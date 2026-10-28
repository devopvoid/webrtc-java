/*
 * Copyright 2026 Alex Andres
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.onvoid.webrtc.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.internal.Timeouts;

/**
 * The ICE of a WHIP or WHEP session once its offer is answered, carried by
 * PATCH requests to the session (RFC 9725, 4.3; draft-ietf-wish-whep-05, 4.5).
 * The candidates the peer gathers trickle to the session, a few at a time,
 * and an ICE restart exchanges only the ICE information of a new offer, as
 * an SDP fragment of RFC 8840.
 * <p>
 * A session that takes no PATCH of a kind, which it says with 405, 415, 422
 * or 501, gets none of that kind any more. It finds the peer all the same,
 * from the checks the peer sends to its candidates.
 */
final class HttpIce {

	/** The media type of the SDP fragments. */
	static final String FRAGMENT_TYPE = "application/trickle-ice-sdpfrag";

	/** How long the connection may stay disconnected before ICE restarts. */
	static final Duration DISCONNECTED_GRACE = Duration.ofSeconds(2);

	/** The most restarts in a row that ICE makes by itself without success. */
	static final int MAX_AUTOMATIC_RESTARTS = 3;

	/** The largest fragment read; one holds a few candidates. */
	private static final int MAX_FRAGMENT = 64 * 1024;

	/** The statuses with which a session says it takes no PATCH of a kind. */
	private static final Set<Integer> UNSUPPORTED = Set.of(405, 415, 422, 501);

	private final WebRtc rtc;

	private final HttpClient client;

	private final PeerImpl peer;

	private final String bearerToken;

	private final Duration timeout;

	/** Where the PATCH requests go, once the endpoint named it. */
	private URI session;

	/** The entity-tag of the ICE session, or null if the session sent none. */
	private String etag;

	/** The ICE information of the local description, as a fragment. */
	private Fragment local;

	/** Whether candidates go to the session. */
	private boolean trickle;

	/** The candidates gathered and not sent yet. */
	private final List<String> pending = new ArrayList<>();

	/** Whether gathering completed since the last candidates were sent. */
	private boolean gathered;

	private HttpCall candidatesRequest;

	private HttpCall restartRequest;

	/** Whether an ICE restart waits for its answer, which candidates wait for too. */
	private boolean restarting;

	private boolean restartsRefused;

	private int automaticRestarts;

	private ScheduledFuture<?> disconnected;

	private Subscription stateSubscription;

	private boolean closed;


	HttpIce(WebRtc rtc, HttpClient client, PeerImpl peer, String bearerToken, Duration timeout) {
		this.rtc = rtc;
		this.client = client;
		this.peer = peer;
		this.bearerToken = bearerToken;
		this.timeout = timeout;

		peer.onLocalCandidates(this::candidate, this::gathered);
	}

	/**
	 * Tells the session the endpoint named, before the offer is applied and
	 * its candidates are gathered, which then go there.
	 *
	 * @param session where the PATCH requests go, or null for none.
	 * @param etag    the entity-tag of the ICE session, or null.
	 * @param offer   the local offer.
	 */
	synchronized void attach(URI session, String etag, String offer) {
		if (closed || session == null) {
			return;
		}

		this.session = session;
		this.etag = etag;
		this.local = Fragment.of(offer);
		this.trickle = true;
	}

	/**
	 * Restarts ICE by itself from now on, when the connection fails, or stays
	 * disconnected for {@link #DISCONNECTED_GRACE}.
	 */
	void watch() {
		Subscription subscription = peer.onStateChange(this::stateChanged);

		synchronized (this) {
			if (closed) {
				subscription.close();
				return;
			}

			stateSubscription = subscription;
		}
	}

