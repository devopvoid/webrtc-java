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
import dev.onvoid.webrtc.client.RemoteTrack;
import dev.onvoid.webrtc.client.Sender;
import dev.onvoid.webrtc.client.Simulcast;
import dev.onvoid.webrtc.client.SignalMessage;
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
import dev.onvoid.webrtc.livekit.internal.proto.LivekitRtc.SubscribedCodec;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitRtc.SubscribedQuality;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitRtc.SubscribedQualityUpdate;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitRtc.TrackPublishedResponse;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitRtc.TrickleRequest;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitRtc.UpdateTrackSettings;

/**
 * The room: the state LiveKit's signaling describes, and the publisher and
 * subscriber peers it negotiates. Everything but the handler lists is
 * confined to the room's single thread, which also runs the handlers.
 */
final class RoomImpl implements Room, SignalClient.Handler {

	private static final System.Logger LOG = System.getLogger(Room.class.getName());

	private static final String RELIABLE_LABEL = "_reliable";

	private static final String LOSSY_LABEL = "_lossy";

	private final WebRtc rtc;

	private final Duration timeout;

	private final ScheduledExecutorService thread;

	/** Runs on the room's thread, and drops what comes after the room is gone. */
	private final Executor executor;

	private final SignalClient signal;

	private final CompletableFuture<Room> joined = new CompletableFuture<>();

	private final CompletableFuture<Void> closed = new CompletableFuture<>();

	private final List<Consumer<Participant>> joinedHandlers = new CopyOnWriteArrayList<>();

	private final List<Consumer<Participant>> leftHandlers = new CopyOnWriteArrayList<>();

	private final List<BiConsumer<Participant, RemoteTrack>> trackHandlers = new CopyOnWriteArrayList<>();

	private final List<Consumer<DataMessage>> dataHandlers = new CopyOnWriteArrayList<>();

	private final List<Consumer<String>> disconnectHandlers = new CopyOnWriteArrayList<>();

	// Room thread only, unless volatile.

	private volatile RoomState state = RoomState.CONNECTING;

	private volatile String name = "";

	private volatile Participant local;

	/** By sid, in join order. Written on the room thread, read anywhere. */
	private volatile Map<String, Participant> participants = Map.of();

	/** Tracks that arrived before the participant that publishes them, by participant sid. */
	private final Map<String, List<RemoteTrack>> orphanTracks = new HashMap<>();

	private final Map<String, PendingPublish> pendingPublishes = new HashMap<>();

	/** The published tracks, by sid. */
	private final Map<String, Publication> publications = new HashMap<>();

	/** Hear which layers dynacast left on, once applied. For tests. */
	final List<BiConsumer<Publication, Map<VideoQuality, Boolean>>> layerListeners = new CopyOnWriteArrayList<>();

	private Peer publisher;

	private Peer subscriber;

	private DataChannel reliable;

	private DataChannel lossy;

	private boolean negotiating;

	private boolean renegotiate;

	private boolean publisherNegotiated;

	/** Wait for the offer that is out. */
	private List<CompletableFuture<Void>> negotiationWaiters = new ArrayList<>();

	/** Wait for the offer after the one that is out. */
	private List<CompletableFuture<Void>> nextNegotiationWaiters = new ArrayList<>();

	private int offerId;

	private int dataSequence = 1;

	private ScheduledFuture<?> pingTask;

	private long lastPongMillis;


	private RoomImpl(WebRtc rtc, Duration timeout) {
		this.rtc = rtc;
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
		this.signal = new SignalClient(executor, this);
	}

