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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.internal.Timeouts;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioDeviceModuleBase;
import dev.onvoid.webrtc.media.audio.AudioProcessing;
import dev.onvoid.webrtc.media.audio.AudioProcessingConfig;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;
import dev.onvoid.webrtc.media.video.codec.HardwareVideoDecoderFactory;
import dev.onvoid.webrtc.media.video.codec.HardwareVideoEncoderFactory;

/**
 * The entry point of the client API. A runtime owns a {@link
 * PeerConnectionFactory}, its audio device module, the threads of this API,
 * and every peer and track created through it. Closing it closes them all, in
 * the order the native layer needs.
 * <pre>{@code
 * try (WebRtc rtc = WebRtc.create()) {
 *     Peer peer = rtc.createPeer(PeerOptions.builder()
 *             .iceServer("stun:stun.l.google.com:19302")
 *             .signaling(message -> socket.send(message.toJson()))
 *             .build());
 *     socket.onText(text -> peer.signal(SignalMessage.fromJson(text)));
 *
 *     peer.addTrack(rtc.media().microphone());
 *     peer.onTrack(track -> System.out.println("Receiving " + track.kind()));
 *     ...
 * }
 * }</pre>
 * <h2>Threads</h2>
 * Every method of this API may be called from any thread, and none waits for a
 * WebRTC thread. The library never runs application code on a thread WebRTC
 * needs: what WebRTC reports is copied and queued, then handed to the
 * application on the event thread of the peer it belongs to. The handlers of
 * one peer run one at a time and in order; those of different peers may run
 * at the same time, unless the {@link Builder#eventExecutor(Executor) event
 * executor} is serial, such as the Swing event dispatch thread.
 * <p>
 * By default the runtime starts a thread for each peer and each subscription
 * whose handlers have work. {@link Builder#eventThreads(int)} and {@link
 * Builder#frameThreads(int)} limit them; what finds no thread then waits its
 * turn, and {@link #loadStats()} tells how much waits.
 */
public final class WebRtc implements AutoCloseable {

	private static final AtomicInteger RUNTIME_COUNT = new AtomicInteger();

	final PeerConnectionFactory factory;

	final AudioMode audioMode;

	final Executor eventExecutor;

	final boolean ownsEventExecutor;

	final ExecutorService controlPool;

	final ExecutorService framePool;

	final ScheduledExecutorService timer;

	final Duration operationTimeout;

	/** How much audio an {@code onAudio} subscription buffers by default. */
	final Duration audioBuffer;

	/** The tasks of the control executors. */
	final Workload controlWork;

	/** The tasks of the peers' event executors. */
	final Workload eventWork;

	/** The frames of the video subscriptions. */
	final Workload videoWork;

	/** The chunks of the audio subscriptions. */
	final Workload audioWork;

	private final AudioDeviceModuleBase audioModule;

	private final AudioProcessing audioProcessing;

	private final Duration closeTimeout;

	private final Consumer<Throwable> errorHandler;

	private final LocalMediaImpl media;

	private final Set<PeerImpl> peers = Collections.newSetFromMap(new ConcurrentHashMap<>());

	private final AtomicInteger peerCount = new AtomicInteger();

	private final AtomicBoolean closing = new AtomicBoolean();

	/** Never handed out, so that no caller can cancel what the close waits for. */
	private final CompletableFuture<Void> closed = new CompletableFuture<>();

	/** The futures tracked that are not done, with what fails each. */
	private final Map<CompletableFuture<?>, Runnable> pending = new ConcurrentHashMap<>();

	/** Set once the control tasks have run, after which none ever runs. */
	private volatile boolean stopped;


