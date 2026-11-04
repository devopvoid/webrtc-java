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
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCEncodedFrameTransformer;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceGatheringState;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCRtpEncodingParameters;
import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCRtpTransceiverDirection;
import dev.onvoid.webrtc.RTCRtpTransceiverInit;
import dev.onvoid.webrtc.client.internal.EncodedFrames;
import dev.onvoid.webrtc.media.MediaStream;
import dev.onvoid.webrtc.media.MediaType;

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
 * <p>
 * This class owns the lifecycle of the connection, its senders, and the step
 * from internal to public futures. The other concerns have classes of their
 * own, which keep to the same two executors and say which of their state
 * belongs to which: the {@link DataChannels}, the {@link RemoteTracks}, the
 * {@link StatsSampler}, the {@link SignalingLink}, and the {@link
 * ManualNegotiation} with its {@link CodecPreferences}.
 */
final class PeerImpl implements Peer {

	static {
		// Senders and remote tracks come from peers, so whoever holds one
		// finds the access to their encoded frames registered.
		EncodedFrames.register(new EncodedFramesAccess());
	}

	final WebRtc rtc;

	final String name;

	final SerialExecutor control;

	final SerialExecutor events;

	// Called from any thread; each says which of its state is confined to
	// which executor.

	final DataChannels channels;

	final ManualNegotiation manual;

	private final RemoteTracks tracks;

	private final StatsSampler statistics;

	private final SignalingLink signaling;

	private final PeerOptions options;

	/** The stream the tracks added without stream ids share. */
	final String defaultStreamId = UUID.randomUUID().toString();

	private final AtomicBoolean closing = new AtomicBoolean();

	private final CompletableFuture<Void> closedInternal = new CompletableFuture<>();

	private final CopyOnWriteArrayList<SenderImpl> senders = new CopyOnWriteArrayList<>();

	private volatile PeerState state = PeerState.NEW;

	// Confined to the control executor.

	private RTCPeerConnection connection;

	private boolean closed;

	private boolean teardownStarted;

	private final OperationChain chain;

	private final NegotiationSteps steps;

	private final PerfectNegotiation automatic;

	// Confined to the event executor.

	private PeerState deliveredState = PeerState.NEW;

	private final Handlers<PeerState> stateHandlers;

	private final Handlers<WebRtcException> errorHandlers;

	private final ClientFuture<Peer> connected = new ClientFuture<>();

	private final ClientFuture<Void> closedFuture = new ClientFuture<>();

	/** Gets the local candidates as well, or null. Called on the control executor. */
	private volatile Consumer<RTCIceCandidate> candidateListener;

	/** Runs once gathering is complete, or null. Called on the control executor. */
	private volatile Runnable gatheredListener;


	PeerImpl(WebRtc rtc, PeerOptions options, String name) {
		this.rtc = rtc;
		this.options = options;
		this.name = name;
		this.control = new SerialExecutor(rtc.controlPool, rtc.controlWork, rtc::reportError, false, null);
		this.events = new SerialExecutor(rtc.eventExecutor, rtc.eventWork, rtc::reportError, true,
				rtc.ownsEventExecutor ? "webrtc-client-events-" + name : null);
		this.stateHandlers = new Handlers<>(rtc::reportError);
		this.errorHandlers = new Handlers<>(rtc::reportError);
		this.chain = new OperationChain(control, rtc.timer, rtc.operationTimeout, name, new ChainHost());
		this.steps = new NegotiationSteps(control, rtc.timer, chain, () -> connection, options.gatheringTimeout,
				this::ignoresOffer, error -> {
					if (!closed) {
						deliverError(error);
					}
				});
		this.automatic = new PerfectNegotiation(this, options, chain, steps);
		this.statistics = new StatsSampler(this);
		this.channels = new DataChannels(this);
		this.tracks = new RemoteTracks(this, options.receiveTransform);
		this.manual = new ManualNegotiation(this, options, chain, steps, tracks, new CodecPreferences(this));
		this.signaling = new SignalingLink(this, options.signaling);

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

		signaling.attach();
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
		return tracks.onTrack(handler);
	}

	@Override
	public List<RemoteTrack> remoteTracks() {
		return tracks.current();
	}

	@Override
	public DataChannel createChannel(String label) {
		return createChannel(label, ChannelOptions.builder().build());
	}

	@Override
	public DataChannel createChannel(String label, ChannelOptions options) {
		return channels.create(label, options);
	}

	@Override
	public Subscription onChannel(Consumer<DataChannel> handler) {
		return channels.onChannel(handler);
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

		return manual;
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

	@Override
	public CompletableFuture<PeerStats> stats() {
		return statistics.read();
	}

	@Override
	public Subscription onStats(Duration interval, Consumer<PeerStats> handler) {
		return statistics.subscribe(interval, handler);
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
		signaling.send(SignalMessage.of(connection.getLocalDescription()));
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

		channels.closeAll(error);

		for (SenderImpl sender : senders) {
			sender.closeFromPeer();
		}
		senders.clear();

		List<RemoteTrackImpl> ended = tracks.endAll();

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
			signaling.detach();

			tracks.fireEnded(ended);
			channels.clearDelivered();

			deliverState(PeerState.CLOSED);

			connected.completeExceptionally(error);
			closedFuture.complete(null);

			tracks.clearHandlers();
			channels.clearHandlers();
			stateHandlers.clear();
			errorHandlers.clear();
		}, () -> {
			signaling.detach();

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
					Consumer<RTCIceCandidate> listener = candidateListener;

					if (listener != null) {
						listener.accept(candidate);
					}

					signaling.send(SignalMessage.of(candidate));
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
			control.execute(() -> tracks.added(receiver, mediaStreams));
		}

		@Override
		public void onRemoveTrack(RTCRtpReceiver receiver) {
			control.execute(() -> tracks.removed(receiver));
		}

		@Override
		public void onDataChannel(RTCDataChannel dataChannel) {
			// Observed at once, so that no message is missed; the observer
			// itself only posts as well.
			DataChannelImpl channel = new DataChannelImpl(PeerImpl.this, dataChannel);

			control.execute(() -> channels.opened(channel));
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
