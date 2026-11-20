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

package dev.onvoid.webrtc.livekit;

import static java.util.Objects.requireNonNull;

import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.client.ChannelOptions;
import dev.onvoid.webrtc.client.DataChannel;
import dev.onvoid.webrtc.client.Encoding;
import dev.onvoid.webrtc.client.LocalTrack;
import dev.onvoid.webrtc.client.MediaKind;
import dev.onvoid.webrtc.client.Negotiation;
import dev.onvoid.webrtc.client.Peer;
import dev.onvoid.webrtc.client.PeerOptions;
import dev.onvoid.webrtc.client.PeerState;
import dev.onvoid.webrtc.client.RemoteTrack;
import dev.onvoid.webrtc.client.Sender;
import dev.onvoid.webrtc.client.SignalMessage;
import dev.onvoid.webrtc.client.Simulcast;
import dev.onvoid.webrtc.client.Subscription;
import dev.onvoid.webrtc.client.WebRtc;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitModels.DataPacket;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitModels.DisconnectReason;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitModels.ParticipantInfo;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitModels.TrackType;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitModels.UserPacket;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitModels.VideoLayer;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitRtc.AddTrackRequest;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitRtc.ICEServer;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitRtc.JoinResponse;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitRtc.LeaveRequest;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitRtc.Ping;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitRtc.RequestResponse;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitRtc.SessionDescription;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitRtc.SignalRequest;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitRtc.SignalResponse;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitRtc.SignalTarget;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitRtc.SimulateScenario;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitRtc.SubscribedCodec;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitRtc.SubscribedQuality;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitRtc.SubscribedQualityUpdate;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitRtc.SyncState;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitRtc.TrackPublishedResponse;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitRtc.TrickleRequest;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitRtc.UpdateSubscription;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitRtc.UpdateTrackSettings;

/**
 * The room: the state LiveKit's signaling describes, and the publisher and
 * subscriber peers it negotiates. Everything but the handler lists is
 * confined to the room's single thread, which also runs the handlers.
 * <p>
 * A lost connection is recovered as LiveKit's own clients do. A resume opens
 * the signaling again for the same session and restarts ICE on both peers,
 * which keeps the peers, their tracks and data channels. Should the server
 * refuse it, or should it fail, the room joins anew, with new peers, and
 * publishes its tracks again.
 */
final class RoomImpl implements Room {

	private static final System.Logger LOG = System.getLogger(Room.class.getName());

	private static final String RELIABLE_LABEL = "_reliable";

	private static final String LOSSY_LABEL = "_lossy";

	/** Reconnect attempts before the room gives up, as LiveKit's clients make. */
	private static final int MAX_ATTEMPTS = 10;

	private static final long BACKOFF_STEP_MILLIS = 300;

	private static final long MAX_BACKOFF_MILLIS = 10_000;

	private final WebRtc rtc;

	private final URI url;

	private final boolean autoSubscribe;

	private final Duration timeout;

	private final HttpClient http = HttpClient.newHttpClient();

	private final ScheduledExecutorService thread;

	/** Runs on the room's thread, and drops what comes after the room is gone. */
	private final Executor executor;

	private final CompletableFuture<Room> joined = new CompletableFuture<>();

	private final CompletableFuture<Void> closed = new CompletableFuture<>();

	private final List<Consumer<Participant>> joinedHandlers = new CopyOnWriteArrayList<>();

	private final List<Consumer<Participant>> leftHandlers = new CopyOnWriteArrayList<>();

	private final List<BiConsumer<Participant, RemoteTrack>> trackHandlers = new CopyOnWriteArrayList<>();

	private final List<Consumer<DataMessage>> dataHandlers = new CopyOnWriteArrayList<>();

	private final List<Consumer<String>> reconnectingHandlers = new CopyOnWriteArrayList<>();

	private final List<Runnable> reconnectedHandlers = new CopyOnWriteArrayList<>();

	private final List<Consumer<String>> disconnectHandlers = new CopyOnWriteArrayList<>();

	/** Hear which layers dynacast left on, once applied. For tests. */
	final List<BiConsumer<Publication, Map<VideoQuality, Boolean>>> layerListeners = new CopyOnWriteArrayList<>();

	// Room thread only, unless volatile.

	private volatile RoomState state = RoomState.CONNECTING;

	/** The token for the next connection; the server refreshes it. */
	private volatile String token;

	private volatile String name = "";

	private volatile Participant local;

	/** By sid, in join order. Written on the room thread, read anywhere. */
	private volatile Map<String, Participant> participants = Map.of();

	/** Completes whenever the room is connected; operations wait on it. */
	private CompletableFuture<Void> connectedGate = new CompletableFuture<>();

	private SignalClient signal;

	/** The first message the open signaling waits for: a join or reconnect response. */
	private CompletableFuture<SignalResponse> firstResponse;

	private SignalResponse.MessageCase firstResponseCase;

	/** Tracks that arrived before the participant that publishes them, by participant sid. */
	private final Map<String, List<RemoteTrack>> orphanTracks = new HashMap<>();

	/** The remote tracks handed to the track handlers, for handlers added later. */
	private final List<SubscribedTrack> subscribedTracks = new ArrayList<>();

	private final Map<String, PendingPublish> pendingPublishes = new HashMap<>();

	/** The published tracks, by sid. */
	private final Map<String, Publication> publications = new LinkedHashMap<>();

	private Peer publisher;

	private Peer subscriber;

	private DataChannel reliable;

	private DataChannel lossy;

	private boolean negotiating;

	private boolean renegotiate;

	private boolean publisherNegotiated;

	private boolean subscriberAnswered;

	/** Wait for the offer that is out. */
	private List<CompletableFuture<Void>> negotiationWaiters = new ArrayList<>();

	/** Wait for the offer after the one that is out. */
	private List<CompletableFuture<Void>> nextNegotiationWaiters = new ArrayList<>();

	private int offerId;