	private WebRtc(Builder builder) {
		int runtime = RUNTIME_COUNT.incrementAndGet();

		this.audioMode = builder.audioMode;
		this.operationTimeout = builder.operationTimeout;
		this.closeTimeout = builder.closeTimeout;
		this.audioBuffer = builder.audioBuffer;
		this.errorHandler = builder.errorHandler != null ? builder.errorHandler : WebRtc::uncaught;

		this.controlWork = new Workload(builder.clock);
		this.eventWork = new Workload(builder.clock);
		this.videoWork = new Workload(builder.clock);
		this.audioWork = new Workload(builder.clock);

		// Never limited: control tasks wait for other peers' control tasks,
		// such as a camera switch for every sender to take the new track.
		this.controlPool = builder.controlPool != null ? builder.controlPool
				: Executors.newCachedThreadPool(threads("webrtc-client-control-" + runtime + "-"));
		this.framePool = pool(builder.frameThreads, "webrtc-client-frames-" + runtime + "-");
		this.timer = builder.timer != null ? builder.timer
				: Executors.newSingleThreadScheduledExecutor(threads("webrtc-client-timer-" + runtime + "-"));

		if (builder.eventExecutor != null) {
			this.eventExecutor = builder.eventExecutor;
			this.ownsEventExecutor = false;
		}
		else {
			this.eventExecutor = pool(builder.eventThreads, "webrtc-client-events-" + runtime + "-");
			this.ownsEventExecutor = true;
		}

		AudioDeviceModuleBase module = null;
		AudioProcessing processing = null;
		PeerConnectionFactory created = null;

		try {
			module = audioMode == AudioMode.HEADLESS
					? new HeadlessAudioDeviceModule()
					: new AudioDeviceModule();

			if (builder.audioProcessingConfig != null) {
				processing = new AudioProcessing();
				processing.applyConfig(builder.audioProcessingConfig);
			}

			PeerConnectionFactory.Builder factoryBuilder = PeerConnectionFactory.builder()
					.setAudioDeviceModule(module);

			if (processing != null) {
				factoryBuilder.setAudioProcessing(processing);
			}
			if (builder.fieldTrials != null) {
				factoryBuilder.setFieldTrials(builder.fieldTrials);
			}
			if (builder.hardwareVideoCodecs) {
				factoryBuilder.setVideoEncoderFactory(new HardwareVideoEncoderFactory());
				factoryBuilder.setVideoDecoderFactory(new HardwareVideoDecoderFactory());
			}
			if (builder.factoryCustomizer != null) {
				builder.factoryCustomizer.accept(factoryBuilder);
			}

			created = factoryBuilder.build();

			if (audioMode == AudioMode.HEADLESS) {
				// Playout drives the receive side of every connection of the
				// factory, so it runs even when nothing is received yet.
				module.initPlayout();
				module.startPlayout();
			}
		}
		catch (RuntimeException | Error e) {
			if (created != null) {
				created.dispose();
			}
			if (module != null) {
				module.dispose();
			}
			if (processing != null) {
				processing.dispose();
			}
			shutdownPools();

			throw new ConfigurationException("The WebRTC runtime could not be created: " + e.getMessage(), e);
		}

		this.audioModule = module;
		this.audioProcessing = processing;
		this.factory = created;
		this.media = new LocalMediaImpl(this, module);
	}

	/**
	 * Creates a runtime that captures the microphone, plays to the speakers
	 * and uses the built-in codecs.
	 *
	 * @return the runtime.
	 */
	public static WebRtc create() {
		return builder().build();
	}

	/**
	 * @return a builder for a runtime.
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Creates a peer. Unlike the other methods, this one waits for the
	 * connection to be created, so that an invalid configuration fails here.
	 *
	 * @param options how to set up the peer.
	 *
	 * @return the peer.
	 *
	 * @throws ConfigurationException if WebRTC refuses the configuration.
	 * @throws PeerClosedException    if the runtime is closed.
	 */
	public Peer createPeer(PeerOptions options) {
		requireNonNull(options, "options");
		checkOpen();

		int number = peerCount.incrementAndGet();
		String name = options.name != null ? options.name : "peer-" + number;

		return open(register(new PeerImpl(this, options, name)));
	}

	/**
	 * @return the source of the tracks this runtime sends.
	 */
	public LocalMedia media() {
		return media;
	}

	/**
	 * @return the peers that are not closed.
	 */
	public List<Peer> peers() {
		return Collections.unmodifiableList(new ArrayList<Peer>(peers));
	}

	/**
	 * @return the audio mode the runtime was built with.
	 */
	public AudioMode audioMode() {
		return audioMode;
	}