	static CompletableFuture<Room> connect(WebRtc rtc, URI url, String token, boolean autoSubscribe,
			Duration timeout) {
		URI uri = SignalClient.rtcUri(url, LiveKit.PROTOCOL, autoSubscribe);
		RoomImpl room = new RoomImpl(rtc, timeout);

		room.executor.execute(() -> {
			ScheduledFuture<?> deadline = room.thread.schedule(
					() -> room.failJoin(new LiveKitException("No answer to the join within " + timeout)),
					timeout.toMillis(), TimeUnit.MILLISECONDS);
			room.joined.whenComplete((r, e) -> deadline.cancel(false));

			room.signal.open(HttpClient.newHttpClient(), uri, token, timeout)
					.whenCompleteAsync((v, e) -> {
						if (e != null) {
							room.failJoin(e instanceof CompletionException && e.getCause() != null
									? e.getCause()
									: e);
						}
					}, room.executor);
		});

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

		post(() -> startPublish(track, options, result), result);

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

		post(() -> sendData(data, topic, reliable, result), result);

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
		return subscribe(trackHandlers, handler);
	}

	@Override
	public Subscription onData(Consumer<DataMessage> handler) {
		return subscribe(dataHandlers, handler);
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

	// Signaling, on the room thread.

	@Override
	public void onSignal(SignalResponse response) {
		if (state == RoomState.DISCONNECTED) {
			return;
		}

		switch (response.getMessageCase()) {
			case JOIN:
				onJoin(response.getJoin());
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
				LeaveRequest leave = response.getLeave();
				disconnect("The server ended the session (" + leave.getReason() + ", " + leave.getAction() + ")",
						false);
				break;
			case PONG_RESP:
				lastPongMillis = System.currentTimeMillis();
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

	@Override
	public void onSignalClosed(String reason) {
		if (!joined.isDone()) {
			failJoin(new LiveKitException(reason));
		}
		else {
			disconnect(reason, false);
		}
	}

	private void onJoin(JoinResponse join) {
		if (joined.isDone()) {
			return;
		}

		name = join.getRoom().getName();
		local = new Participant(join.getParticipant());

		Map<String, Participant> others = new LinkedHashMap<>();
		for (ParticipantInfo info : join.getOtherParticipantsList()) {
			others.put(info.getSid(), new Participant(info));
		}
		participants = others;

		try {
			publisher = createPeer(SignalTarget.PUBLISHER, join.getIceServersList());
			subscriber = createPeer(SignalTarget.SUBSCRIBER, join.getIceServersList());

			// The channels go into the first offer of the publisher.
			reliable = publisher.createChannel(RELIABLE_LABEL, ChannelOptions.builder().ordered(true).build());
			lossy = publisher.createChannel(LOSSY_LABEL,
					ChannelOptions.builder().ordered(false).maxRetransmits(0).build());
		}
		catch (RuntimeException e) {
			failJoin(e);
			return;
		}

		receiveData(reliable);
		receiveData(lossy);

		subscriber.onTrack(track -> executor.execute(() -> onRemoteTrack(track)));
		subscriber.onChannel(channel -> executor.execute(() -> receiveData(channel)));

		startPing(join.getPingInterval(), join.getPingTimeout());

		state = RoomState.CONNECTED;

		if (!join.getSubscriberPrimary() || join.getFastPublish()) {
			negotiatePublisher();
		}

		joined.complete(this);
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
			for (String url : server.getUrlsList()) {
				if (server.getUsername().isEmpty()) {
					options.iceServer(url);
				}
				else {
					options.iceServer(url, server.getUsername(), server.getCredential());
				}
			}
		}

		Peer peer = rtc.createPeer(options.build());
		peer.onError(e -> LOG.log(Level.WARNING, "The " + target + " peer failed", e));

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

		publisher.negotiator().createOffer().whenCompleteAsync((offer, error) -> {
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

		publisher.negotiator().applyAnswer(new RTCSessionDescription(RTCSdpType.ANSWER, answer.getSdp()))
				.whenCompleteAsync((v, error) -> negotiationDone(error), executor);
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

	// The server offers, the subscriber answers.

	private void onOffer(SessionDescription offer) {
		if (subscriber == null) {
			return;
		}

		subscriber.negotiator().answer(new RTCSessionDescription(RTCSdpType.OFFER, offer.getSdp()))
				.whenCompleteAsync((answer, error) -> {
					if (error != null) {
						LOG.log(Level.WARNING, "Answering the server's offer failed", error);
						return;
					}
					if (state == RoomState.DISCONNECTED) {
						return;
					}

					signal.send(SignalRequest.newBuilder()
							.setAnswer(SessionDescription.newBuilder()
									.setType("answer")
									.setSdp(answer.sdp)
									.setId(offer.getId()))
							.build());
				}, executor);
	}

	// Publishing.

	private void startPublish(LocalTrack track, PublishOptions options, CompletableFuture<Publication> result) {
		if (state != RoomState.CONNECTED) {
			result.completeExceptionally(new LiveKitException("The room is " + state));
			return;
		}

		// The server knows the track by this id until it names its own.
		String cid = track.id();
		if (pendingPublishes.containsKey(cid)) {
			result.completeExceptionally(new LiveKitException("The track " + cid + " is being published"));
			return;
		}

		pendingPublishes.put(cid, new PendingPublish(track, options, result));
		result.whenCompleteAsync((p, e) -> pendingPublishes.remove(cid), executor);

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

		Publication publication = new Publication(published.getTrack().getSid(), pending.options.source,
				pending.track, sender);

		negotiatePublisher().whenComplete((v, error) -> {
			if (error != null) {
				pending.result.completeExceptionally(error);
			}
			else {
				publications.put(publication.sid(), publication);
				pending.result.complete(publication);
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
		if (state != RoomState.CONNECTED) {
			result.completeExceptionally(new LiveKitException("The room is " + state));
			return;
		}

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
			for (Consumer<Participant> handler : leftHandlers) {
				call(() -> handler.accept(participant));
			}
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
		for (BiConsumer<Participant, RemoteTrack> handler : trackHandlers) {
			call(() -> handler.accept(participant, track));
		}
	}

	// Keepalive.

	private void startPing(int intervalSeconds, int timeoutSeconds) {
		if (intervalSeconds <= 0) {
			return;
		}

		long timeoutMillis = TimeUnit.SECONDS.toMillis(timeoutSeconds);
		lastPongMillis = System.currentTimeMillis();

		pingTask = thread.scheduleAtFixedRate(() -> {
			long now = System.currentTimeMillis();

			if (timeoutMillis > 0 && now - lastPongMillis > timeoutMillis) {
				disconnect("The server did not answer pings for " + timeoutSeconds + " s", false);
				return;
			}

			signal.send(SignalRequest.newBuilder()
					.setPingReq(Ping.newBuilder().setTimestamp(now))
					.build());
		}, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
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
		state = RoomState.DISCONNECTED;

		if (pingTask != null) {
			pingTask.cancel(false);
		}
		if (sendLeave) {
			signal.send(SignalRequest.newBuilder()
					.setLeave(LeaveRequest.newBuilder()
							.setReason(DisconnectReason.CLIENT_INITIATED)
							.setAction(LeaveRequest.Action.DISCONNECT))
					.build());
		}
		signal.close();

		LiveKitException error = new LiveKitException("The room was left: " + reason);
		joined.completeExceptionally(error);

		List<PendingPublish> publishes = new ArrayList<>(pendingPublishes.values());
		pendingPublishes.clear();
		publishes.forEach(pending -> pending.result.completeExceptionally(error));

		List<CompletableFuture<Void>> waiters = new ArrayList<>(negotiationWaiters);
		waiters.addAll(nextNegotiationWaiters);
		negotiationWaiters.clear();
		nextNegotiationWaiters.clear();
		waiters.forEach(waiter -> waiter.completeExceptionally(error));

		orphanTracks.clear();
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

	// Helpers.

	private void post(Runnable task, CompletableFuture<?> future) {
		try {
			thread.execute(task);
		}
		catch (RejectedExecutionException e) {
			future.completeExceptionally(new LiveKitException("The room is closed"));
		}
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

		final CompletableFuture<Publication> result;


		PendingPublish(LocalTrack track, PublishOptions options, CompletableFuture<Publication> result) {
			this.track = track;
			this.options = options;
			this.result = result;
		}
	}

}
