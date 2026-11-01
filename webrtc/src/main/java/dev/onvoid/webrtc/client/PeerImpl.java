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

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCEncodedFrameTransformer;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceGatheringState;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCRtpCapabilities;
import dev.onvoid.webrtc.RTCRtpCodecCapability;
import dev.onvoid.webrtc.RTCRtpEncodingParameters;
import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCRtpTransceiverDirection;
import dev.onvoid.webrtc.RTCRtpTransceiverInit;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.RTCSignalingState;
import dev.onvoid.webrtc.client.internal.EncodedFrames;
import dev.onvoid.webrtc.media.MediaStream;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.MediaType;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.video.VideoTrack;

/**
 * The {@link Peer} of a {@link WebRtc} runtime.
 * <p>
 * The state of a peer lives on two serial executors. The control executor
 * owns the connection and everything negotiation needs; WebRTC's callbacks
 * only post to it, and it is the only place that calls the connection. The
 * event executor runs application code, and owns what the handlers have been
 * told so far. Neither ever waits for the other, and nothing on a WebRTC
 * thread waits for either, so no handler can deadlock the connection.
 * <p>
 * Changes that the standard runs on its operations chain go through the
 * {@link OperationChain}, which starts one only once the one before finished,
 * made of the {@link NegotiationSteps}. The automatic negotiation is the
 * {@link PerfectNegotiation}; all three are confined to the control executor
 * as well.
 */
final class PeerImpl implements Peer {

	static {
		// Senders and remote tracks come from peers, so whoever holds one
		// finds the access to their encoded frames registered.
		EncodedFrames.register(new EncodedFramesAccess());
	}

	/**
	 * The codecs that go along with those a session names: retransmission,
	 * redundancy, FEC, comfort noise and DTMF, which serve the media codecs.
	 */
	private static final Set<String> COMPANION_CODECS = Set.of("rtx", "red", "ulpfec", "flexfec-03", "cn",
			"telephone-event");

	final WebRtc rtc;

	final String name;

	final SerialExecutor control;

	final SerialExecutor events;

	private final PeerOptions options;

	private final Signaling signaling;

	/** The stream the tracks added without stream ids share. */
	private final String defaultStreamId = UUID.randomUUID().toString();

	private final AtomicBoolean closing = new AtomicBoolean();

	private final CompletableFuture<Void> closedInternal = new CompletableFuture<>();

	private final CopyOnWriteArrayList<SenderImpl> senders = new CopyOnWriteArrayList<>();

	private final CopyOnWriteArrayList<RemoteTrackImpl> remoteTracks = new CopyOnWriteArrayList<>();

	private volatile PeerState state = PeerState.NEW;

	// Confined to the control executor.

	private RTCPeerConnection connection;

	private boolean closed;

	private boolean teardownStarted;

	private final OperationChain chain;

	private final NegotiationSteps steps;

	private final PerfectNegotiation automatic;

	private final Map<String, RemoteTrackImpl> remoteTrackById = new HashMap<>();

	private final List<DataChannelImpl> channels = new ArrayList<>();

	private final StatsReader statsReader = new StatsReader();

	// Confined to the event executor.

	private PeerState deliveredState = PeerState.NEW;

	private final List<RemoteTrack> deliveredTracks = new ArrayList<>();

	private final List<DataChannel> deliveredChannels = new ArrayList<>();

	private final Handlers<PeerState> stateHandlers;

	private final Handlers<RemoteTrack> trackHandlers;

	private final Handlers<DataChannel> channelHandlers;

	private final Handlers<WebRtcException> errorHandlers;

	private final ClientFuture<Peer> connected = new ClientFuture<>();

	private final ClientFuture<Void> closedFuture = new ClientFuture<>();

	private final NegotiatorImpl negotiator = new NegotiatorImpl();

	/**
	 * The kinds of media whose tracks this peer drops, those it declined when
	 * it answered an offer that replaced its own. Only on the control
	 * executor.
	 */
	private final Set<MediaKind> declinedKinds = EnumSet.noneOf(MediaKind.class);

	/**
	 * The codecs each kind of media is negotiated with, in order, for the
	 * kinds a session named codecs of. Only on the control executor.
	 */
	private final Map<MediaKind, List<RTCRtpCodecCapability>> codecs = new EnumMap<>(MediaKind.class);

	/** Gets the local candidates as well, or null. Called on the control executor. */
	private volatile Consumer<RTCIceCandidate> candidateListener;

	/** Runs once gathering is complete, or null. Called on the control executor. */
	private volatile Runnable gatheredListener;

	/**
	 * What {@link Signaling#attach} returned, or null before, and {@link
	 * #DETACHED} once the peer has let go of the signaling on close.
	 */
	private final AtomicReference<Subscription> signalingSubscription = new AtomicReference<>();

	private static final Subscription DETACHED = () -> { };


	PeerImpl(WebRtc rtc, PeerOptions options, String name) {
		this.rtc = rtc;
		this.options = options;
		this.name = name;
		this.signaling = options.signaling;
		this.control = new SerialExecutor(rtc.controlPool, rtc.controlWork, rtc::reportError, false, null);
		this.events = new SerialExecutor(rtc.eventExecutor, rtc.eventWork, rtc::reportError, true,
				rtc.ownsEventExecutor ? "webrtc-client-events-" + name : null);
		this.stateHandlers = new Handlers<>(rtc::reportError);
		this.trackHandlers = new Handlers<>(rtc::reportError);
		this.channelHandlers = new Handlers<>(rtc::reportError);
		this.errorHandlers = new Handlers<>(rtc::reportError);
		this.chain = new OperationChain(control, rtc.timer, rtc.operationTimeout, name, new ChainHost());
		this.steps = new NegotiationSteps(control, rtc.timer, chain, () -> connection, options.gatheringTimeout,
				this::ignoresOffer);
		this.automatic = new PerfectNegotiation(this, options, chain, steps);

		rtc.track(connected, events);
		rtc.track(closedFuture, events);
	}