	/**
	 * Restarts ICE: the peer gathers new candidates for a new offer, whose ICE
	 * information alone goes to the session, and the session's comes back.
	 *
	 * @return a future that completes once both sides' ICE information is
	 * applied, or fails, the ICE session before going on.
	 */
	CompletableFuture<Void> restart() {
		synchronized (this) {
			if (closed) {
				return CompletableFuture.failedFuture(new PeerClosedException("The session is closed"));
			}
			if (session == null) {
				return CompletableFuture.failedFuture(new WebRtcException("ICE cannot restart: the endpoint named "
						+ "no session, or answered with an offer of its own"));
			}
		}

		Fragment[] before = new Fragment[1];

		return peer.restartIce(offer -> {
			HttpCall request;
			Fragment fragment = Fragment.of(offer.sdp);

			synchronized (this) {
				if (closed) {
					return CompletableFuture.failedFuture(new PeerClosedException("The session is closed"));
				}

				before[0] = local;
				local = fragment;
				restarting = true;
				pending.clear();
				gathered = false;

				request = new HttpCall(client, session, "PATCH", bearerToken, timeout).header("If-Match", "*");
				restartRequest = request;
			}

			return request.send(FRAGMENT_TYPE, fragment.text.getBytes(StandardCharsets.UTF_8), MAX_FRAGMENT,
					head -> { }).handle((response, error) -> answered(request, response, error));
		}).whenComplete((restarted, error) -> {
			synchronized (this) {
				restarting = false;
				restartRequest = null;

				if (error != null && before[0] != null) {
					// Taken back: the candidates of the new offer go nowhere.
					local = before[0];
					pending.clear();
					gathered = false;
				}
			}

			flush();
		});
	}

	/**
	 * Stops sending: cuts off the requests under way, and makes no more.
	 */
	void close() {
		HttpCall candidates;
		HttpCall restart;
		Subscription subscription;
		ScheduledFuture<?> wait;

		synchronized (this) {
			if (closed) {
				return;
			}

			closed = true;
			candidates = candidatesRequest;
			restart = restartRequest;
			subscription = stateSubscription;
			wait = disconnected;
		}

		if (candidates != null) {
			candidates.abort();
		}
		if (restart != null) {
			restart.abort();
		}
		if (subscription != null) {
			subscription.close();
		}
		if (wait != null) {
			wait.cancel(false);
		}
	}

	/**
	 * Reads the answer to an ICE restart.
	 *
	 * @return how to change the remote answer.
	 */
	private UnaryOperator<String> answered(HttpCall request, HttpCall.Response response, Throwable error) {
		URI target;

		synchronized (this) {
			target = session;
		}

		if (error != null) {
			throw new WebRtcException("Restarting ICE of the session " + target + " failed: " + error.getMessage(),
					error);
		}
		if (UNSUPPORTED.contains(response.status)) {
			synchronized (this) {
				restartsRefused = true;
			}

			throw new WebRtcException("The session " + target + " takes no ICE restarts: status "
					+ response.status);
		}
		if (response.status / 100 != 2) {
			throw new WebRtcException("The session " + target + " answered the ICE restart with status "
					+ response.status + ": " + response.body);
		}

		Fragment remote = Fragment.parse(response.body);

		if (remote.ufrag == null || remote.pwd == null) {
			throw new WebRtcException("The session " + target + " answered the ICE restart without "
					+ "its new ICE credentials");
		}

		synchronized (this) {
			etag = response.header("ETag");
		}

		return remote::applyTo;
	}

	// On the control executor.

	private void candidate(RTCIceCandidate candidate) {
		synchronized (this) {
			if (closed || !trickle || !local.owns(candidate.sdp)) {
				return;
			}

			pending.add("a=" + candidate.sdp);
		}

		flush();
	}

	private void gathered() {
		synchronized (this) {
			if (closed || !trickle) {
				return;
			}

			gathered = true;
		}

		flush();
	}

