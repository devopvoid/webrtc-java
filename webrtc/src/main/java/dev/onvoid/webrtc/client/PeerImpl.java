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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import dev.onvoid.webrtc.AddIceCandidateObserver;
import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceGatheringState;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCRtpTransceiverDirection;
import dev.onvoid.webrtc.RTCRtpTransceiverInit;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.RTCSignalingState;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
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
 * Changes that the standard runs on its operations chain go through {@link
 * #enqueue}, which starts one only once the one before finished.
 */
final class PeerImpl implements Peer {

	static {
		// Senders and remote tracks come from peers, so whoever holds one
		// finds the access to their encoded frames registered.
		EncodedFrames.register(new EncodedFramesAccess());
	}

	/** How long an impolite peer waits for an answer after it ignored an offer. */
	private static final long COLLISION_TIMEOUT_MS = 5000;

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

	private final ArrayDeque<Runnable> operations = new ArrayDeque<>();

	private boolean operationRunning;

	private CompletableFuture<?> runningOperation;

	private boolean negotiationQueued;

	private boolean ignoreOffer;

	private int remoteAnswers;

	private ScheduledFuture<?> collisionCheck;

	private final List<HeldCandidate> heldCandidates = new ArrayList<>();

	private final List<CompletableFuture<Void>> gatheringWaiters = new ArrayList<>();

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


	PeerImpl(WebRtc rtc, PeerOptions options, String name) {
		this.rtc = rtc;
		this.options = options;
		this.name = name;
		this.signaling = options.signaling;
		this.control = new SerialExecutor(rtc.controlPool, rtc::reportError, false, null);
		this.events = new SerialExecutor(rtc.eventExecutor, rtc::reportError, true,
				rtc.ownsEventExecutor ? "webrtc-client-events-" + name : null);
		this.stateHandlers = new Handlers<>(rtc::reportError);
		this.trackHandlers = new Handlers<>(rtc::reportError);
		this.channelHandlers = new Handlers<>(rtc::reportError);
		this.errorHandlers = new Handlers<>(rtc::reportError);

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

		if (signaling != null) {
			signaling.attach(this::signal);
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

		control.execute(() -> handleSignal(message));
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

		SenderImpl sender = new SenderImpl(this, local);
		senders.add(sender);

		control.execute(() -> {
			if (closed) {
				senders.remove(sender);
				sender.failAttach(closedError());
				return;
			}

			try {
				sender.attach(connection, ids);
			}
			catch (Throwable e) {
				senders.remove(sender);
				sender.failAttach(e);
				deliverError(asWebRtcException(e, "Adding the track failed"));
			}
		});

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
		return connected;
	}

	@Override
	public CompletableFuture<Void> whenClosed() {
		return closedFuture;
	}

	@Override
	public Negotiator negotiator() {
		if (options.negotiation != Negotiation.MANUAL) {
			throw new IllegalStateException("The peer " + name + " negotiates automatically. "
					+ "Build it with Negotiation.MANUAL to drive the exchange by hand");
		}

		return negotiator;
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
					handler.accept(stats);
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

		control.execute(() -> enqueue(() -> CompletableFuture.completedFuture(action.apply(connection)))
				.whenComplete((value, error) -> complete(result, value, error)));

		return toPublic(result);
	}

	@Override
	public CompletableFuture<Void> closeAsync() {
		closeInternal();

		return closedFuture;
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
	 * connection is closed, before the handlers hear of it.
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

	/**
	 * Hands a future out to the application: it completes on the event
	 * executor, so its callbacks run there like the handlers.
	 */
	<T> CompletableFuture<T> toPublic(CompletableFuture<T> internal) {
		ClientFuture<T> result = new ClientFuture<>();

		rtc.track(result, events);

		// Completed in any case: should the event executor reject the task, on
		// the thread that found it rejected.
		internal.whenComplete((value, error) -> events.execute(() -> complete(result, value, error),
				() -> complete(result, value, error)));

		return result;
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
	 * events the other handlers saw.
	 */
	Subscription subscribe(Supplier<Subscription> register) {
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

	static <T> void complete(CompletableFuture<T> future, T value, Throwable error) {
		if (error != null) {
			future.completeExceptionally(unwrap(error));
		}
		else {
			future.complete(value);
		}
	}

	private LocalTrackImpl checkTrack(LocalTrack track) {
		requireNonNull(track, "track");

		if (!(track instanceof LocalTrackImpl) || ((LocalTrackImpl) track).rtc != rtc) {
			throw new IllegalArgumentException("The track was not created by the runtime of " + name);
		}

		return (LocalTrackImpl) track;
	}

	// Operations, on the control executor.

	/**
	 * Runs an operation once the ones queued before it finished. It fails
	 * with a {@link PeerClosedException} when the peer closed first, and with
	 * a {@link TimeoutException} when WebRTC never finishes it.
	 *
	 * @return a future that completes on the control executor.
	 */
	private <T> CompletableFuture<T> enqueue(Supplier<CompletableFuture<T>> operation) {
		CompletableFuture<T> result = new CompletableFuture<>();

		operations.add(() -> {
			CompletableFuture<T> running;

			if (closed) {
				running = new CompletableFuture<>();
				running.completeExceptionally(closedError());
			}
			else {
				try {
					running = operation.get();
				}
				catch (Throwable e) {
					running = new CompletableFuture<>();
					running.completeExceptionally(e);
				}
			}

			CompletableFuture<T> current = running;
			runningOperation = current;

			ScheduledFuture<?> timeout = rtc.timer.schedule(() -> control.execute(() ->
					current.completeExceptionally(new TimeoutException("WebRTC did not finish an operation of "
							+ name + " within " + rtc.operationTimeout))),
					rtc.operationTimeout.toMillis(), TimeUnit.MILLISECONDS);

			current.whenComplete((value, error) -> control.execute(() -> {
				timeout.cancel(false);

				runningOperation = null;
				operationRunning = false;

				complete(result, value, error);
				runNextOperation();
			}));
		});

		runNextOperation();

		return result;
	}

	private void runNextOperation() {
		if (operationRunning) {
			return;
		}

		Runnable next = operations.poll();

		if (next != null) {
			operationRunning = true;
			next.run();
		}
	}

	private CompletableFuture<Void> setLocal() {
		CompletableFuture<Void> future = new CompletableFuture<>();
		connection.setLocalDescription(descriptionObserver(future, "Applying the local description failed"));
		return future;
	}

	private CompletableFuture<Void> setLocal(RTCSessionDescription description) {
		CompletableFuture<Void> future = new CompletableFuture<>();
		connection.setLocalDescription(description, descriptionObserver(future, "Applying the local description failed"));
		return future;
	}

	private CompletableFuture<Void> setRemote(RTCSessionDescription description) {
		CompletableFuture<Void> future = new CompletableFuture<>();
		connection.setRemoteDescription(description, descriptionObserver(future,
				"Applying the remote " + description.sdpType.name().toLowerCase() + " failed"));
		return future;
	}

	private CompletableFuture<RTCSessionDescription> createOffer() {
		CompletableFuture<RTCSessionDescription> future = new CompletableFuture<>();
		connection.createOffer(new RTCOfferOptions(), createObserver(future, "Creating the offer failed"));
		return future;
	}

	private CompletableFuture<RTCSessionDescription> createAnswer() {
		CompletableFuture<RTCSessionDescription> future = new CompletableFuture<>();
		connection.createAnswer(new RTCAnswerOptions(), createObserver(future, "Creating the answer failed"));
		return future;
	}

	private SetSessionDescriptionObserver descriptionObserver(CompletableFuture<Void> future, String message) {
		return new SetSessionDescriptionObserver() {

			@Override
			public void onSuccess() {
				control.execute(() -> future.complete(null));
			}

			@Override
			public void onFailure(String error) {
				control.execute(() -> future.completeExceptionally(new NegotiationException(message + ": " + error)));
			}
		};
	}

	private CreateSessionDescriptionObserver createObserver(
			CompletableFuture<RTCSessionDescription> future, String message) {
		return new CreateSessionDescriptionObserver() {

			@Override
			public void onSuccess(RTCSessionDescription description) {
				control.execute(() -> future.complete(description));
			}

			@Override
			public void onFailure(String error) {
				control.execute(() -> future.completeExceptionally(new NegotiationException(message + ": " + error)));
			}
		};
	}

	/**
	 * Adds a remote candidate, or holds it while there is no remote
	 * description to add it to.
	 */
	private CompletableFuture<Void> addRemoteCandidate(RTCIceCandidate candidate) {
		CompletableFuture<Void> future = new CompletableFuture<>();

		if (connection.getRemoteDescription() == null) {
			heldCandidates.add(new HeldCandidate(candidate, future));
		}
		else {
			addCandidate(candidate, future);
		}

		return future;
	}

	private void addCandidate(RTCIceCandidate candidate, CompletableFuture<Void> future) {
		connection.addIceCandidate(candidate, new AddIceCandidateObserver() {

			@Override
			public void onSuccess() {
				control.execute(() -> future.complete(null));
			}

			@Override
			public void onFailure(String error) {
				control.execute(() -> {
					if (ignoreOffer) {
						// A candidate of an offer this peer ignored.
						future.complete(null);
					}
					else {
						future.completeExceptionally(new NegotiationException("Adding a remote candidate failed: " + error));
					}
				});
			}
		});
	}

	private void releaseHeldCandidates() {
		List<HeldCandidate> held = new ArrayList<>(heldCandidates);
		heldCandidates.clear();

		for (HeldCandidate candidate : held) {
			addCandidate(candidate.candidate, candidate.future);
		}
	}

	private CompletableFuture<Void> waitForGathering() {
		if (connection.getIceGatheringState() == RTCIceGatheringState.COMPLETE) {
			return CompletableFuture.completedFuture(null);
		}

		CompletableFuture<Void> future = new CompletableFuture<>();
		gatheringWaiters.add(future);

		// A STUN or TURN server that cannot be reached holds up the end of
		// gathering for long; the description then goes with what there is.
		rtc.timer.schedule(() -> control.execute(() -> {
			if (gatheringWaiters.remove(future)) {
				future.complete(null);
			}
		}), options.gatheringTimeout.toMillis(), TimeUnit.MILLISECONDS);

		return future;
	}

	private void sendLocalDescription() {
		send(SignalMessage.of(connection.getLocalDescription()));
	}

	private void send(SignalMessage message) {
		if (signaling == null) {
			return;
		}

		events.execute(() -> {
			try {
				signaling.send(message);
			}
			catch (Throwable e) {
				deliverError(new WebRtcException("The signaling failed to send " + message.type(), e));
			}
		});
	}

	private void reportFailure(Object value, Throwable error) {
		if (error != null && !(unwrap(error) instanceof PeerClosedException)) {
			deliverError(asWebRtcException(error, "Negotiation of " + name + " failed"));
		}
	}

	// Automatic negotiation: perfect negotiation, on the control executor.

	private void onNegotiationNeeded() {
		if (closed || options.negotiation != Negotiation.AUTOMATIC || negotiationQueued) {
			return;
		}

		negotiationQueued = true;

		enqueue(() -> {
			negotiationQueued = false;

			if (connection.getSignalingState() != RTCSignalingState.STABLE) {
				// WebRTC asks again once the exchange under way is done.
				return CompletableFuture.completedFuture(null);
			}

			return setLocal().thenRun(this::sendLocalDescription);
		}).whenComplete(this::reportFailure);
	}

	private void handleSignal(SignalMessage message) {
		if (closed) {
			return;
		}

		switch (message.type()) {
			case OFFER:
			case ANSWER:
				if (options.negotiation == Negotiation.MANUAL) {
					deliverError(new NegotiationException("The peer " + name + " negotiates by hand. "
							+ "Apply descriptions through Peer.negotiator(), not Peer.signal()"));
					return;
				}

				enqueue(() -> applyRemoteDescription(message.description()))
						.whenComplete(this::reportFailure);
				break;

			case CANDIDATE:
				enqueue(() -> ignoreOffer
						? CompletableFuture.<Void>completedFuture(null)
						: addRemoteCandidate(message.candidate()))
						.whenComplete(this::reportFailure);
				break;

			default:
				// WebRTC needs no end of candidates.
				break;
		}
	}

	private CompletableFuture<Void> applyRemoteDescription(RTCSessionDescription description) {
		boolean offer = description.sdpType == RTCSdpType.OFFER;
		boolean collision = offer && connection.getSignalingState() != RTCSignalingState.STABLE;

		ignoreOffer = !options.polite && collision;

		if (ignoreOffer) {
			// The polite peer gives way and answers this peer's offer instead.
			watchCollision();
			return CompletableFuture.completedFuture(null);
		}

		// A polite peer's own pending offer is rolled back implicitly.
		return setRemote(description).thenCompose(ignored -> {
			releaseHeldCandidates();

			if (!offer) {
				remoteAnswers++;
				return CompletableFuture.completedFuture(null);
			}

			return setLocal().thenRun(this::sendLocalDescription);
		});
	}

	/**
	 * Tells the application when the remote peer never answered the offer this
	 * peer kept, which happens when both peers are impolite.
	 */
	private void watchCollision() {
		if (collisionCheck != null) {
			return;
		}

		int answers = remoteAnswers;

		collisionCheck = rtc.timer.schedule(() -> control.execute(() -> {
			collisionCheck = null;

			if (!closed && remoteAnswers == answers
					&& connection.getSignalingState() == RTCSignalingState.HAVE_LOCAL_OFFER) {
				deliverError(new NegotiationException("Both peers sent an offer at once, and both are impolite, "
						+ "so neither gives way. Build exactly one of them with PeerOptions.Builder.polite(true)"));
			}
		}), COLLISION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
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
			List<CompletableFuture<Void>> waiters = new ArrayList<>(gatheringWaiters);
			gatheringWaiters.clear();

			waiters.forEach(waiter -> waiter.complete(null));
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

		if (remoteTrackById.containsKey(id)) {
			// Renegotiation reported a track that is already known.
			track.dispose();
			receiver.dispose();
			return;
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
		closed = true;

		PeerClosedException error = closedError();

		if (runningOperation != null) {
			runningOperation.completeExceptionally(error);
		}
		if (collisionCheck != null) {
			collisionCheck.cancel(false);
			collisionCheck = null;
		}

		for (CompletableFuture<Void> waiter : gatheringWaiters) {
			waiter.completeExceptionally(error);
		}
		gatheringWaiters.clear();

		for (HeldCandidate candidate : heldCandidates) {
			candidate.future.completeExceptionally(error);
		}
		heldCandidates.clear();

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
		closedInternal.complete(null);

		events.execute(() -> {
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
			connected.completeExceptionally(error);
			closedFuture.complete(null);
		});
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
			control.execute(PeerImpl.this::onNegotiationNeeded);
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

			return run(() -> PeerImpl.this.createOffer()
					.thenCompose(PeerImpl.this::setLocal)
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

			return run(() -> setRemote(offer)
					.thenCompose(ignored -> {
						releaseHeldCandidates();
						return PeerImpl.this.createAnswer();
					})
					.thenCompose(PeerImpl.this::setLocal)
					.thenCompose(ignored -> gathered(gathering)));
		}

		@Override
		public CompletableFuture<Void> applyAnswer(RTCSessionDescription answer) {
			requireNonNull(answer, "answer");

			if (answer.sdpType != RTCSdpType.ANSWER && answer.sdpType != RTCSdpType.PR_ANSWER) {
				throw new IllegalArgumentException("Expected an answer, got " + answer.sdpType);
			}

			return run(() -> setRemote(answer).thenRun(PeerImpl.this::releaseHeldCandidates));
		}

		@Override
		public CompletableFuture<Void> addCandidate(RTCIceCandidate candidate) {
			requireNonNull(candidate, "candidate");

			return run(() -> addRemoteCandidate(candidate));
		}

		private CompletableFuture<RTCSessionDescription> gathered(Gathering gathering) {
			CompletableFuture<Void> ready = gathering == Gathering.COMPLETE
					? waitForGathering()
					: CompletableFuture.completedFuture(null);

			return ready.thenApply(ignored -> connection.getLocalDescription());
		}

		private <T> CompletableFuture<T> run(Supplier<CompletableFuture<T>> operation) {
			CompletableFuture<T> result = new CompletableFuture<>();

			control.execute(() -> enqueue(operation)
					.whenComplete((value, error) -> complete(result, value, error)));

			return toPublic(result);
		}
	}



	private static final class HeldCandidate {

		final RTCIceCandidate candidate;

		final CompletableFuture<Void> future;


		HeldCandidate(RTCIceCandidate candidate, CompletableFuture<Void> future) {
			this.candidate = candidate;
			this.future = future;
		}
	}
}