	/**
	 * Tells how much work waits for the threads of this runtime and how much
	 * runs, at this moment. Cheap enough to be read every second, from any
	 * thread.
	 *
	 * @return the load of the runtime.
	 */
	public LoadStats loadStats() {
		return new LoadStats(controlWork.snapshot(), eventWork.snapshot(),
				videoWork.snapshot(), audioWork.snapshot(),
				poolSize(controlPool), ownsEventExecutor ? poolSize(eventExecutor) : 0,
				poolSize(framePool));
	}

	/**
	 * Closes every peer, then every track, then the factory, and stops the
	 * threads of this API.
	 * <p>
	 * When a peer or track does not close within the close timeout, the
	 * factory is left undisposed, since disposing it could free what WebRTC's
	 * threads still use. The future then fails with a {@link WebRtcException}
	 * that names the cause, as it does when disposing fails.
	 *
	 * @return a future that completes once all is closed, or fails when not
	 * everything could be released. Each call returns a future of its own:
	 * cancelling it stops waiting, not the close.
	 */
	public CompletableFuture<Void> closeAsync() {
		startClose();

		// Not on an event thread, so that one may wait for it.
		CompletableFuture<Void> result = new CompletableFuture<>();
		closed.whenComplete((ignored, error) -> PeerImpl.complete(result, null, error));

		return result;
	}