	/**
	 * Sends the candidates gathered since the last request, unless one is
	 * under way, or an ICE restart waits for the session's new entity-tag.
	 */
	private void flush() {
		HttpCall request;
		String body;
		URI target;

		synchronized (this) {
			if (closed || !trickle || restarting || candidatesRequest != null || (pending.isEmpty() && !gathered)) {
				return;
			}

			StringBuilder fragment = new StringBuilder(local.text);
			pending.forEach(line -> fragment.append(line).append("\r\n"));

			if (gathered) {
				fragment.append("a=end-of-candidates\r\n");
			}

			pending.clear();
			gathered = false;

			request = new HttpCall(client, session, "PATCH", bearerToken, timeout);

			if (etag != null) {
				request.header("If-Match", etag);
			}

			candidatesRequest = request;
			body = fragment.toString();
			target = session;
		}

		request.send(FRAGMENT_TYPE, body.getBytes(StandardCharsets.UTF_8), 0, head -> { })
				.whenComplete((response, error) -> {
					boolean report;

					synchronized (this) {
						candidatesRequest = null;

						if (error == null && UNSUPPORTED.contains(response.status)) {
							// The session finds the peer from its checks.
							trickle = false;
						}

						// A precondition that fails belongs to an ICE session
						// a restart replaced.
						report = !closed && (error != null || (response.status / 100 != 2
								&& !UNSUPPORTED.contains(response.status) && response.status != 412
								&& response.status != 428));

						if (report) {
							trickle = false;
						}
					}

					if (report) {
						rtc.reportError(new WebRtcException("Sending ICE candidates to the session " + target
								+ " failed, and no more are sent: " + (error != null
								? error.getMessage()
								: "status " + response.status), error));
					}

					flush();
				});
	}

	/**
	 * Follows the state of the connection, on the event executor; tests call
	 * it as well.
	 */
	void stateChanged(PeerState state) {
		switch (state) {
			case CONNECTED:
				synchronized (this) {
					automaticRestarts = 0;
				}
				cancelWait();
				break;

			case DISCONNECTED:
				waitForReconnect();
				break;

			case FAILED:
				cancelWait();
				restartByItself();
				break;

			case CLOSED:
				close();
				break;

			default:
				break;
		}
	}

	private void waitForReconnect() {
		synchronized (this) {
			if (closed || disconnected != null) {
				return;
			}

			try {
				disconnected = rtc.timer.schedule(() -> {
					synchronized (this) {
						disconnected = null;
					}

					if (peer.state() == PeerState.DISCONNECTED) {
						restartByItself();
					}
				}, Timeouts.toMillis(DISCONNECTED_GRACE), TimeUnit.MILLISECONDS);
			}
			catch (RejectedExecutionException e) {
				// The runtime is closing, and the peer with it.
			}
		}
	}

	private void cancelWait() {
		ScheduledFuture<?> wait;

		synchronized (this) {
			wait = disconnected;
			disconnected = null;
		}

		if (wait != null) {
			wait.cancel(false);
		}
	}

	private void restartByItself() {
		URI target;

		synchronized (this) {
			if (closed || session == null || restartsRefused || automaticRestarts >= MAX_AUTOMATIC_RESTARTS) {
				return;
			}

			automaticRestarts++;
			target = session;
		}

		restart().whenComplete((restarted, error) -> {
			boolean report;

			synchronized (this) {
				report = error != null && !closed;
			}

			if (report) {
				rtc.reportError(new WebRtcException("Restarting ICE of the session " + target
						+ " after the connection broke failed", PeerImpl.unwrap(error)));
			}
		});
	}

	/**
	 * Reads the STUN and TURN servers an endpoint names in Link header fields
	 * with the relation "ice-server" (RFC 9725, 4.6).
	 *
	 * @param fields the values of the Link header fields.
	 *
	 * @return the servers, in order.
	 */
	static List<RTCIceServer> iceServers(List<String> fields) {
		List<RTCIceServer> servers = new ArrayList<>();

		for (String field : fields) {
			int i = 0;

			while (true) {
				int open = field.indexOf('<', i);
				int close = open < 0 ? -1 : field.indexOf('>', open);

				if (close < 0) {
					break;
				}

				String target = field.substring(open + 1, close).trim();
				Map<String, String> parameters = new HashMap<>();
				i = parameters(field, close + 1, parameters);

				String rel = parameters.getOrDefault("rel", "");
				boolean iceServer = false;

				for (String relation : rel.trim().split("\\s+")) {
					iceServer |= relation.equalsIgnoreCase("ice-server");
				}

				if (iceServer && !target.isEmpty()) {
					RTCIceServer server = new RTCIceServer();
					server.urls.add(target);
					server.username = parameters.get("username");
					server.password = parameters.get("credential");
					servers.add(server);
				}
			}
		}

		return servers;
	}