	/**
	 * Creates the connection on the control executor and waits for it, so that
	 * a configuration WebRTC refuses fails the caller.
	 */
	void open() {
		CompletableFuture<Void> opened = new CompletableFuture<>();

		control.execute(() -> {
			if (closed) {
				// The runtime closed the peer before this ran, and may already
				// be freeing the factory.
				opened.completeExceptionally(closedError());
				return;
			}

			try {
				connection = rtc.factory.createPeerConnection(options.toConfiguration(), new Observer());

				if (connection == null) {
					throw new ConfigurationException("WebRTC refused the configuration of " + name
							+ ". Check the ICE server URLs and the values set through PeerOptions.Builder.configure");
				}

				for (MediaKind kind : options.receive) {
					RTCRtpTransceiverInit init = new RTCRtpTransceiverInit();
					init.direction = RTCRtpTransceiverDirection.RECV_ONLY;

					RTCRtpTransceiver transceiver = connection.addTransceiver(
							kind == MediaKind.AUDIO ? MediaType.AUDIO : MediaType.VIDEO, init);
					transceiver.dispose();
				}

				opened.complete(null);
			}
			catch (Throwable e) {
				closed = true;

				if (connection != null) {
					connection.close();
					connection = null;
				}

				opened.completeExceptionally(e);
			}
		}, () -> {
			// A runtime that stopped its threads dropped the task.
			opened.completeExceptionally(new PeerClosedException("The runtime closed before " + name
					+ " was created"));
		});

		try {
			opened.get();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new WebRtcException("Interrupted while creating " + name, e);
		}
		catch (ExecutionException e) {
			Throwable cause = e.getCause();

			if (cause instanceof WebRtcException) {
				throw (WebRtcException) cause;
			}

			throw new ConfigurationException("Creating " + name + " failed: " + cause.getMessage(), cause);
		}

		if (signaling != null && !closing.get()) {
			Subscription subscription = signaling.attach(this::signal);

			if (subscription != null && !signalingSubscription.compareAndSet(null, subscription)) {
				// Closed while attaching: the close found nothing to let go of
				// yet, so this lets go of what attach returned.
				detach(subscription);
			}
		}
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public PeerState state() {
		return state;
	}

	@Override
	public void signal(SignalMessage message) {
		requireNonNull(message, "message");

		control.execute(() -> automatic.signal(message));
	}

	@Override
	public Sender addTrack(LocalTrack track, String... streamIds) {
		LocalTrackImpl local = checkTrack(track);

		List<String> ids = new ArrayList<>();

		for (String id : streamIds) {
			ids.add(requireNonNull(id, "streamId"));
		}
		if (ids.isEmpty()) {
			ids.add(defaultStreamId);
		}

		return addSender(local, ids, Collections.emptyList(), new CompletableFuture<>());
	}

	/**
	 * Adds a track to the stream of the tracks added without stream ids, sent
	 * in the given encodings, as simulcast (RFC 8853) does with one for each
	 * layer.
	 *
	 * @return a future that completes once the track is added, or fails if
	 * WebRTC refuses the encodings.
	 */
	CompletableFuture<Void> addTrack(LocalTrack track, List<RTCRtpEncodingParameters> encodings) {
		CompletableFuture<Void> added = new CompletableFuture<>();

		addSender(checkTrack(track), List.of(defaultStreamId), encodings, added);

		return added;
	}

	private SenderImpl addSender(LocalTrackImpl local, List<String> ids, List<RTCRtpEncodingParameters> encodings,
			CompletableFuture<Void> added) {
		SenderImpl sender = new SenderImpl(this, local);
		senders.add(sender);

		control.execute(() -> {
			if (closed) {
				senders.remove(sender);
				sender.failAttach(closedError());
				added.completeExceptionally(closedError());
				return;
			}

			try {
				sender.attach(connection, ids, encodings, guard(options.sendTransform, "a sender of " + name));
				added.complete(null);
			}
			catch (Throwable e) {
				senders.remove(sender);
				sender.failAttach(e);
				deliverError(asWebRtcException(e, "Adding the track failed"));
				added.completeExceptionally(e);
			}
		}, () -> added.completeExceptionally(closedError()));

		return sender;
	}

	@Override
	public List<Sender> senders() {
		return Collections.unmodifiableList(new ArrayList<Sender>(senders));
	}

	@Override
	public Subscription onTrack(Consumer<RemoteTrack> handler) {
		requireNonNull(handler, "handler");

		return subscribe(() -> {
			for (RemoteTrack track : new ArrayList<>(deliveredTracks)) {
				call(handler, track);
			}
			return trackHandlers.add(handler);
		});
	}

	@Override
	public List<RemoteTrack> remoteTracks() {
		return Collections.unmodifiableList(new ArrayList<RemoteTrack>(remoteTracks));
	}

	@Override
	public DataChannel createChannel(String label) {
		return createChannel(label, ChannelOptions.builder().build());
	}

	@Override
	public DataChannel createChannel(String label, ChannelOptions options) {
		requireNonNull(label, "label");
		requireNonNull(options, "options");

		DataChannelImpl channel = new DataChannelImpl(this, label);

		control.execute(() -> {
			if (closed) {
				channel.closeFromPeer(new PeerClosedException("The peer " + name + " is closed"));
				return;
			}

			try {
				RTCDataChannel nativeChannel = connection.createDataChannel(label, options.toInit());

				if (nativeChannel == null) {
					throw new ConfigurationException("WebRTC refused the data channel " + label);
				}

				channels.add(channel);
				channel.bind(nativeChannel);
			}
			catch (Throwable e) {
				WebRtcException error = asWebRtcException(e, "Creating the data channel " + label + " failed");
				channel.closeFromPeer(error);
				deliverError(error);
			}
		});

		return channel;
	}

	@Override
	public Subscription onChannel(Consumer<DataChannel> handler) {
		requireNonNull(handler, "handler");

		return subscribe(() -> {
			for (DataChannel channel : new ArrayList<>(deliveredChannels)) {
				call(handler, channel);
			}
			return channelHandlers.add(handler);
		});
	}

	@Override
	public Subscription onStateChange(Consumer<PeerState> handler) {
		requireNonNull(handler, "handler");

		return subscribe(() -> {
			call(handler, deliveredState);
			return stateHandlers.add(handler);
		});
	}

	@Override
	public Subscription onError(Consumer<WebRtcException> handler) {
		requireNonNull(handler, "handler");

		return subscribe(() -> errorHandlers.add(handler));
	}

	@Override
	public CompletableFuture<Peer> whenConnected() {
		return ClientFuture.copyOf(connected);
	}

	@Override
	public CompletableFuture<Void> whenClosed() {
		return ClientFuture.copyOf(closedFuture);
	}

	@Override
	public Negotiator negotiator() {
		if (options.negotiation != Negotiation.MANUAL) {
			throw new IllegalStateException("The peer " + name + " negotiates automatically. "
					+ "Build it with Negotiation.MANUAL to drive the exchange by hand");
		}

		return negotiator;
	}

	/**
	 * Creates an offer without applying it, so that the configuration can
	 * still change before candidates are gathered for it, as WHIP and WHEP do
	 * with the ICE servers the endpoint names in its answer.
	 *
	 * @return a future with the offer, which {@link #applyLocal} applies.
	 */
	CompletableFuture<RTCSessionDescription> offer() {
		return negotiator.run(steps::createOffer);
	}

	/**
	 * Applies a description this peer created, which starts the gathering of
	 * candidates for it.
	 */
	CompletableFuture<Void> applyLocal(RTCSessionDescription description) {
		return negotiator.run(() -> steps.setLocal(description));
	}

	/**
	 * Makes every transceiver send only, as a WHIP client's offer should be
	 * (RFC 9725, 4.2). Ordered with the negotiation steps, so it takes effect
	 * on the next offer.
	 */
	CompletableFuture<Void> sendOnly() {
		return negotiator.run(() -> {
			for (RTCRtpTransceiver transceiver : connection.getTransceivers()) {
				try {
					if (!transceiver.stopped()) {
						transceiver.setDirection(RTCRtpTransceiverDirection.SEND_ONLY);
					}
				}
				finally {
					transceiver.dispose();
				}
			}

			return CompletableFuture.completedFuture(null);
		});
	}

	/**
	 * Adds video that only sends, without a track, to the stream of the
	 * tracks added without stream ids, for endpoints that take no offer
	 * without video. Ordered with the negotiation steps.
	 */
	CompletableFuture<Void> addVideoWithoutTrack() {
		return negotiator.run(() -> {
			RTCRtpTransceiverInit init = new RTCRtpTransceiverInit();
			init.direction = RTCRtpTransceiverDirection.SEND_ONLY;
			init.streamIds.add(defaultStreamId);

			connection.addTransceiver(MediaType.VIDEO, init).dispose();

			return CompletableFuture.completedFuture(null);
		});
	}

	/**
	 * Negotiates only the codecs of the given names, in that order, for each
	 * kind of media given, together with the companion codecs WebRTC pairs
	 * with them. This holds for the transceivers there are, and for those an
	 * offer adds later, as a counter-offer this peer answers instead of its
	 * own. Ordered with the negotiation steps, so it takes effect on the next
	 * offer or answer.
	 *
	 * @param names the codec names of each kind, such as H264 or opus.
	 *
	 * @return a future that fails if WebRTC has no codec of a name.
	 */
	CompletableFuture<Void> preferCodecs(Map<MediaKind, List<String>> names) {
		return negotiator.run(() -> {
			for (Map.Entry<MediaKind, List<String>> entry : names.entrySet()) {
				codecs.put(entry.getKey(), codecsNamed(entry.getKey(), entry.getValue()));
			}

			applyCodecs();

			return CompletableFuture.completedFuture(null);
		});
	}

	/**
	 * Finds the codecs of the given names that WebRTC sends or receives,
	 * followed by the companion codecs.
	 *
	 * @throws ConfigurationException if WebRTC has no codec of a name.
	 */
	private List<RTCRtpCodecCapability> codecsNamed(MediaKind kind, List<String> names) {
		MediaType type = kind == MediaKind.AUDIO ? MediaType.AUDIO : MediaType.VIDEO;
		// Those WebRTC sends and receives, each once. WebRTC tells codecs
		// apart as this key does, not by their scalability modes, in which
		// the two lists differ; one codec twice would be offered twice.
		Map<List<Object>, RTCRtpCodecCapability> available = new LinkedHashMap<>();

		for (RTCRtpCapabilities capabilities : List.of(rtc.factory.getRtpSenderCapabilities(type),
				rtc.factory.getRtpReceiverCapabilities(type))) {
			for (RTCRtpCodecCapability codec : capabilities.getCodecs()) {
				available.putIfAbsent(Arrays.asList(codec.getMimeType().toLowerCase(Locale.ROOT),
						codec.getClockRate(), codec.getSDPFmtp()), codec);
			}
		}

		List<RTCRtpCodecCapability> named = new ArrayList<>();

		for (String name : names) {
			boolean found = false;

			for (RTCRtpCodecCapability codec : available.values()) {
				if ((codec.getName().equalsIgnoreCase(name) || codec.getMimeType().equalsIgnoreCase(name))
						&& !named.contains(codec)) {
					named.add(codec);
					found = true;
				}
			}

			if (!found) {
				Set<String> known = new LinkedHashSet<>();
				available.values().forEach(codec -> known.add(codec.getName()));

				throw new ConfigurationException("WebRTC has no " + kind.name().toLowerCase(Locale.ROOT)
						+ " codec " + name + " for " + this.name + ", only " + known);
			}
		}

		for (RTCRtpCodecCapability codec : available.values()) {
			if (COMPANION_CODECS.contains(codec.getName().toLowerCase(Locale.ROOT)) && !named.contains(codec)) {
				named.add(codec);
			}
		}

		return named;
	}

	/**
	 * Sets the codecs of {@link #preferCodecs} on every transceiver that has
	 * not stopped.
	 */
	private void applyCodecs() {
		if (codecs.isEmpty()) {
			return;
		}

		for (RTCRtpTransceiver transceiver : connection.getTransceivers()) {
			try {
				if (transceiver.stopped()) {
					continue;
				}

				RTCRtpReceiver receiver = transceiver.getReceiver();
				MediaStreamTrack track = receiver.getTrack();
				MediaKind kind = MediaStreamTrack.AUDIO_TRACK_KIND.equals(track.getKind())
						? MediaKind.AUDIO
						: MediaKind.VIDEO;

				track.dispose();
				receiver.dispose();

				List<RTCRtpCodecCapability> preferred = codecs.get(kind);

				if (preferred != null) {
					transceiver.setCodecPreferences(preferred);
				}
			}
			finally {
				transceiver.dispose();
			}
		}
	}

	/**
	 * Gathers candidates from now on with the given ICE servers, unless the
	 * peer was built with servers of its own, which take precedence.
	 *
	 * @param servers the ICE servers; none leaves the configuration as it is.
	 *
	 * @return a future that fails if WebRTC refuses the servers.
	 */
	CompletableFuture<Void> useIceServers(List<RTCIceServer> servers) {
		if (servers.isEmpty() || !options.iceServers.isEmpty()) {
			return CompletableFuture.completedFuture(null);
		}

		return negotiator.run(() -> {
			try {
				RTCConfiguration config = connection.getConfiguration();
				config.iceServers = new ArrayList<>(servers);
				connection.setConfiguration(config);

				return CompletableFuture.completedFuture(null);
			}
			catch (RuntimeException e) {
				return CompletableFuture.failedFuture(new ConfigurationException("WebRTC refused the ICE servers "
						+ servers + " for " + name + ": " + e.getMessage(), e));
			}
		});
	}

	/**
	 * Hands the candidates this peer gathers to the given listener as well,
	 * on the control executor, and tells it when gathering is complete.
	 *
	 * @param candidates gets each local candidate.
	 * @param gathered   runs once gathering is complete, each time.
	 */
	void onLocalCandidates(Consumer<RTCIceCandidate> candidates, Runnable gathered) {
		this.candidateListener = candidates;
		this.gatheredListener = gathered;
	}

	/**
	 * Restarts ICE as WHIP and WHEP do, without a renegotiation: only the ICE
	 * information of a new offer goes to the remote peer, and the remote
	 * description keeps its offer/answer role, with the ICE information that
	 * comes back. After a server counter-offer, the new local credentials are
	 * applied in an answer to the updated remote offer.
	 * An exchange that fails takes the new offer back, and the ICE session
	 * before goes on.
	 *
	 * @param exchange sends the ICE information of the new offer, and returns
	 *                 how to change the remote answer.
	 *
	 * @return a future that completes once the new ICE information is applied.
	 */
	CompletableFuture<Void> restartIce(
			Function<RTCSessionDescription, CompletableFuture<UnaryOperator<String>>> exchange) {
		return restartIce(exchange, (value, error) -> { });
	}

	CompletableFuture<Void> restartIce(
			Function<RTCSessionDescription, CompletableFuture<UnaryOperator<String>>> exchange,
			BiConsumer<Void, Throwable> finished) {
		return negotiator.run(() -> {
			RTCSessionDescription remote = connection.getRemoteDescription();

			if (connection.getSignalingState() != RTCSignalingState.STABLE || remote == null
					|| (remote.sdpType != RTCSdpType.ANSWER && remote.sdpType != RTCSdpType.OFFER)) {
				return CompletableFuture.failedFuture(new NegotiationException("The peer " + name
						+ " has no stable description to restart ICE with"));
			}

			connection.restartIce();

			if (remote.sdpType == RTCSdpType.OFFER) {
				// Keep the server's offerer role (including its DTLS role and
				// media sections). Create an offer only to obtain fresh local
				// ICE credentials; do not apply it or change signaling roles.
				return steps.createOffer().thenCompose(offer -> onControl(exchange.apply(offer))
						.thenCompose(change -> steps.setRemote(new RTCSessionDescription(
								RTCSdpType.OFFER, change.apply(remote.sdp))))
						.thenCompose(ignored -> {
							// Reapplying the server's offer can create transceivers
							// for media the original answer rejected.
							stopTransceivers(midsNotReceived(remote.sdp));
							return steps.createAnswer();
						})
						.thenCompose(answer -> steps.setLocal(new RTCSessionDescription(RTCSdpType.ANSWER,
								HttpIce.Fragment.of(offer.sdp).applyCredentialsTo(answer.sdp)))))
						.handle((applied, error) -> {
							if (error == null) {
								return CompletableFuture.<Void>completedFuture(null);
							}
							CompletableFuture<Void> failed = CompletableFuture.failedFuture(unwrap(error));
							if (closed || connection.getSignalingState() != RTCSignalingState.HAVE_REMOTE_OFFER) {
								return failed;
							}
							return steps.setRemote(new RTCSessionDescription(RTCSdpType.ROLLBACK, ""))
									.handle((ignored, rollbackError) -> null)
									.thenCompose(ignored -> failed);
						}).thenCompose(result -> result);
			}

			return steps.createOffer()
					.thenCompose(offer -> steps.setLocal(offer).thenApply(ignored -> offer))
					.thenCompose(offer -> onControl(exchange.apply(offer)))
					.thenCompose(change -> {
						RTCSessionDescription answer = connection.getRemoteDescription();

						return steps.setRemote(new RTCSessionDescription(RTCSdpType.ANSWER, change.apply(answer.sdp)));
					})
					.handle((applied, error) -> {
						if (error == null) {
							return CompletableFuture.<Void>completedFuture(null);
						}

						CompletableFuture<Void> failed = CompletableFuture.failedFuture(unwrap(error));

						if (closed || connection.getSignalingState() == RTCSignalingState.STABLE) {
							return failed;
						}

						// Back to the ICE session before, which the server keeps.
						return steps.setLocal(new RTCSessionDescription(RTCSdpType.ROLLBACK, ""))
								.handle((ignored, rollbackError) -> null)
								.thenCompose(ignored -> failed);
					})
					.thenCompose(result -> result);
		}, finished);
	}

	/**
	 * @return a future that completes as the given one, but on the control
	 * executor, or fails once the peer is closed.
	 */
	private <T> CompletableFuture<T> onControl(CompletableFuture<T> future) {
		CompletableFuture<T> result = chain.step();

		future.whenComplete((value, error) -> control.execute(() -> complete(result, value, error),
				() -> result.completeExceptionally(closedError())));

		return result;
	}

	/**
	 * Takes this peer's own offer back and answers the given one instead, as
	 * a WHEP player does when the server counters its offer. Unlike {@link
	 * Negotiator#answer}, which fails while an offer of this peer is out.
	 * <p>
	 * The answer receives only the kinds of media the peer was built to
	 * receive: the audio or video m-lines of other kinds are rejected, with
	 * port 0. An offer without any m-line of those kinds fails.
	 *
	 * @param offer     the offer that replaces this peer's own.
	 * @param gathering when the answer is handed over.
	 *
	 * @return a future with the answer, applied as the local description.
	 */
	CompletableFuture<RTCSessionDescription> answerInstead(RTCSessionDescription offer, Gathering gathering) {
		Set<String> rejected;

		try {
			rejected = midsNotReceived(offer.sdp);
		}
		catch (NegotiationException e) {
			return toPublic(CompletableFuture.failedFuture(e));
		}

		return negotiator.run(() -> {
			// Before the offer is applied, which reports the tracks of the
			// m-lines that are then rejected.
			for (MediaKind kind : MediaKind.values()) {
				if (!options.receive.contains(kind)) {
					declinedKinds.add(kind);
				}
			}

			// An offer created but not applied yet needs no taking back.
			CompletableFuture<Void> stable = connection.getSignalingState() == RTCSignalingState.STABLE
					? CompletableFuture.completedFuture(null)
					: steps.setLocal(new RTCSessionDescription(RTCSdpType.ROLLBACK, ""));

			return stable
					.thenCompose(ignored -> steps.setRemote(offer))
					.thenCompose(ignored -> {
						stopTransceivers(rejected);
						// The offer made transceivers of its own.
						applyCodecs();
						steps.releaseHeldCandidates();
						return steps.createAnswer();
					})
					.thenCompose(steps::setLocal)
					.thenCompose(ignored -> negotiator.gathered(gathering));
		});
	}

	/**
	 * Finds the audio and video m-lines of an offer whose kind this peer does
	 * not receive.
	 *
	 * @return their mids.
	 *
	 * @throws NegotiationException if the offer has no open m-line of a kind
	 *                              this peer receives.
	 */
	private Set<String> midsNotReceived(String sdp) {
		Set<String> rejected = new HashSet<>();
		boolean received = false;
		MediaKind kind = null;

		for (String line : sdp.split("\r?\n")) {
			if (line.startsWith("m=")) {
				String[] fields = line.substring(2).split(" ");
				boolean open = fields.length > 1 && !fields[1].equals("0");

				kind = !open ? null
						: fields[0].equals("audio") ? MediaKind.AUDIO
						: fields[0].equals("video") ? MediaKind.VIDEO
						: null;
				received |= kind != null && options.receive.contains(kind);
			}
			else if (line.startsWith("a=mid:") && kind != null && !options.receive.contains(kind)) {
				rejected.add(line.substring("a=mid:".length()).trim());
			}
		}

		if (!received) {
			throw new NegotiationException("The offer of the remote peer of " + name
					+ " holds none of the media it receives, " + options.receive);
		}

		return rejected;
	}

	/**
	 * Stops the transceivers of the given mids, whose m-lines the next
	 * answer then rejects.
	 */
	private void stopTransceivers(Set<String> mids) {
		if (mids.isEmpty()) {
			return;
		}

		for (RTCRtpTransceiver transceiver : connection.getTransceivers()) {
			try {
				if (mids.contains(transceiver.getMid()) && !transceiver.stopped()) {
					// At once: an answer rejects only the m-lines of stopped
					// transceivers, and keeps those still stopping, inactive.
					transceiver.stopImmediately();
				}
			}
			finally {
				transceiver.dispose();
			}
		}
	}

	@Override
	public CompletableFuture<PeerStats> stats() {
		CompletableFuture<PeerStats> result = new CompletableFuture<>();

		control.execute(() -> {
			if (closed) {
				result.completeExceptionally(closedError());
				return;
			}

			try {
				connection.getStats(report -> control.execute(() -> {
					try {
						result.complete(statsReader.read(report));
					}
					catch (Throwable e) {
						result.completeExceptionally(e);
					}
				}));
			}
			catch (Throwable e) {
				result.completeExceptionally(asWebRtcException(e, "Reading the statistics failed"));
			}
		});

		return toPublic(result);
	}

	@Override
	public Subscription onStats(Duration interval, Consumer<PeerStats> handler) {
		requireNonNull(interval, "interval");
		requireNonNull(handler, "handler");

		long millis = interval.toMillis();

		if (millis <= 0) {
			throw new IllegalArgumentException("The interval must be positive, got " + interval);
		}

		AtomicBoolean active = new AtomicBoolean(true);
		AtomicReference<ScheduledFuture<?>> task = new AtomicReference<>();

		Runnable sample = () -> {
			if (!active.get()) {
				return;
			}
			if (closing.get()) {
				active.set(false);

				ScheduledFuture<?> scheduled = task.get();

				if (scheduled != null) {
					scheduled.cancel(false);
				}
				return;
			}

			stats().whenComplete((stats, error) -> {
				if (!active.get()) {
					return;
				}
				if (error == null) {
					call(handler, stats);
				}
				else if (!(unwrap(error) instanceof PeerClosedException)) {
					deliverError(asWebRtcException(error, "Reading the statistics failed"));
				}
			});
		};

		task.set(rtc.timer.scheduleAtFixedRate(sample, 0, millis, TimeUnit.MILLISECONDS));

		return () -> {
			active.set(false);
			task.get().cancel(false);
		};
	}

	@Override
	public void restartIce() {
		control.execute(() -> {
			if (!closed) {
				connection.restartIce();
			}
		});
	}

	@Override
	public <T> CompletableFuture<T> withNative(Function<RTCPeerConnection, T> action) {
		requireNonNull(action, "action");

		CompletableFuture<T> result = new CompletableFuture<>();

		control.execute(() -> chain.enqueue(() -> CompletableFuture.completedFuture(action.apply(connection)))
				.whenComplete((value, error) -> complete(result, value, error)));

		return toPublic(result);
	}

	@Override
	public CompletableFuture<Void> closeAsync() {
		closeInternal();

		return ClientFuture.copyOf(closedFuture);
	}

	@Override
	public void close() {
		closeInternal();
	}

	@Override
	public String toString() {
		return "Peer[" + name + ", " + state + "]";
	}

	/**
	 * Starts closing, once.
	 *
	 * @return a future that completes on the control executor once the
	 * connection is closed and the control tasks queued before that have run,
	 * before the handlers hear of it.
	 */
	CompletableFuture<Void> closeInternal() {
		if (closing.compareAndSet(false, true)) {
			control.execute(this::teardown);
		}

		return closedInternal;
	}

	boolean isClosing() {
		return closing.get();
	}

	long channelSendLimit() {
		return options.channelSendLimit;
	}

	long channelReceiveLimit() {
		return options.channelReceiveLimit;
	}

	/**
	 * Hands a future out to the application: it completes on the event
	 * executor, so its callbacks run there like the handlers.
	 */
	<T> CompletableFuture<T> toPublic(CompletableFuture<T> internal) {
		return toPublic(internal, value -> { });
	}

	/**
	 * Hands a future out to the application, as {@link #toPublic(CompletableFuture)}
	 * does, and frees a result that comes too late for it: the caller may have
	 * cancelled or completed the future meanwhile, and then no one else would.
	 *
	 * @param unclaimed frees a result the future did not take.
	 */
	<T> CompletableFuture<T> toPublic(CompletableFuture<T> internal, Consumer<? super T> unclaimed) {
		ClientFuture<T> result = new ClientFuture<>();

		rtc.track(result, events);

		internal.whenComplete((value, error) -> {
			Runnable deliver = () -> {
				if (!complete(result, value, error) && error == null && value != null) {
					unclaimed.accept(value);
				}
			};

			// Completed in any case: should the event executor reject the
			// task, on the thread that found it rejected.
			events.execute(deliver, deliver);
		});

		return result;
	}

	/**
	 * Hands a native extension a source of encoded frames, taken on the
	 * control executor. The future fails once the runtime has stopped the
	 * threads that would take it, and a source that comes too late for the
	 * future, which the caller cancelled meanwhile, is closed rather than
	 * left with its reference.
	 *
	 * @param take takes the source, or throws why it cannot.
	 */
	CompletableFuture<EncodedFrames.Source> encodedFrames(Supplier<EncodedFrames.Source> take) {
		CompletableFuture<EncodedFrames.Source> result = new CompletableFuture<>();

		rtc.track(result, null);

		control.execute(() -> {
			if (result.isDone()) {
				// The caller stopped waiting before this ran.
				return;
			}

			try {
				EncodedFrames.Source source = take.get();

				if (!result.complete(source)) {
					source.close();
				}
			}
			catch (Throwable e) {
				result.completeExceptionally(e);
			}
		}, () -> {
			// A runtime that stopped its threads dropped the task.
			result.completeExceptionally(closedError());
		});

		return result;
	}

	/**
	 * Wraps a transform of the application, so that a frame it throws on is
	 * dropped rather than sent on, and only the first failure reaches the
	 * error handlers of the peer, not every one the uncaught exception
	 * handler of the transform's thread.
	 *
	 * @param owner names the sender or receiver in the error.
	 *
	 * @return the wrapped transform, or null for none.
	 */
	RTCEncodedFrameTransformer guard(RTCEncodedFrameTransformer transform, String owner) {
		if (transform == null) {
			return null;
		}

		AtomicBoolean reported = new AtomicBoolean();

		return frame -> {
			try {
				transform.transform(frame);
			}
			catch (Throwable e) {
				frame.drop();

				if (reported.compareAndSet(false, true)) {
					deliverError(new WebRtcException("The transform of " + owner
							+ " failed, and drops each frame it fails on", e));
				}
			}
		};
	}

	void deliverError(WebRtcException error) {
		events.execute(() -> {
			if (errorHandlers.isEmpty()) {
				rtc.reportError(error);
			}
			else {
				errorHandlers.accept(error);
			}
		});
	}

	/**
	 * Registers a handler on the event executor, where it sees exactly the
	 * events the other handlers saw. A handler that registers another does so
	 * at once, so that the new one also sees the events already queued, such
	 * as the messages of a channel it was just handed.
	 */
	Subscription subscribe(Supplier<Subscription> register) {
		if (events.inExecutor()) {
			return register.get();
		}

		DeferredSubscription subscription = new DeferredSubscription();

		events.execute(() -> subscription.set(register.get()));

		return subscription;
	}

	<T> void call(Consumer<? super T> handler, T value) {
		try {
			handler.accept(value);
		}
		catch (Throwable e) {
			rtc.reportError(e);
		}
	}

	PeerClosedException closedError() {
		return new PeerClosedException("The peer " + name + " is closed");
	}

	void forgetSender(SenderImpl sender) {
		senders.remove(sender);
	}

	void forgetChannel(DataChannelImpl channel) {
		channels.remove(channel);
	}

	boolean isClosed() {
		return closed;
	}

	private boolean ignoresOffer() {
		return automatic.ignoresOffer();
	}

	static Throwable unwrap(Throwable error) {
		while ((error instanceof CompletionException || error instanceof ExecutionException)
				&& error.getCause() != null) {
			error = error.getCause();
		}

		return error;
	}

	static WebRtcException asWebRtcException(Throwable error, String message) {
		error = unwrap(error);

		if (error instanceof WebRtcException) {
			return (WebRtcException) error;
		}

		return new WebRtcException(message + ": " + error, error);
	}

	/**
	 * @return whether this completed the future, which was not done before.
	 */
	static <T> boolean complete(CompletableFuture<T> future, T value, Throwable error) {
		if (error != null) {
			return future.completeExceptionally(unwrap(error));
		}

		return future.complete(value);
	}

	private LocalTrackImpl checkTrack(LocalTrack track) {
		requireNonNull(track, "track");

		if (!(track instanceof LocalTrackImpl) || ((LocalTrackImpl) track).rtc != rtc) {
			throw new IllegalArgumentException("The track was not created by the runtime of " + name);
		}

		return (LocalTrackImpl) track;
	}

	/**
	 * @return the connection, or null before it is created. Only on the
	 * control executor.
	 */
	RTCPeerConnection connection() {
		return connection;
	}

	/**
	 * Sends the local description through the signaling. On the control
	 * executor.
	 */
	void sendLocalDescription() {
		send(SignalMessage.of(connection.getLocalDescription()));
	}

	private void send(SignalMessage message) {
		if (signaling == null) {
			return;
		}

		events.execute(() -> {
			if (signalingSubscription.get() == DETACHED) {
				// The transport may take another peer by now.
				return;
			}

			try {
				signaling.send(message);
			}
			catch (Throwable e) {
				deliverError(new WebRtcException("The signaling failed to send " + message.type(), e));
			}
		});
	}

	// What WebRTC reports, on the control executor.

	private void connectionChanged(RTCPeerConnectionState nativeState) {
		if (closed) {
			return;
		}

		PeerState newState = PeerState.of(nativeState);
		state = newState;

		events.execute(() -> deliverState(newState), () -> settleConnected(newState));
	}

	private void gatheringChanged(RTCIceGatheringState gatheringState) {
		if (gatheringState == RTCIceGatheringState.COMPLETE) {
			steps.gatheringComplete();

			Runnable listener = gatheredListener;

			if (listener != null && !closed) {
				listener.run();
			}
		}
	}

	private void trackAdded(RTCRtpReceiver receiver, MediaStream[] streams) {
		List<String> streamIds = new ArrayList<>();

		for (MediaStream stream : streams) {
			streamIds.add(stream.id());
			stream.dispose();
		}

		if (closed) {
			receiver.dispose();
			return;
		}

		MediaStreamTrack track = receiver.getTrack();
		String id = track.getId();

		if (declinedKinds.contains(track instanceof VideoTrack ? MediaKind.VIDEO : MediaKind.AUDIO)) {
			// Of an m-line this peer rejected, when it answered an offer that
			// replaced its own.
			track.dispose();
			receiver.dispose();
			return;
		}
		if (remoteTrackById.containsKey(id)) {
			// Renegotiation reported a track that is already known.
			track.dispose();
			receiver.dispose();
			return;
		}

		if (options.receiveTransform != null) {
			String owner = "the track " + id + " of " + name;

			try {
				// Before the application hears of the track, and so before
				// its first frame arrives.
				receiver.setTransform(guard(options.receiveTransform, owner));
			}
			catch (Throwable e) {
				deliverError(asWebRtcException(e, "Setting the receive transform of " + owner + " failed"));
			}
		}

		RemoteTrackImpl remote = track instanceof VideoTrack
				? new RemoteVideoImpl(this, receiver, (VideoTrack) track, id, streamIds)
				: new RemoteAudioImpl(this, receiver, (AudioTrack) track, id, streamIds);

		remoteTrackById.put(id, remote);
		remoteTracks.add(remote);

		events.execute(() -> {
			deliveredTracks.add(remote);
			trackHandlers.accept(remote);
		});
	}

	private void trackRemoved(RTCRtpReceiver receiver) {
		MediaStreamTrack track = receiver.getTrack();
		String id = track.getId();

		track.dispose();
		receiver.dispose();

		RemoteTrackImpl remote = remoteTrackById.remove(id);

		if (remote == null || closed) {
			return;
		}

		remoteTracks.remove(remote);
		remote.end();

		events.execute(() -> {
			deliveredTracks.remove(remote);
			remote.fireEnded();
		});
	}

	private void channelOpened(DataChannelImpl channel) {
		if (closed) {
			channel.closeFromPeer(closedError());
			return;
		}

		channels.add(channel);

		events.execute(() -> {
			deliveredChannels.add(channel);
			channelHandlers.accept(channel);
		});
	}

	void channelClosed(DataChannelImpl channel) {
		channels.remove(channel);

		events.execute(() -> deliveredChannels.remove(channel));
	}

	private void teardown() {
		if (teardownStarted) {
			return;
		}
		teardownStarted = true;
		closing.set(true);
		closed = true;

		PeerClosedException error = closedError();

		chain.close(error);
		automatic.close();
		steps.close(error);

		for (DataChannelImpl channel : new ArrayList<>(channels)) {
			channel.closeFromPeer(error);
		}
		channels.clear();

		for (SenderImpl sender : senders) {
			sender.closeFromPeer();
		}
		senders.clear();

		List<RemoteTrackImpl> ended = new ArrayList<>(remoteTracks);

		for (RemoteTrackImpl remote : ended) {
			remote.end();
		}
		remoteTracks.clear();
		remoteTrackById.clear();

		try {
			if (connection != null) {
				connection.close();
			}
		}
		catch (Throwable e) {
			rtc.reportError(e);
		}

		state = PeerState.CLOSED;
		rtc.removePeer(this);

		// Finished only once the tasks queued before the connection closed
		// have run: a callback of WebRTC may have queued one behind this
		// teardown, such as a channel the remote side opened, and that task
		// still calls into its native objects. The runtime frees the factory,
		// and with it WebRTC's threads, as soon as closedInternal completes.
		// Nothing is queued for the connection once it has closed.
		Runnable finish = () -> finishTeardown(ended, error);

		control.execute(finish, finish);
	}

	private void finishTeardown(List<RemoteTrackImpl> ended, PeerClosedException error) {
		closedInternal.complete(null);

		events.execute(() -> {
			// Behind the messages queued for sending, so that the transport
			// hears of none once it was let go of.
			detachSignaling();

			for (RemoteTrackImpl remote : ended) {
				remote.fireEnded();
			}
			deliveredTracks.clear();
			deliveredChannels.clear();

			deliverState(PeerState.CLOSED);

			connected.completeExceptionally(error);
			closedFuture.complete(null);

			trackHandlers.clear();
			channelHandlers.clear();
			stateHandlers.clear();
			errorHandlers.clear();
		}, () -> {
			detachSignaling();

			connected.completeExceptionally(error);
			closedFuture.complete(null);
		});
	}

	/**
	 * Lets go of the signaling, once: a transport that outlives the peer must
	 * not keep it, and the runtime with it, reachable.
	 */
	private void detachSignaling() {
		Subscription subscription = signalingSubscription.getAndSet(DETACHED);

		if (subscription != null && subscription != DETACHED) {
			detach(subscription);
		}
	}

	private void detach(Subscription subscription) {
		try {
			subscription.close();
		}
		catch (Throwable e) {
			rtc.reportError(new WebRtcException("Detaching " + name + " from its signaling failed", e));
		}
	}

	// On the event executor.

	private void deliverState(PeerState newState) {
		if (newState == deliveredState || deliveredState == PeerState.CLOSED) {
			return;
		}

		deliveredState = newState;
		stateHandlers.accept(newState);
		settleConnected(newState);
	}

	/**
	 * Completes the connected future for the state, which is all that is left
	 * of delivering it when the event executor rejects that.
	 */
	private void settleConnected(PeerState newState) {
		if (newState == PeerState.CONNECTED) {
			connected.complete(this);
		}
		else if (newState == PeerState.FAILED) {
			connected.completeExceptionally(new WebRtcException("The connection of " + name + " failed"));
		}
	}



	/**
	 * Hears what WebRTC reports, on its signaling thread, and only posts it to
	 * the control executor.
	 */
	private final class Observer implements PeerConnectionObserver {

		@Override
		public void onIceCandidate(RTCIceCandidate candidate) {
			control.execute(() -> {
				if (!closed) {
					Consumer<RTCIceCandidate> listener = candidateListener;

					if (listener != null) {
						listener.accept(candidate);
					}

					send(SignalMessage.of(candidate));
				}
			});
		}

		@Override
		public void onConnectionChange(RTCPeerConnectionState state) {
			control.execute(() -> connectionChanged(state));
		}

		@Override
		public void onIceGatheringChange(RTCIceGatheringState state) {
			control.execute(() -> gatheringChanged(state));
		}

		@Override
		public void onRenegotiationNeeded() {
			control.execute(automatic::negotiationNeeded);
		}

		@Override
		public void onTrack(RTCRtpTransceiver transceiver) {
			// The tracks are taken from onAddTrack, which names their streams.
			control.execute(transceiver::dispose);
		}

		@Override
		public void onAddTrack(RTCRtpReceiver receiver, MediaStream[] mediaStreams) {
			control.execute(() -> trackAdded(receiver, mediaStreams));
		}

		@Override
		public void onRemoveTrack(RTCRtpReceiver receiver) {
			control.execute(() -> trackRemoved(receiver));
		}

		@Override
		public void onDataChannel(RTCDataChannel dataChannel) {
			// Observed at once, so that no message is missed; the observer
			// itself only posts as well.
			DataChannelImpl channel = new DataChannelImpl(PeerImpl.this, dataChannel);

			control.execute(() -> channelOpened(channel));
		}
	}



	private final class NegotiatorImpl implements Negotiator {

		@Override
		public CompletableFuture<RTCSessionDescription> createOffer() {
			return createOffer(Gathering.TRICKLE);
		}

		@Override
		public CompletableFuture<RTCSessionDescription> createOffer(Gathering gathering) {
			requireNonNull(gathering, "gathering");

			return run(() -> steps.createOffer()
					.thenCompose(steps::setLocal)
					.thenCompose(ignored -> gathered(gathering)));
		}

		@Override
		public CompletableFuture<RTCSessionDescription> answer(RTCSessionDescription offer) {
			return answer(offer, Gathering.TRICKLE);
		}

		@Override
		public CompletableFuture<RTCSessionDescription> answer(RTCSessionDescription offer, Gathering gathering) {
			requireNonNull(offer, "offer");
			requireNonNull(gathering, "gathering");

			if (offer.sdpType != RTCSdpType.OFFER) {
				throw new IllegalArgumentException("Expected an offer, got " + offer.sdpType);
			}

			return run(() -> steps.setRemote(offer)
					.thenCompose(ignored -> {
						steps.releaseHeldCandidates();
						return steps.createAnswer();
					})
					.thenCompose(steps::setLocal)
					.thenCompose(ignored -> gathered(gathering)));
		}

		@Override
		public CompletableFuture<Void> applyAnswer(RTCSessionDescription answer) {
			requireNonNull(answer, "answer");

			if (answer.sdpType != RTCSdpType.ANSWER && answer.sdpType != RTCSdpType.PR_ANSWER) {
				throw new IllegalArgumentException("Expected an answer, got " + answer.sdpType);
			}

			return run(() -> steps.setRemote(answer).thenRun(steps::releaseHeldCandidates));
		}

		@Override
		public CompletableFuture<Void> addCandidate(RTCIceCandidate candidate) {
			requireNonNull(candidate, "candidate");

			return run(() -> steps.addRemoteCandidate(candidate));
		}

		private CompletableFuture<RTCSessionDescription> gathered(Gathering gathering) {
			CompletableFuture<Void> ready = gathering == Gathering.COMPLETE
					? steps.waitForGathering()
					: CompletableFuture.completedFuture(null);

			return ready.thenApply(ignored -> connection.getLocalDescription());
		}

		private <T> CompletableFuture<T> run(Supplier<CompletableFuture<T>> operation) {
			return run(operation, (value, error) -> { });
		}

		private <T> CompletableFuture<T> run(Supplier<CompletableFuture<T>> operation,
				BiConsumer<T, Throwable> finished) {
			CompletableFuture<T> result = new CompletableFuture<>();

			control.execute(() -> chain.enqueue(operation, finished)
					.whenComplete((value, error) -> complete(result, value, error)));

			return toPublic(result);
		}
	}



	/**
	 * What the operations chain needs of this peer, on the control executor.
	 */
	private final class ChainHost implements OperationChain.Host {

		@Override
		public boolean isClosed() {
			return closed;
		}

		@Override
		public PeerClosedException closedError() {
			return PeerImpl.this.closedError();
		}

		@Override
		public CompletableFuture<Void> rollback() {
			return steps.rollback();
		}

		@Override
		public void abandon() {
			teardown();
		}
	}
}