	/**
	 * Closes every peer, then every track, then the factory, and stops the
	 * threads of this API. Waits for that up to the close timeout, which is
	 * the one wait in this API. May be called more than once.
	 * <p>
	 * Once it returns, every peer's {@link Peer#state()} is {@link
	 * PeerState#CLOSED}. The peers' {@code whenClosed} futures and their
	 * {@code CLOSED} events follow on their event threads; waiting for those
	 * here could never end when this is called on an event thread.
	 */
	@Override
	public void close() {
		startClose();

		try {
			closed.get(Timeouts.toMillis(closeTimeout), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		catch (ExecutionException e) {
			// Closing reported its failure already.
		}
		catch (TimeoutException e) {
			reportError(new WebRtcException("The runtime did not close within " + closeTimeout, e));
		}
	}

	private void startClose() {
		if (closing.compareAndSet(false, true)) {
			Thread thread = new Thread(this::shutdown, "webrtc-client-close");
			thread.setDaemon(true);
			thread.start();
		}
	}

	/**
	 * Registers a peer before the runtime is checked again: a runtime that
	 * begins to close either finds the peer among those it closes and waits
	 * for it before freeing the factory, or is seen closing here.
	 */
	PeerImpl register(PeerImpl peer) {
		peers.add(peer);

		if (closing.get()) {
			peers.remove(peer);
			throw new PeerClosedException("The runtime is closed");
		}

		return peer;
	}

	/**
	 * Creates the connection of a registered peer.
	 */
	PeerImpl open(PeerImpl peer) {
		try {
			peer.open();
		}
		catch (RuntimeException e) {
			// Closed before the error goes out, so that nothing of the failed
			// peer still runs when the runtime closes and frees WebRTC's threads.
			// Closing removes the peer. Once the runtime has stopped its threads
			// the close cannot run any more, and there is nothing left to wait for.
			CompletableFuture.anyOf(peer.closeInternal(), closed).join();
			throw e;
		}

		return peer;
	}

	void reportError(Throwable error) {
		try {
			errorHandler.accept(error);
		}
		catch (Throwable e) {
			uncaught(e);
		}
	}

	void removePeer(PeerImpl peer) {
		peers.remove(peer);
	}

	void checkOpen() {
		if (closing.get()) {
			throw new PeerClosedException("The runtime is closed");
		}
	}

	boolean isClosing() {
		return closing.get();
	}

	/**
	 * Fails the future, unless it is done by then, once the runtime has
	 * stopped the threads that could complete it. A task that would have
	 * completed it is dropped then, and the future would stay open forever.
	 *
	 * @param future the future handed out or waited on.
	 * @param events the event executor the future completes on, so that the
	 *               failure comes after what is queued there; or null.
	 */
	void track(CompletableFuture<?> future, SerialExecutor events) {
		Runnable fail = () -> future.completeExceptionally(
				new PeerClosedException("The runtime closed before this completed"));
		Runnable orphan = events == null ? fail : () -> events.execute(fail, fail);

		pending.put(future, orphan);
		future.whenComplete((value, error) -> pending.remove(future));

		if (stopped) {
			orphan.run();
		}
	}

	private void shutdown() {
		Throwable failure = null;

		try {
			// Peers and tracks created while the runtime closes are closed as
			// well, so each is waited for until none is left.
			failure = awaitAll(() -> {
				List<CompletableFuture<Void>> closing = new ArrayList<>();

				for (PeerImpl peer : new ArrayList<>(peers)) {
					closing.add(peer.closeInternal());
				}
				return closing;
			}, "peers");

			if (failure == null) {
				failure = awaitAll(media::closeAll, "local tracks");
			}

			if (failure != null) {
				// Something may still run on WebRTC's threads, and disposing
				// the factory would free them under it. Leaking is the lesser harm.
				failure = new WebRtcException("The factory of the runtime was left undisposed, "
						+ "since not everything it created closed", failure);
				reportError(failure);
				return;
			}

			media.dispose();

			try {
				if (audioMode == AudioMode.HEADLESS) {
					audioModule.stopPlayout();
				}
			}
			catch (Throwable e) {
				// The module is disposed next anyway.
			}

			audioModule.dispose();
			factory.dispose();

			if (audioProcessing != null) {
				audioProcessing.dispose();
			}
		}
		catch (Throwable e) {
			failure = e;
			reportError(e);
		}
		finally {
			shutdownPools();
			failPending();

			if (failure == null) {
				closed.complete(null);
			}
			else {
				closed.completeExceptionally(failure);
			}
		}
	}

	/**
	 * Fails the futures that nothing completes any more, once the control
	 * tasks still queued have run.
	 */
	private void failPending() {
		try {
			controlPool.awaitTermination(Timeouts.toMillis(closeTimeout), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		// Set before the futures are listed: one tracked meanwhile either is
		// listed or sees it set.
		stopped = true;

		for (Runnable orphan : new ArrayList<>(pending.values())) {
			orphan.run();
		}
	}

	/**
	 * Closes what the supplier names, again and again, until it names nothing.
	 *
	 * @return null once all is closed, or why a round did not finish.
	 */
	private WebRtcException awaitAll(Supplier<List<CompletableFuture<Void>>> closes, String what) {
		for (int round = 0; round < 10; round++) {
			List<CompletableFuture<Void>> futures = closes.get();

			if (futures.isEmpty()) {
				return null;
			}

			try {
				CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
						.get(Timeouts.toMillis(closeTimeout), TimeUnit.MILLISECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return new WebRtcException("Closing the " + what + " was interrupted", e);
			}
			catch (TimeoutException e) {
				return new WebRtcException("Closing the " + what + " did not finish within " + closeTimeout, e);
			}
			catch (ExecutionException e) {
				return new WebRtcException("Closing the " + what + " failed", e.getCause());
			}
		}

		return closes.get().isEmpty() ? null
				: new WebRtcException("The " + what + " kept being created while the runtime closed");
	}

	private void shutdownPools() {
		controlPool.shutdown();
		framePool.shutdown();
		timer.shutdownNow();

		if (ownsEventExecutor) {
			((ExecutorService) eventExecutor).shutdown();
		}
	}

	/**
	 * Creates a pool of at most the given number of threads, or an unlimited
	 * one for 0. A limited pool queues what finds no thread, in order, and
	 * never rejects it while it runs; since each serial executor has at most
	 * one turn queued at a time, the queue holds no more than one turn per
	 * peer or subscription.
	 */
	private static ExecutorService pool(int limit, String prefix) {
		if (limit == 0) {
			return Executors.newCachedThreadPool(threads(prefix));
		}

		ThreadPoolExecutor pool = new ThreadPoolExecutor(limit, limit, 60, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(), threads(prefix));
		// Idle threads end, as those of an unlimited pool do.
		pool.allowCoreThreadTimeOut(true);

		return pool;
	}

	private static int poolSize(Executor executor) {
		return executor instanceof ThreadPoolExecutor ? ((ThreadPoolExecutor) executor).getPoolSize() : 0;
	}

	private static ThreadFactory threads(String prefix) {
		AtomicInteger count = new AtomicInteger();

		return task -> {
			Thread thread = new Thread(task, prefix + count.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}

	private static void uncaught(Throwable error) {
		Thread thread = Thread.currentThread();
		thread.getUncaughtExceptionHandler().uncaughtException(thread, error);
	}



	/** Builds a {@link WebRtc} runtime. */
	public static final class Builder {

		private AudioMode audioMode = AudioMode.DEVICES;

		private AudioProcessingConfig audioProcessingConfig;

		private boolean hardwareVideoCodecs;

		private Map<String, String> fieldTrials;

		private Executor eventExecutor;

		private Consumer<Throwable> errorHandler;

		private Duration operationTimeout = Duration.ofSeconds(30);

		private Duration closeTimeout = Duration.ofSeconds(10);

		private Duration audioBuffer = Duration.ofSeconds(5);

		private int eventThreads;

		private int frameThreads;

		private Consumer<PeerConnectionFactory.Builder> factoryCustomizer;

		private ExecutorService controlPool;

		private ScheduledExecutorService timer;

		private LongSupplier clock = System::nanoTime;


		private Builder() {
		}

		/**
		 * @param mode where sent audio comes from; {@link AudioMode#DEVICES}
		 *             by default.
		 *
		 * @return this builder.
		 */
		public Builder audio(AudioMode mode) {
			this.audioMode = requireNonNull(mode, "mode");
			return this;
		}

		/**
		 * @param config the audio processing applied to captured audio.
		 *
		 * @return this builder.
		 */
		public Builder audioProcessing(AudioProcessingConfig config) {
			this.audioProcessingConfig = requireNonNull(config, "config");
			return this;
		}

		/**
		 * @param enabled true to encode and decode video on the GPU where it
		 *                can, and in software otherwise.
		 *
		 * @return this builder.
		 */
		public Builder hardwareVideoCodecs(boolean enabled) {
			this.hardwareVideoCodecs = enabled;
			return this;
		}

		/**
		 * @param fieldTrials WebRTC field trials, by name.
		 *
		 * @return this builder.
		 */
		public Builder fieldTrials(Map<String, String> fieldTrials) {
			this.fieldTrials = requireNonNull(fieldTrials, "fieldTrials");
			return this;
		}

		/**
		 * Sets where handlers and future callbacks run, such as {@code
		 * SwingUtilities::invokeLater} or {@code Platform::runLater}. The
		 * order of each peer's events is kept whatever the executor. By
		 * default the runtime runs them on threads of its own.
		 * <p>
		 * An executor that rejects work loses the events it rejects, which
		 * goes to the error handler. The futures those events would have
		 * completed complete all the same, on the thread that found them
		 * rejected.
		 *
		 * @param executor runs handlers.
		 *
		 * @return this builder.
		 */
		public Builder eventExecutor(Executor executor) {
			this.eventExecutor = requireNonNull(executor, "executor");
			return this;
		}

		/**
		 * Limits the threads the runtime runs handlers on, those of peers,
		 * tracks and channels, future callbacks and {@code Signaling.send}.
		 * Unlimited by default, so that each peer whose events wait gets a
		 * thread.
		 * <p>
		 * With a limit, the peers take turns: a peer that has events runs a
		 * few of them, for at most a few milliseconds, then lets the next
		 * peer in line run, so that slow handlers delay the others but never
		 * starve them. Nothing is rejected or dropped for the limit; events
		 * wait, and {@link LoadStats#events()} tells how many and for how
		 * long. A handler that waits for what another peer's handler does
		 * may then wait forever, once every thread is taken by such waits.
		 * <p>
		 * Applies only to the runtime's own threads, not to an {@link
		 * #eventExecutor(Executor) executor of the application}.
		 *
		 * @param limit the most threads, 1 or more.
		 *
		 * @return this builder.
		 */
		public Builder eventThreads(int limit) {
			this.eventThreads = atLeastOne(limit);
			return this;
		}

		/**
		 * Limits the threads that run {@code onFrame} and {@code onAudio}
		 * handlers, of all subscriptions together, local previews included.
		 * Unlimited by default, so that each subscription with media waiting
		 * gets a thread.
		 * <p>
		 * With a limit, the subscriptions take turns as the peers do with
		 * {@link #eventThreads(int)}. A subscription waiting for a thread
		 * keeps what its {@link FrameDelivery} or audio buffer holds and drops
		 * the oldest beyond that, which {@link LoadStats#video()} and {@link
		 * LoadStats#audio()} count.
		 *
		 * @param limit the most threads, 1 or more.
		 *
		 * @return this builder.
		 */
		public Builder frameThreads(int limit) {
			this.frameThreads = atLeastOne(limit);
			return this;
		}

		/**
		 * Sets how much decoded audio an {@link RemoteAudio#onAudio(Consumer)
		 * onAudio} subscription keeps for a handler that falls behind. The
		 * oldest audio beyond that is dropped. A short buffer keeps a live
		 * stream close to real time; a long one rides out a handler that
		 * stalls now and then, at the cost of memory, about 190 KiB per second
		 * of 48 kHz stereo.
		 *
		 * @param buffer the most audio that waits; 5 seconds by default.
		 *
		 * @return this builder.
		 */
		public Builder audioBuffer(Duration buffer) {
			this.audioBuffer = Timeouts.requirePositive(buffer, "audio buffer");
			return this;
		}

		/**
		 * Sets what receives the exceptions handlers throw and the failures
		 * nobody else hears of. By default they go to the uncaught exception
		 * handler of the thread they happen on.
		 *
		 * @param handler receives the errors. It must not throw.
		 *
		 * @return this builder.
		 */
		public Builder errorHandler(Consumer<Throwable> handler) {
			this.errorHandler = requireNonNull(handler, "handler");
			return this;
		}

		/**
		 * @param timeout how long an operation, such as applying a
		 *                description, may take before it fails with a {@code
		 *                TimeoutException}; 30 seconds by default.
		 *
		 * @return this builder.
		 */
		public Builder operationTimeout(Duration timeout) {
			this.operationTimeout = Timeouts.requirePositive(timeout, "operation timeout");
			return this;
		}

		/**
		 * @param timeout how long {@link WebRtc#close()} waits; 10 seconds by
		 *                default.
		 *
		 * @return this builder.
		 */
		public Builder closeTimeout(Duration timeout) {
			this.closeTimeout = Timeouts.requirePositive(timeout, "close timeout");
			return this;
		}

		/**
		 * Changes anything else of the factory, after this builder applied its
		 * own values. The audio device module must be left as it is.
		 *
		 * @param customizer changes the factory builder.
		 *
		 * @return this builder.
		 */
		public Builder configureFactory(Consumer<PeerConnectionFactory.Builder> customizer) {
			this.factoryCustomizer = requireNonNull(customizer, "customizer");
			return this;
		}

		/**
		 * Replaces the pool the control tasks run on, so that tests decide
		 * when each of them runs. The runtime shuts it down when it closes.
		 */
		Builder controlPool(ExecutorService pool) {
			this.controlPool = requireNonNull(pool, "pool");
			return this;
		}

		/**
		 * Replaces the timer of timeouts and periodic tasks, so that tests
		 * decide when each of them fires. The runtime shuts it down when it
		 * closes.
		 */
		Builder timer(ScheduledExecutorService timer) {
			this.timer = requireNonNull(timer, "timer");
			return this;
		}

		/**
		 * Replaces the clock of the load statistics and of the turns of the
		 * executors, so that tests decide how much time passes.
		 *
		 * @param nanos the time in nanoseconds, as {@link System#nanoTime()}.
		 */
		Builder clock(LongSupplier nanos) {
			this.clock = requireNonNull(nanos, "nanos");
			return this;
		}

		/**
		 * @return the runtime.
		 *
		 * @throws ConfigurationException if the factory cannot be created.
		 * @throws IllegalStateException  if both {@link #eventThreads(int)}
		 *                                and {@link #eventExecutor(Executor)}
		 *                                were set.
		 */
		public WebRtc build() {
			if (eventExecutor != null && eventThreads != 0) {
				throw new IllegalStateException("eventThreads limits the runtime's own event threads, "
						+ "which an eventExecutor replaces; set only one of them");
			}

			return new WebRtc(this);
		}

		private static int atLeastOne(int limit) {
			if (limit < 1) {
				throw new IllegalArgumentException("The limit must be 1 or more, got " + limit);
			}

			return limit;
		}
	}
}