	/**
	 * Reads the parameters of a link, up to the comma that ends it.
	 *
	 * @return where the next link starts.
	 */
	private static int parameters(String field, int start, Map<String, String> parameters) {
		int i = start;

		while (i < field.length() && field.charAt(i) != ',') {
			if (field.charAt(i) != ';') {
				i++;
				continue;
			}

			int nameStart = ++i;

			while (i < field.length() && "=;,".indexOf(field.charAt(i)) < 0) {
				i++;
			}

			String name = field.substring(nameStart, i).trim().toLowerCase(Locale.ROOT);
			String value = "";

			if (i < field.length() && field.charAt(i) == '=') {
				i++;

				while (i < field.length() && field.charAt(i) == ' ') {
					i++;
				}

				StringBuilder text = new StringBuilder();

				if (i < field.length() && field.charAt(i) == '"') {
					i++;

					while (i < field.length() && field.charAt(i) != '"') {
						if (field.charAt(i) == '\\' && i + 1 < field.length()) {
							i++;
						}

						text.append(field.charAt(i++));
					}

					i++;
				}
				else {
					while (i < field.length() && ";,".indexOf(field.charAt(i)) < 0) {
						text.append(field.charAt(i++));
					}
				}

				value = text.toString().trim();
			}

			parameters.putIfAbsent(name, value);
		}

		return i + 1;
	}



	/**
	 * The ICE information of a description as an SDP fragment of RFC 8840:
	 * the ICE options, the BUNDLE group, and of the m-line that tags the group
	 * its line, mid, username fragment and password. Candidates follow it.
	 */
	static final class Fragment {

		/** The fragment, each line ended by CRLF. */
		final String text;

		final String ufrag;

		final String pwd;

		/** The candidate lines of a fragment that was read. */
		final List<String> candidates;

		/** Whether a fragment that was read ends the candidates. */
		final boolean endOfCandidates;


		private Fragment(String text, String ufrag, String pwd, List<String> candidates, boolean endOfCandidates) {
			this.text = text;
			this.ufrag = ufrag;
			this.pwd = pwd;
			this.candidates = candidates;
			this.endOfCandidates = endOfCandidates;
		}

		/**
		 * Takes the ICE information out of a description.
		 */
		static Fragment of(String sdp) {
			List<List<String>> sections = sections(sdp);
			List<String> session = sections.get(0);
			String group = first(session, "a=group:BUNDLE");
			String tag = group != null && group.trim().split("\\s+").length > 1
					? group.trim().split("\\s+")[1]
					: null;
			List<String> media = sections.size() > 1 ? sections.get(1) : null;

			for (List<String> section : sections.subList(1, sections.size())) {
				if (tag != null && tag.equals(value(section, "a=mid:"))) {
					media = section;
					break;
				}
			}

			String options = first(session, "a=ice-options:");
			String ufrag = media != null && value(media, "a=ice-ufrag:") != null
					? value(media, "a=ice-ufrag:")
					: value(session, "a=ice-ufrag:");
			String pwd = media != null && value(media, "a=ice-pwd:") != null
					? value(media, "a=ice-pwd:")
					: value(session, "a=ice-pwd:");

			StringBuilder text = new StringBuilder();
			append(text, options != null ? options : media != null ? first(media, "a=ice-options:") : null);
			append(text, group);

			if (media != null) {
				append(text, media.get(0));
				append(text, first(media, "a=mid:"));
			}

			append(text, ufrag != null ? "a=ice-ufrag:" + ufrag : null);
			append(text, pwd != null ? "a=ice-pwd:" + pwd : null);

			return new Fragment(text.toString(), ufrag, pwd, new ArrayList<>(), false);
		}

