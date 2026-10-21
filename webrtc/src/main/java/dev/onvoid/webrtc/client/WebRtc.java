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
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.onvoid.webrtc.PeerConnectionFactory;
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

	private final AudioDeviceModuleBase audioModule;

	private final AudioProcessing audioProcessing;

	private final Duration closeTimeout;

	private final Consumer<Throwable> errorHandler;

	private final LocalMediaImpl media;

	private final Set<PeerImpl> peers = Collections.newSetFromMap(new ConcurrentHashMap<>());

	private final AtomicInteger peerCount = new AtomicInteger();

	private final AtomicBoolean closing = new AtomicBoolean();

	private final CompletableFuture<Void> closed = new CompletableFuture<>();


	private WebRtc(Builder builder) {
		int runtime = RUNTIME_COUNT.incrementAndGet();

		this.audioMode = builder.audioMode;
		this.operationTimeout = builder.operationTimeout;
		this.closeTimeout = builder.closeTimeout;
		this.errorHandler = builder.errorHandler != null ? builder.errorHandler : WebRtc::uncaught;

		this.controlPool = Executors.newCachedThreadPool(threads("webrtc-client-control-" + runtime + "-"));
		this.framePool = Executors.newCachedThreadPool(threads("webrtc-client-frames-" + runtime + "-"));
		this.timer = Executors.newSingleThreadScheduledExecutor(threads("webrtc-client-timer-" + runtime + "-"));

		if (builder.eventExecutor != null) {
			this.eventExecutor = builder.eventExecutor;
			this.ownsEventExecutor = false;
		}
		else {
			this.eventExecutor = Executors.newCachedThreadPool(threads("webrtc-client-events-" + runtime + "-"));
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

		PeerImpl peer = new PeerImpl(this, options, name);
		peers.add(peer);

		try {
			peer.open();
		}
		catch (RuntimeException e) {
			// Closed before the error goes out, so that nothing of the failed
			// peer still runs when the runtime closes and frees WebRTC's threads.
			peers.remove(peer);
			peer.closeInternal().join();
			throw e;
		}

		if (closing.get()) {
			// The runtime began to close meanwhile and may have missed it.
			peer.closeInternal();
		}

		return peer;
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
	 * Closes every peer, then every track, then the factory, and stops the
	 * threads of this API.
	 *
	 * @return a future that completes once all is closed.
	 */
	public CompletableFuture<Void> closeAsync() {
		if (closing.compareAndSet(false, true)) {
			Thread thread = new Thread(this::shutdown, "webrtc-client-close");
			thread.setDaemon(true);
			thread.start();
		}

		return closed;
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
		CompletableFuture<Void> future = closeAsync();

		try {
			future.get(closeTimeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		catch (Exception e) {
			reportError(new WebRtcException("The runtime did not close within " + closeTimeout, e));
		}
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

	private void shutdown() {
		try {
			// Peers and tracks created while the runtime closes are closed as
			// well, so each is waited for until none is left.
			boolean released = awaitAll(() -> {
				List<CompletableFuture<Void>> closing = new ArrayList<>();

				for (PeerImpl peer : new ArrayList<>(peers)) {
					closing.add(peer.closeInternal());
				}
				return closing;
			}, "peers") && awaitAll(media::closeAll, "local tracks");

			if (!released) {
				// Something may still run on WebRTC's threads, and disposing
				// the factory would free them under it. Leaking is the lesser harm.
				reportError(new WebRtcException("The factory of the runtime was left undisposed, "
						+ "since not everything it created closed within " + closeTimeout));
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
			reportError(e);
		}
		finally {
			shutdownPools();
			closed.complete(null);
		}
	}

	/**
	 * Closes what the supplier names, again and again, until it names nothing.
	 *
	 * @return false if a round did not finish within the close timeout.
	 */
	private boolean awaitAll(Supplier<List<CompletableFuture<Void>>> closes, String what) {
		for (int round = 0; round < 10; round++) {
			List<CompletableFuture<Void>> futures = closes.get();

			if (futures.isEmpty()) {
				return true;
			}

			try {
				CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
						.get(closeTimeout.toMillis(), TimeUnit.MILLISECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
			catch (Exception e) {
				reportError(new WebRtcException("Closing the " + what + " did not finish within " + closeTimeout, e));
				return false;
			}
		}

		return closes.get().isEmpty();
	}

	private void shutdownPools() {
		controlPool.shutdown();
		framePool.shutdown();
		timer.shutdownNow();

		if (ownsEventExecutor) {
			((ExecutorService) eventExecutor).shutdown();
		}
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

		private Consumer<PeerConnectionFactory.Builder> factoryCustomizer;


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
			this.operationTimeout = positive(timeout);
			return this;
		}

		/**
		 * @param timeout how long {@link WebRtc#close()} waits; 10 seconds by
		 *                default.
		 *
		 * @return this builder.
		 */
		public Builder closeTimeout(Duration timeout) {
			this.closeTimeout = positive(timeout);
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
		 * @return the runtime.
		 *
		 * @throws ConfigurationException if the factory cannot be created.
		 */
		public WebRtc build() {
			return new WebRtc(this);
		}

		private static Duration positive(Duration duration) {
			requireNonNull(duration, "duration");

			if (duration.isNegative() || duration.isZero()) {
				throw new IllegalArgumentException("The duration must be positive, got " + duration);
			}

			return duration;
		}
	}
}