	private int dataSequence = 1;

	private int pingIntervalSeconds;

	private int pingTimeoutSeconds;

	private ScheduledFuture<?> pingTask;

	private long lastPongMillis;

	// Recovery.

	private boolean fullReconnect;

	private int attempts;

	/** Counts the attempts, so that the steps of an abandoned one stop. */
	private int generation;

	private CompletableFuture<Void> currentAttempt;


	private RoomImpl(WebRtc rtc, URI url, String token, boolean autoSubscribe, Duration timeout) {
		this.rtc = rtc;
		this.url = url;
		this.token = token;
		this.autoSubscribe = autoSubscribe;
		this.timeout = timeout;
		this.thread = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread t = new Thread(task, "livekit-room");
			t.setDaemon(true);
			return t;
		});
		this.executor = task -> {
			try {
				thread.execute(task);
			}
			catch (RejectedExecutionException e) {
				// The room is closed and every future of it completed.
			}
		};
	}

	static CompletableFuture<Room> connect(WebRtc rtc, URI url, String token, boolean autoSubscribe,
			Duration timeout) {
		// Fails here on a URL that is not one.
		SignalClient.rtcUri(url, LiveKit.PROTOCOL, autoSubscribe);

		RoomImpl room = new RoomImpl(rtc, url, token, autoSubscribe, timeout);

		// Not async: the session is set up before the room thread takes the
		// messages that follow the join, such as the server's first offer.
		room.executor.execute(() -> room.openSignal(false).whenComplete((response, error) -> {
			if (error != null) {
				room.failJoin(unwrap(error));
				return;
			}
			if (room.joined.isDone()) {
				return;
			}

			try {
				room.setUpSession(response.getJoin(), false);
			}
			catch (RuntimeException e) {
				room.failJoin(e);
				return;
			}

			room.state = RoomState.CONNECTED;
			room.connectedGate.complete(null);
			room.joined.complete(room);
		}));

		return room.joined.copy();
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public RoomState state() {
		return state;
	}

	@Override
	public Participant localParticipant() {
		return local;
	}

	@Override
	public List<Participant> remoteParticipants() {
		return List.copyOf(participants.values());
	}

	@Override
	public CompletableFuture<Publication> publish(LocalTrack track, TrackSource source) {
		requireNonNull(source, "source");

		return publish(track, PublishOptions.builder().source(source).build());
	}

	@Override
	public CompletableFuture<Publication> publish(LocalTrack track, PublishOptions options) {
		requireNonNull(track, "track");
		requireNonNull(options, "options");

		if (options.simulcast && track.kind() != MediaKind.VIDEO) {
			throw new IllegalArgumentException("Simulcast takes a video track, got " + track.kind());
		}

		CompletableFuture<Publication> result = new CompletableFuture<>();
		result.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);

		post(() -> whenConnected(result, () -> startPublish(track, options, null, result)), result);

		return result;
	}

	@Override
	public void requestVideoQuality(String trackSid, VideoQuality quality) {
		requireNonNull(trackSid, "trackSid");
		requireNonNull(quality, "quality");

		executor.execute(() -> {
			if (state != RoomState.CONNECTED) {
				return;
			}

			signal.send(SignalRequest.newBuilder()
					.setTrackSetting(UpdateTrackSettings.newBuilder()
							.addTrackSids(trackSid)
							.setQuality(quality.proto()))
					.build());
		});
	}

	@Override
	public CompletableFuture<Void> publishData(byte[] payload, String topic, boolean reliable) {
		requireNonNull(payload, "payload");

		byte[] data = payload.clone();
		CompletableFuture<Void> result = new CompletableFuture<>();
		result.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);

		post(() -> whenConnected(result, () -> sendData(data, topic, reliable, result)), result);

		return result;
	}

	@Override
	public Subscription onParticipantJoined(Consumer<Participant> handler) {
		return subscribe(joinedHandlers, handler);
	}

	@Override
	public Subscription onParticipantLeft(Consumer<Participant> handler) {
		return subscribe(leftHandlers, handler);
	}

	@Override
	public Subscription onTrackSubscribed(BiConsumer<Participant, RemoteTrack> handler) {
		requireNonNull(handler, "handler");

		// On the room thread, so that a track is handed over either here or
		// when it arrives, never both.
		boolean[] removed = new boolean[1];
		executor.execute(() -> {
			if (removed[0]) {
				return;
			}
			trackHandlers.add(handler);

			for (SubscribedTrack subscribed : List.copyOf(subscribedTracks)) {
				if (!subscribed.track.isEnded()) {
					call(() -> handler.accept(subscribed.participant, subscribed.track));
				}
			}
		});

		return () -> executor.execute(() -> {
			removed[0] = true;
			trackHandlers.remove(handler);
		});
	}

	@Override
	public Subscription onData(Consumer<DataMessage> handler) {
		return subscribe(dataHandlers, handler);
	}

	@Override
	public Subscription onReconnecting(Consumer<String> handler) {
		return subscribe(reconnectingHandlers, handler);
	}

	@Override
	public Subscription onReconnected(Runnable handler) {
		return subscribe(reconnectedHandlers, handler);
	}

	@Override
	public Subscription onDisconnected(Consumer<String> handler) {
		return subscribe(disconnectHandlers, handler);
	}

	@Override
	public CompletableFuture<Void> closeAsync() {
		executor.execute(() -> disconnect("The application left the room", true));
		return closed.copy();
	}

	@Override
	public void close() {
		try {
			closeAsync().get(timeout.toMillis() * 2, TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		catch (Exception e) {
			LOG.log(Level.WARNING, "Closing the room did not finish", e);
		}
	}

	// For tests.

	/** Cuts the signaling connection off, as a network failure would. */
	void cutSignal() {
		executor.execute(() -> {
			if (signal != null) {
				signal.abort();
			}
		});
	}

	/** Has the server act out a scenario, as a server started with --dev does. */
	void simulate(SimulateScenario scenario) {
		executor.execute(() -> signal.send(SignalRequest.newBuilder().setSimulate(scenario).build()));
	}

	// Signaling, on the room thread.

	/**
	 * Opens a signaling connection, which replaces the one before, and waits
	 * for its first response: a join response for a new session, a reconnect
	 * response for a resumed one. The future completes on the room thread,
	 * while it handles that response, so that stages added without async
	 * run before the messages behind it.
	 */
	private CompletableFuture<SignalResponse> openSignal(boolean resume) {
		SignalClient[] self = new SignalClient[1];
		SignalClient client = new SignalClient(executor, new SignalClient.Handler() {

			@Override
			public void onSignal(SignalResponse response) {
				if (signal == self[0]) {
					RoomImpl.this.onSignal(response);
				}
			}

			@Override
			public void onSignalClosed(String reason) {
				if (signal == self[0]) {
					RoomImpl.this.onSignalClosed(reason);
				}
			}
		});
		self[0] = client;

		if (signal != null) {
			signal.close();
		}
		signal = client;

		CompletableFuture<SignalResponse> first = new CompletableFuture<>();
		firstResponse = first;
		firstResponseCase = resume ? SignalResponse.MessageCase.RECONNECT : SignalResponse.MessageCase.JOIN;

		URI uri = SignalClient.rtcUri(url, LiveKit.PROTOCOL, autoSubscribe, resume ? local.sid() : null);

		client.open(http, uri, token, timeout).whenCompleteAsync((v, error) -> {
			if (error != null) {
				first.completeExceptionally(unwrap(error));
			}
		}, executor);

		ScheduledFuture<?> deadline = thread.schedule(() -> first.completeExceptionally(
				new LiveKitException("No " + (resume ? "reconnect" : "join") + " response within " + timeout)),
				timeout.toMillis(), TimeUnit.MILLISECONDS);
		first.whenComplete((r, e) -> deadline.cancel(false));

		return first;
	}

	private void onSignal(SignalResponse response) {
		if (state == RoomState.DISCONNECTED) {
			return;
		}

		switch (response.getMessageCase()) {
			case JOIN:
			case RECONNECT:
				if (firstResponse != null && firstResponseCase == response.getMessageCase()) {
					firstResponse.complete(response);
				}
				break;
			case ANSWER:
				onAnswer(response.getAnswer());
				break;
			case OFFER:
				onOffer(response.getOffer());
				break;
			case TRICKLE:
				onTrickle(response.getTrickle());
				break;
			case UPDATE:
				onParticipants(response.getUpdate().getParticipantsList());
				break;
			case TRACK_PUBLISHED:
				onTrackPublished(response.getTrackPublished());
				break;
			case SUBSCRIBED_QUALITY_UPDATE:
				onSubscribedQualities(response.getSubscribedQualityUpdate());
				break;
			case LEAVE:
				onLeave(response.getLeave());
				break;
			case PONG_RESP:
				lastPongMillis = System.currentTimeMillis();
				break;
			case REFRESH_TOKEN:
				token = response.getRefreshToken();
				break;
			case REQUEST_RESPONSE:
				RequestResponse answer = response.getRequestResponse();
				if (answer.getReason() != RequestResponse.Reason.OK) {
					LOG.log(Level.WARNING, "The server refused request " + answer.getRequestId() + ": "
							+ answer.getReason() + " " + answer.getMessage());
				}
				break;
			default:
				LOG.log(Level.DEBUG, () -> "Ignored " + response.getMessageCase());
		}
	}

	private void onSignalClosed(String reason) {
		if (!joined.isDone()) {
			failJoin(new LiveKitException(reason));
			return;
		}

		if (firstResponse != null && !firstResponse.isDone()) {
			firstResponse.completeExceptionally(new LiveKitException(reason));
		}

		if (state == RoomState.CONNECTED) {
			recover(false, reason);
		}
		else if (state == RoomState.RECONNECTING && currentAttempt != null) {
			currentAttempt.completeExceptionally(new LiveKitException(reason));
		}
	}

	private void onLeave(LeaveRequest leave) {
		String reason = "The server ended the session (" + leave.getReason() + ", " + leave.getAction() + ")";

		if (!joined.isDone()) {
			failJoin(new LiveKitException(reason));
			return;
		}

		LeaveRequest.Action action = leave.getAction();
		if (action == LeaveRequest.Action.DISCONNECT && leave.getCanReconnect()) {
			// What older servers send for a reconnect.
			action = LeaveRequest.Action.RECONNECT;
		}

		switch (action) {
			case RESUME:
				recover(false, reason);
				break;
			case RECONNECT:
				recover(true, reason);
				break;
			default:
				disconnect(reason, false);
		}
	}

	/**
	 * Takes the response of a join: the room, the participants and the ICE
	 * servers, from which it makes the peers of the session.
	 *
	 * @param rejoined true if this is a full reconnect, whose participants
	 *                 are reported as joined again.
	 */
	private void setUpSession(JoinResponse join, boolean rejoined) {
		name = join.getRoom().getName();
		local = new Participant(join.getParticipant());

		Map<String, Participant> others = new LinkedHashMap<>();
		for (ParticipantInfo info : join.getOtherParticipantsList()) {
			others.put(info.getSid(), new Participant(info));
		}
		participants = others;

		publisher = createPeer(SignalTarget.PUBLISHER, join.getIceServersList());
		subscriber = createPeer(SignalTarget.SUBSCRIBER, join.getIceServersList());

		// The channels go into the first offer of the publisher.
		reliable = publisher.createChannel(RELIABLE_LABEL, ChannelOptions.builder().ordered(true).build());
		lossy = publisher.createChannel(LOSSY_LABEL,
				ChannelOptions.builder().ordered(false).maxRetransmits(0).build());

		receiveData(reliable);
		receiveData(lossy);

		Peer sessionSubscriber = subscriber;
		sessionSubscriber.onTrack(track -> executor.execute(() -> {
			if (sessionSubscriber == subscriber) {
				onRemoteTrack(track);
			}
		}));
		sessionSubscriber.onChannel(channel -> executor.execute(() -> {
			if (sessionSubscriber == subscriber) {
				receiveData(channel);
			}
		}));

		pingIntervalSeconds = join.getPingInterval();
		pingTimeoutSeconds = join.getPingTimeout();
		startPing();

		if (rejoined) {
			for (Participant participant : others.values()) {
				for (Consumer<Participant> handler : joinedHandlers) {
					call(() -> handler.accept(participant));
				}
			}
		}

		if (!join.getSubscriberPrimary() || join.getFastPublish()) {
			negotiatePublisher();
		}
	}

	private Peer createPeer(SignalTarget target, List<ICEServer> servers) {
		PeerOptions.Builder options = PeerOptions.builder()
				.name("livekit-" + target.name().toLowerCase())
				.negotiation(Negotiation.MANUAL)
				.signaling(message -> {
					// A manual peer signals only its candidates.
					if (message.type() == SignalMessage.Type.CANDIDATE) {
						RTCIceCandidate candidate = message.candidate();
						executor.execute(() -> sendTrickle(candidate, target));
					}
				});

		for (ICEServer server : servers) {
			for (String serverUrl : server.getUrlsList()) {
				if (server.getUsername().isEmpty()) {
					options.iceServer(serverUrl);
				}
				else {
					options.iceServer(serverUrl, server.getUsername(), server.getCredential());
				}
			}
		}

		Peer peer = rtc.createPeer(options.build());
		peer.onError(e -> LOG.log(Level.WARNING, "The " + target + " peer failed", e));
		peer.onStateChange(peerState -> executor.execute(() -> {
			boolean current = peer == publisher || peer == subscriber;
			if (current && peerState == PeerState.FAILED && state == RoomState.CONNECTED) {
				recover(false, "The " + target.name().toLowerCase() + " connection failed");
			}
		}));

		return peer;
	}

	private void sendTrickle(RTCIceCandidate candidate, SignalTarget target) {
		if (state == RoomState.DISCONNECTED) {
			return;
		}

		signal.send(SignalRequest.newBuilder()
				.setTrickle(TrickleRequest.newBuilder()
						.setCandidateInit(CandidateJson.encode(candidate))
						.setTarget(target))
				.build());
	}

	private void onTrickle(TrickleRequest trickle) {
		Peer peer = trickle.getTarget() == SignalTarget.PUBLISHER ? publisher : subscriber;
		if (peer == null) {
			return;
		}

		RTCIceCandidate candidate;
		try {
			candidate = CandidateJson.decode(trickle.getCandidateInit());
		}
		catch (IllegalArgumentException e) {
			LOG.log(Level.WARNING, "Dropped a candidate that does not parse", e);
			return;
		}

		peer.negotiator().addCandidate(candidate).exceptionally(e -> {
			LOG.log(Level.DEBUG, () -> "A remote candidate was refused: " + e);
			return null;
		});
	}

	// The publisher offers, the server answers.

	private CompletableFuture<Void> negotiatePublisher() {
		CompletableFuture<Void> negotiated = new CompletableFuture<>();

		if (negotiating) {
			// The offer that is out may predate what the caller changed.
			nextNegotiationWaiters.add(negotiated);
			renegotiate = true;
		}
		else {
			negotiationWaiters.add(negotiated);
			sendOffer();
		}

		return negotiated;
	}

	private void sendOffer() {
		negotiating = true;
		Peer peer = publisher;

		peer.negotiator().createOffer().whenCompleteAsync((offer, error) -> {
			if (peer != publisher) {
				return;
			}
			if (error != null) {
				negotiationDone(error);
				return;
			}
			if (state == RoomState.DISCONNECTED) {
				return;
			}

			signal.send(SignalRequest.newBuilder()
					.setOffer(SessionDescription.newBuilder()
							.setType("offer")
							.setSdp(offer.sdp)
							.setId(++offerId))
					.build());
		}, executor);
	}

	private void onAnswer(SessionDescription answer) {
		if (publisher == null || !negotiating) {
			return;
		}

		Peer peer = publisher;
		peer.negotiator().applyAnswer(new RTCSessionDescription(RTCSdpType.ANSWER, answer.getSdp()))
				.whenCompleteAsync((v, error) -> {
					if (peer == publisher) {
						negotiationDone(error);
					}
				}, executor);
	}

	private void negotiationDone(Throwable error) {
		negotiating = false;

		if (error == null) {
			publisherNegotiated = true;
		}
		else {
			LOG.log(Level.WARNING, "Negotiating the publisher failed", error);
		}

		List<CompletableFuture<Void>> waiters = negotiationWaiters;
		negotiationWaiters = nextNegotiationWaiters;
		nextNegotiationWaiters = new ArrayList<>();

		if (renegotiate && state != RoomState.DISCONNECTED) {
			renegotiate = false;
			sendOffer();
		}

		for (CompletableFuture<Void> waiter : waiters) {
			if (error == null) {
				waiter.complete(null);
			}
			else {
				waiter.completeExceptionally(error);
			}
		}
	}

	/**
	 * Offers again with ICE restarted, for a resumed session: the answer to an
	 * offer that was out may have been lost with the old connection, so its
	 * waiters wait for the new one.
	 */
	private void restartPublisherIce() {
		List<CompletableFuture<Void>> waiters = new ArrayList<>(negotiationWaiters);
		waiters.addAll(nextNegotiationWaiters);
		negotiationWaiters = new ArrayList<>();
		nextNegotiationWaiters = new ArrayList<>();
		negotiating = false;
		renegotiate = false;

		if (!publisherNegotiated && waiters.isEmpty()) {
			return;
		}

		publisher.restartIce();
		negotiationWaiters.addAll(waiters);
		sendOffer();
	}

	// The server offers, the subscriber answers.

	private void onOffer(SessionDescription offer) {
		if (subscriber == null) {
			return;
		}

		Peer peer = subscriber;
		peer.negotiator().answer(new RTCSessionDescription(RTCSdpType.OFFER, offer.getSdp()))
				.whenCompleteAsync((answer, error) -> {
					if (peer != subscriber || state == RoomState.DISCONNECTED) {
						return;
					}
					if (error != null) {
						LOG.log(Level.WARNING, "Answering the server's offer failed", error);
						return;
					}

					subscriberAnswered = true;
					signal.send(SignalRequest.newBuilder()
							.setAnswer(SessionDescription.newBuilder()
									.setType("answer")
									.setSdp(answer.sdp)
									.setId(offer.getId()))
							.build());
				}, executor);
	}

	// Publishing.

	/**
	 * Asks the server to take a track.
	 *
	 * @param existing the publication to publish again in a new session, or
	 *                 null for a new one.
	 */
	private void startPublish(LocalTrack track, PublishOptions options, Publication existing,
			CompletableFuture<Publication> result) {
		// The server knows the track by this id until it names its own.
		String cid = track.id();
		if (pendingPublishes.containsKey(cid)) {
			result.completeExceptionally(new LiveKitException("The track " + cid + " is being published"));
			return;
		}

		pendingPublishes.put(cid, new PendingPublish(track, options, existing, result));
		result.whenCompleteAsync((p, e) -> {
			PendingPublish pending = pendingPublishes.get(cid);
			if (pending != null && pending.result == result) {
				pendingPublishes.remove(cid);
			}
		}, executor);

		AddTrackRequest.Builder request = AddTrackRequest.newBuilder()
				.setCid(cid)
				.setName(options.name != null ? options.name : track.id())
				.setType(track.kind() == MediaKind.VIDEO ? TrackType.VIDEO : TrackType.AUDIO)
				.setSource(options.source.proto());

		if (track.kind() == MediaKind.VIDEO && options.height > 0) {
			request.setWidth(options.width).setHeight(options.height);

			List<PublishOptions.Layer> layers = options.layers();
			if (layers.isEmpty()) {
				request.addLayers(VideoLayer.newBuilder()
						.setQuality(VideoQuality.HIGH.proto())
						.setWidth(options.width)
						.setHeight(options.height));
			}
			for (PublishOptions.Layer layer : layers) {
				request.addLayers(VideoLayer.newBuilder()
						.setQuality(layer.quality.proto())
						.setRid(layer.quality.rid())
						.setWidth(layer.width)
						.setHeight(layer.height)
						.setBitrate(layer.bitrate));
			}
		}

		signal.send(SignalRequest.newBuilder().setAddTrack(request).build());
	}

	private void onTrackPublished(TrackPublishedResponse published) {
		PendingPublish pending = pendingPublishes.remove(published.getCid());
		if (pending == null || pending.result.isDone()) {
			return;
		}

		Sender sender;
		try {
			Simulcast simulcast = pending.options.simulcastLayers();
			sender = simulcast != null
					? publisher.addTrack(pending.track, simulcast)
					: publisher.addTrack(pending.track);
		}
		catch (RuntimeException e) {
			pending.result.completeExceptionally(e);
			return;
		}

		Publication publication;
		if (pending.existing != null) {
			publication = pending.existing;
			publication.republished(published.getTrack(), sender);
		}
		else {
			publication = new Publication(published.getTrack(), pending.options, pending.track, sender);
		}

		Peer peer = publisher;
		negotiatePublisher().whenComplete((v, error) -> {
			if (error != null) {
				pending.result.completeExceptionally(error);
			}
			else if (peer == publisher) {
				publications.put(publication.sid(), publication);
				pending.result.complete(publication);
			}
			else {
				pending.result.completeExceptionally(new LiveKitException("The session ended"));
			}
		});
	}

	/**
	 * Dynacast: the server names the layers some subscriber wants. Sending
	 * the others would only cost bandwidth and CPU, so they pause.
	 */
	private void onSubscribedQualities(SubscribedQualityUpdate update) {
		Publication publication = publications.get(update.getTrackSid());
		if (publication == null || publication.rids().isEmpty()) {
			return;
		}

		// One codec is published; older servers send the qualities without it.
		List<SubscribedQuality> qualities = update.getSubscribedQualitiesList();
		for (SubscribedCodec codec : update.getSubscribedCodecsList()) {
			qualities = codec.getQualitiesList();
		}

		Map<VideoQuality, Boolean> enabled = new EnumMap<>(VideoQuality.class);
		List<CompletableFuture<Void>> changes = new ArrayList<>();

		for (SubscribedQuality quality : qualities) {
			VideoQuality layer = VideoQuality.of(quality.getQuality());
			if (layer == null || !publication.rids().contains(layer.rid())) {
				continue;
			}

			enabled.put(layer, quality.getEnabled());
			changes.add(publication.sender().configure(layer.rid(),
					Encoding.builder().active(quality.getEnabled()).build()));
		}

		CompletableFuture.allOf(changes.toArray(new CompletableFuture[0])).whenCompleteAsync((v, error) -> {
			if (error != null) {
				LOG.log(Level.WARNING, "Pausing or resuming layers of " + publication.sid() + " failed", error);
				return;
			}
			for (BiConsumer<Publication, Map<VideoQuality, Boolean>> listener : layerListeners) {
				call(() -> listener.accept(publication, enabled));
			}
		}, executor);
	}

	// Data.

	private void sendData(byte[] payload, String topic, boolean reliable, CompletableFuture<Void> result) {
		UserPacket.Builder user = UserPacket.newBuilder().setPayload(ByteString.copyFrom(payload));
		if (topic != null) {
			user.setTopic(topic);
		}

		DataPacket.Builder packet = DataPacket.newBuilder()
				.setParticipantIdentity(local.identity())
				.setUser(user);
		if (reliable) {
			packet.setSequence(dataSequence++);
		}
		byte[] bytes = packet.build().toByteArray();

		DataChannel channel = reliable ? this.reliable : this.lossy;
		CompletableFuture<Void> ready = publisherNegotiated || negotiating
				? CompletableFuture.completedFuture(null)
				: negotiatePublisher();

		ready.thenCompose(v -> channel.whenOpen())
				.thenCompose(open -> open.send(bytes))
				.whenComplete((v, error) -> {
					if (error != null) {
						result.completeExceptionally(error);
					}
					else {
						result.complete(null);
					}
				});
	}

	private void receiveData(DataChannel channel) {
		boolean reliableChannel = !LOSSY_LABEL.equals(channel.label());

		channel.onMessage(message -> {
			if (message.isText()) {
				return;
			}
			byte[] bytes = message.bytes();
			executor.execute(() -> onDataPacket(bytes, reliableChannel));
		});
	}

	private void onDataPacket(byte[] bytes, boolean reliableChannel) {
		DataPacket packet;
		try {
			packet = DataPacket.parseFrom(bytes);
		}
		catch (InvalidProtocolBufferException e) {
			LOG.log(Level.WARNING, "Dropped a data packet that does not parse", e);
			return;
		}

		if (!packet.hasUser()) {
			return;
		}

		UserPacket user = packet.getUser();
		DataMessage message = new DataMessage(packet.getParticipantIdentity(),
				user.hasTopic() ? user.getTopic() : null, user.getPayload().toByteArray(), reliableChannel);

		for (Consumer<DataMessage> handler : dataHandlers) {
			call(() -> handler.accept(message));
		}
	}

	// Participants and their tracks.

	private void onParticipants(List<ParticipantInfo> infos) {
		Map<String, Participant> next = new LinkedHashMap<>(participants);
		List<Participant> joinedNow = new ArrayList<>();
		List<Participant> leftNow = new ArrayList<>();

		for (ParticipantInfo info : infos) {
			if (local != null && info.getSid().equals(local.sid())) {
				local.update(info);
				continue;
			}

			Participant participant = next.get(info.getSid());

			if (info.getState() == ParticipantInfo.State.DISCONNECTED) {
				if (participant != null) {
					next.remove(info.getSid());
					orphanTracks.remove(info.getSid());
					leftNow.add(participant);
				}
			}
			else if (participant == null) {
				participant = new Participant(info);
				next.put(info.getSid(), participant);
				joinedNow.add(participant);
			}
			else {
				participant.update(info);
			}
		}

		participants = next;

		for (Participant participant : joinedNow) {
			for (Consumer<Participant> handler : joinedHandlers) {
				call(() -> handler.accept(participant));
			}

			List<RemoteTrack> tracks = orphanTracks.remove(participant.sid());
			if (tracks != null) {
				tracks.forEach(track -> trackSubscribed(participant, track));
			}
		}
		for (Participant participant : leftNow) {
			fireLeft(participant);
		}
	}

	private void onRemoteTrack(RemoteTrack track) {
		if (state == RoomState.DISCONNECTED) {
			return;
		}

		// LiveKit's stream ids are "<participant sid>|<track sid>".
		String participantSid = null;
		for (String streamId : track.streamIds()) {
			int separator = streamId.indexOf('|');
			if (separator > 0) {
				participantSid = streamId.substring(0, separator);
				break;
			}
		}

		Participant participant = participantSid == null ? null : participants.get(participantSid);
		if (participant == null) {
			for (Participant candidate : participants.values()) {
				if (candidate.publishes(track.id())) {
					participant = candidate;
					break;
				}
			}
		}

		if (participant == null) {
			if (participantSid != null) {
				orphanTracks.computeIfAbsent(participantSid, sid -> new ArrayList<>()).add(track);
			}
			else {
				LOG.log(Level.WARNING, "Dropped a track of an unknown participant: " + track.id());
			}
			return;
		}

		trackSubscribed(participant, track);
	}

	private void trackSubscribed(Participant participant, RemoteTrack track) {
		subscribedTracks.removeIf(subscribed -> subscribed.track.isEnded());
		subscribedTracks.add(new SubscribedTrack(participant, track));

		for (BiConsumer<Participant, RemoteTrack> handler : trackHandlers) {
			call(() -> handler.accept(participant, track));
		}
	}

	private void fireLeft(Participant participant) {
		subscribedTracks.removeIf(subscribed -> subscribed.participant == participant);

		for (Consumer<Participant> handler : leftHandlers) {
			call(() -> handler.accept(participant));
		}
	}

	// Keepalive.

	private void startPing() {
		stopPing();

		if (pingIntervalSeconds <= 0) {
			return;
		}

		long timeoutMillis = TimeUnit.SECONDS.toMillis(pingTimeoutSeconds);
		lastPongMillis = System.currentTimeMillis();

		pingTask = thread.scheduleAtFixedRate(() -> {
			long now = System.currentTimeMillis();

			if (timeoutMillis > 0 && now - lastPongMillis > timeoutMillis) {
				recover(false, "The server did not answer pings for " + pingTimeoutSeconds + " s");
				return;
			}

			signal.send(SignalRequest.newBuilder()
					.setPingReq(Ping.newBuilder().setTimestamp(now))
					.build());
		}, pingIntervalSeconds, pingIntervalSeconds, TimeUnit.SECONDS);
	}

	private void stopPing() {
		if (pingTask != null) {
			pingTask.cancel(false);
			pingTask = null;
		}
	}

	// Recovery, on the room thread.

	/**
	 * Starts getting the connection back, unless the room already is.
	 *
	 * @param full true to join anew, as the server asks when it cannot
	 *             resume the session.
	 */
	private void recover(boolean full, String reason) {
		if (state == RoomState.DISCONNECTED || state == RoomState.CONNECTING) {
			return;
		}

		if (state == RoomState.RECONNECTING) {
			if (full && !fullReconnect) {
				fullReconnect = true;
				if (currentAttempt != null) {
					currentAttempt.completeExceptionally(new LiveKitException(reason));
				}
			}
			return;
		}

		LOG.log(Level.INFO, "Reconnecting the room " + name + ": " + reason);

		state = RoomState.RECONNECTING;
		connectedGate = new CompletableFuture<>();
		fullReconnect = full;
		attempts = 0;

		stopPing();
		signal.close();

		for (Consumer<String> handler : reconnectingHandlers) {
			call(() -> handler.accept(reason));
		}

		nextAttempt(reason);
	}

	private void nextAttempt(String lastFailure) {
		if (state != RoomState.RECONNECTING) {
			return;
		}
		if (attempts >= MAX_ATTEMPTS) {
			disconnect("Reconnecting failed " + attempts + " times, last: " + lastFailure, false);
			return;
		}

		long delay = Math.min(BACKOFF_STEP_MILLIS * attempts * attempts, MAX_BACKOFF_MILLIS);
		attempts++;

		thread.schedule(this::runAttempt, delay, TimeUnit.MILLISECONDS);
	}

	private void runAttempt() {
		if (state != RoomState.RECONNECTING) {
			return;
		}

		int attemptGeneration = ++generation;
		boolean full = fullReconnect;
		CompletableFuture<Void> attempt = new CompletableFuture<>();
		currentAttempt = attempt;

		(full ? restartSession(attemptGeneration) : resumeSession(attemptGeneration)).whenComplete((v, error) -> {
			if (error != null) {
				attempt.completeExceptionally(unwrap(error));
			}
			else {
				attempt.complete(null);
			}
		});

		attempt.orTimeout(timeout.toMillis() * 2, TimeUnit.MILLISECONDS).whenCompleteAsync((v, error) -> {
			if (attempt != currentAttempt || state != RoomState.RECONNECTING) {
				return;
			}
			currentAttempt = null;

			if (error == null) {
				reconnected(full);
				return;
			}

			String failure = String.valueOf(unwrap(error).getMessage());
			LOG.log(Level.INFO, (full ? "Rejoining" : "Resuming") + " the room " + name + " failed: " + failure);

			// A session that cannot be resumed is joined anew.
			fullReconnect = true;
			nextAttempt(failure);
		}, executor);
	}

	/**
	 * Resumes the session: the signaling opens for the same participant, the
	 * publisher restarts ICE, the server restarts it on the subscriber with an
	 * offer, and once both are connected the server hears what this side has.
	 */
	private CompletableFuture<Void> resumeSession(int attemptGeneration) {
		return openSignal(true)
				.thenCompose(response -> {
					checkGeneration(attemptGeneration);
					startPing();
					restartPublisherIce();
					return whenPeersConnected();
				})
				.thenComposeAsync(v -> {
					checkGeneration(attemptGeneration);
					return sendSyncState();
				}, executor);
	}

	/**
	 * Joins anew: the old peers go, the participants are reported as left,
	 * and the published tracks are published again on new peers.
	 */
	private CompletableFuture<Void> restartSession(int attemptGeneration) {
		endSession(new LiveKitException("The session is joined anew"));

		return openSignal(false)
				.thenCompose(response -> {
					checkGeneration(attemptGeneration);
					setUpSession(response.getJoin(), true);
					return republish();
				});
	}

	/** Drops the peers and participants of the session, for a new one. */
	private void endSession(LiveKitException error) {
		List<Participant> left = new ArrayList<>(participants.values());
		participants = Map.of();
		orphanTracks.clear();
		left.forEach(this::fireLeft);

		failPending(error);

		if (publisher != null) {
			publisher.closeAsync();
		}
		if (subscriber != null) {
			subscriber.closeAsync();
		}
		publisher = null;
		subscriber = null;
		reliable = null;
		lossy = null;
		negotiating = false;
		renegotiate = false;
		publisherNegotiated = false;
		subscriberAnswered = false;
	}

	private CompletableFuture<Void> republish() {
		List<Publication> previous = new ArrayList<>(publications.values());
		publications.clear();

		List<CompletableFuture<Publication>> republished = new ArrayList<>();
		for (Publication publication : previous) {
			CompletableFuture<Publication> result = new CompletableFuture<>();
			startPublish(publication.track(), publication.options(), publication, result);
			republished.add(result);
		}

		return CompletableFuture.allOf(republished.toArray(new CompletableFuture[0]));
	}

	private CompletableFuture<Void> whenPeersConnected() {
		List<CompletableFuture<Void>> connected = new ArrayList<>();

		if (publisherNegotiated || negotiating) {
			connected.add(whenConnected(publisher));
		}
		if (subscriberAnswered) {
			connected.add(whenConnected(subscriber));
		}

		return CompletableFuture.allOf(connected.toArray(new CompletableFuture[0]));
	}

	private static CompletableFuture<Void> whenConnected(Peer peer) {
		CompletableFuture<Void> connected = new CompletableFuture<>();
		Subscription subscription = peer.onStateChange(peerState -> {
			if (peerState == PeerState.CONNECTED) {
				connected.complete(null);
			}
		});
		connected.whenComplete((v, e) -> subscription.close());

		if (peer.state() == PeerState.CONNECTED) {
			connected.complete(null);
		}

		return connected;
	}

	/**
	 * Tells the server what the subscriber negotiated, what it subscribes to
	 * and what this side publishes, as a resumed session needs when it moved
	 * to another server.
	 */
	private CompletableFuture<Void> sendSyncState() {
		if (!subscriberAnswered) {
			return CompletableFuture.completedFuture(null);
		}

		return subscriber.withNative(connection -> new RTCSessionDescription[] {
				connection.getRemoteDescription(), connection.getLocalDescription() })
				.thenAcceptAsync(descriptions -> {
					if (descriptions[0] == null || descriptions[1] == null) {
						return;
					}

					SyncState.Builder sync = SyncState.newBuilder()
							.setOffer(SessionDescription.newBuilder().setType("offer").setSdp(descriptions[0].sdp))
							.setAnswer(SessionDescription.newBuilder().setType("answer").setSdp(descriptions[1].sdp))
							// With auto subscribe, the tracks listed would be those unsubscribed.
							.setSubscription(UpdateSubscription.newBuilder().setSubscribe(!autoSubscribe));

					for (Publication publication : publications.values()) {
						sync.addPublishTracks(TrackPublishedResponse.newBuilder()
								.setCid(publication.track().id())
								.setTrack(publication.info()));
					}

					signal.send(SignalRequest.newBuilder().setSyncState(sync).build());
				}, executor);
	}

	private void reconnected(boolean full) {
		LOG.log(Level.INFO, (full ? "Rejoined" : "Resumed") + " the room " + name);

		state = RoomState.CONNECTED;
		attempts = 0;
		fullReconnect = false;
		connectedGate.complete(null);

		for (Runnable handler : reconnectedHandlers) {
			call(handler);
		}
	}

	private void checkGeneration(int attemptGeneration) {
		if (attemptGeneration != generation || state != RoomState.RECONNECTING) {
			throw new LiveKitException("The attempt was abandoned");
		}
	}

	// Leaving.

	private void failJoin(Throwable error) {
		if (joined.isDone()) {
			return;
		}

		joined.completeExceptionally(error);
		disconnect(String.valueOf(error.getMessage()), false);
	}

	private void disconnect(String reason, boolean sendLeave) {
		if (state == RoomState.DISCONNECTED) {
			return;
		}
		boolean wasConnected = state == RoomState.CONNECTED;
		state = RoomState.DISCONNECTED;

		stopPing();
		if (signal != null) {
			if (sendLeave && wasConnected) {
				signal.send(SignalRequest.newBuilder()
						.setLeave(LeaveRequest.newBuilder()
								.setReason(DisconnectReason.CLIENT_INITIATED)
								.setAction(LeaveRequest.Action.DISCONNECT))
						.build());
			}
			signal.close();
		}

		LiveKitException error = new LiveKitException("The room was left: " + reason);
		joined.completeExceptionally(error);
		connectedGate.completeExceptionally(error);
		if (firstResponse != null) {
			firstResponse.completeExceptionally(error);
		}
		if (currentAttempt != null) {
			currentAttempt.completeExceptionally(error);
		}

		failPending(error);
		orphanTracks.clear();
		subscribedTracks.clear();
		publications.clear();

		CompletableFuture<Void> publisherClosed = publisher != null
				? publisher.closeAsync()
				: CompletableFuture.completedFuture(null);
		CompletableFuture<Void> subscriberClosed = subscriber != null
				? subscriber.closeAsync()
				: CompletableFuture.completedFuture(null);

		CompletableFuture.allOf(publisherClosed, subscriberClosed)
				.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
				.whenComplete((v, closeError) -> executor.execute(() -> {
					for (Consumer<String> handler : disconnectHandlers) {
						call(() -> handler.accept(reason));
					}

					if (closeError != null) {
						closed.completeExceptionally(closeError instanceof TimeoutException
								? new LiveKitException("The peers did not close in time", closeError)
								: closeError);
					}
					else {
						closed.complete(null);
					}

					thread.shutdown();
				}));
	}

	/** Fails the publishes and negotiations of the session. */
	private void failPending(LiveKitException error) {
		List<PendingPublish> publishes = new ArrayList<>(pendingPublishes.values());
		pendingPublishes.clear();
		publishes.forEach(pending -> pending.result.completeExceptionally(error));

		List<CompletableFuture<Void>> waiters = new ArrayList<>(negotiationWaiters);
		waiters.addAll(nextNegotiationWaiters);
		negotiationWaiters = new ArrayList<>();
		nextNegotiationWaiters = new ArrayList<>();
		waiters.forEach(waiter -> waiter.completeExceptionally(error));
	}

	// Helpers.

	/**
	 * Runs an operation on the room thread once the room is connected, at
	 * once if it is, and fails the future if the room is left first.
	 */
	private void whenConnected(CompletableFuture<?> result, Runnable operation) {
		connectedGate.whenCompleteAsync((v, error) -> {
			if (result.isDone()) {
				return;
			}
			if (error != null) {
				result.completeExceptionally(unwrap(error));
				return;
			}
			if (state != RoomState.CONNECTED) {
				// Lost again before the operation got its turn: wait for the next.
				whenConnected(result, operation);
				return;
			}
			operation.run();
		}, executor);
	}

	private void post(Runnable task, CompletableFuture<?> future) {
		try {
			thread.execute(task);
		}
		catch (RejectedExecutionException e) {
			future.completeExceptionally(new LiveKitException("The room is closed"));
		}
	}

	private static Throwable unwrap(Throwable error) {
		while (error instanceof CompletionException && error.getCause() != null) {
			error = error.getCause();
		}
		return error;
	}

	private static <T> Subscription subscribe(List<T> handlers, T handler) {
		requireNonNull(handler, "handler");
		handlers.add(handler);
		return () -> handlers.remove(handler);
	}

	private static void call(Runnable handler) {
		try {
			handler.run();
		}
		catch (RuntimeException e) {
			LOG.log(Level.WARNING, "A room handler failed", e);
		}
	}



	private static final class PendingPublish {

		final LocalTrack track;

		final PublishOptions options;

		final Publication existing;

		final CompletableFuture<Publication> result;


		PendingPublish(LocalTrack track, PublishOptions options, Publication existing,
				CompletableFuture<Publication> result) {
			this.track = track;
			this.options = options;
			this.existing = existing;
			this.result = result;
		}
	}



	/** A remote track and the participant that publishes it. */
	private static final class SubscribedTrack {

		final Participant participant;

		final RemoteTrack track;


		SubscribedTrack(Participant participant, RemoteTrack track) {
			this.participant = participant;
			this.track = track;
		}
	}

}