		/**
		 * Reads a fragment a session sent.
		 */
		static Fragment parse(String body) {
			String ufrag = null;
			String pwd = null;
			List<String> candidates = new ArrayList<>();
			boolean end = false;

			for (String line : body.split("\r?\n")) {
				String trimmed = line.trim();

				if (trimmed.startsWith("a=ice-ufrag:") && ufrag == null) {
					ufrag = trimmed.substring("a=ice-ufrag:".length()).trim();
				}
				else if (trimmed.startsWith("a=ice-pwd:") && pwd == null) {
					pwd = trimmed.substring("a=ice-pwd:".length()).trim();
				}
				else if (trimmed.startsWith("a=candidate:")) {
					candidates.add(trimmed);
				}
				else if (trimmed.equals("a=end-of-candidates")) {
					end = true;
				}
			}

			return new Fragment(body, ufrag, pwd, candidates, end);
		}

		/**
		 * @return whether a local candidate belongs to the ICE session of
		 * this fragment, by the username fragment it names; one that names
		 * none does.
		 */
		boolean owns(String candidate) {
			String[] fields = candidate.trim().split("\\s+");

			for (int i = 0; i + 1 < fields.length; i++) {
				if (fields[i].equals("ufrag")) {
					return ufrag == null || ufrag.equals(fields[i + 1]);
				}
			}

			return true;
		}

		/**
		 * Puts the ICE information of this fragment, which a session sent,
		 * into the remote answer applied before. Its candidates replace those
		 * of the answer; with none, the answer keeps its own, since a session
		 * that names none has kept them.
		 */
		String applyTo(String answer) {
			List<List<String>> sections = sections(answer);
			String group = first(sections.get(0), "a=group:BUNDLE");
			String tag = group != null && group.trim().split("\\s+").length > 1
					? group.trim().split("\\s+")[1]
					: null;
			boolean replace = !candidates.isEmpty();
			boolean placed = false;
			StringBuilder sdp = new StringBuilder();

			for (int s = 0; s < sections.size(); s++) {
				List<String> section = sections.get(s);
				boolean target = s > 0 && !placed && (tag == null || tag.equals(value(section, "a=mid:")));

				for (String line : section) {
					if (line.startsWith("a=ice-ufrag:")) {
						line = "a=ice-ufrag:" + ufrag;
					}
					else if (line.startsWith("a=ice-pwd:")) {
						line = "a=ice-pwd:" + pwd;
					}
					else if (replace && (line.startsWith("a=candidate:") || line.equals("a=end-of-candidates"))) {
						continue;
					}

					append(sdp, line);
				}

				if (target && replace) {
					candidates.forEach(candidate -> append(sdp, candidate));

					if (endOfCandidates) {
						append(sdp, "a=end-of-candidates");
					}

					placed = true;
				}
			}

			return sdp.toString();
		}

		/**
		 * Splits a description into its session part and its media sections,
		 * each a list of lines without their ends.
		 */
		private static List<List<String>> sections(String sdp) {
			List<List<String>> sections = new ArrayList<>();
			List<String> section = new ArrayList<>();
			sections.add(section);

			for (String line : sdp.split("\r?\n")) {
				if (line.isEmpty()) {
					continue;
				}
				if (line.startsWith("m=")) {
					section = new ArrayList<>();
					sections.add(section);
				}

				section.add(line);
			}

			return sections;
		}

		private static String first(List<String> lines, String prefix) {
			for (String line : lines) {
				if (line.startsWith(prefix)) {
					return line;
				}
			}

			return null;
		}

		private static String value(List<String> lines, String prefix) {
			String line = first(lines, prefix);

			return line != null ? line.substring(prefix.length()).trim() : null;
		}

		private static void append(StringBuilder text, String line) {
			if (line != null) {
				text.append(line).append("\r\n");
			}
		}
	}
}
