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

package dev.onvoid.webrtc.media.audio;

import dev.onvoid.webrtc.internal.DisposableNativeObject;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * Captures the system output as a separate audio track source, at 48 kHz
 * stereo. Use it with {@code PeerConnectionFactory.createAudioTrack}, alongside
 * a screen video track. A separate microphone track must also use pushed audio;
 * do not combine this with the factory's device-captured audio sources.
 * <p>
 * Windows uses WASAPI loopback of the default multimedia output; Linux uses the
 * default output's PulseAudio monitor (also available with pipewire-pulse).
 * macOS 13 or later uses ScreenCaptureKit and excludes this process's audio.
 * Screen/audio recording permission is required on macOS. This class does not
 * capture a selected window's or process's audio.
 * <p>
 * Call {@link #start()} to begin and {@link #stop()} before changing output
 * devices. A restart selects the current default output. Capture errors stop
 * recording and are available through {@link #getFailure()}. Remote call audio
 * played on the captured output is included on Windows and Linux: route it to
 * another output to prevent feedback. No microphone mixing or echo cancellation
 * is performed. The inherited {@code pushAudio} methods are rejected, since the
 * captured audio is the only audio this source sends.
 *
 * @author Alex Andres
 */
public final class SystemAudioSource extends CustomAudioSource implements AutoCloseable {

	/**
	 * How long the capture worker waits between two polls of the platform. A
	 * chunk lasts 10 ms, and the platform buffers up to 100 ms.
	 */
	private static final long POLL_INTERVAL_NANOS = 2_000_000L;

	/** Creates the platform capture, once for each start. */
	private final Supplier<Backend> backend;

	/** Set once the native source has been released. */
	private final AtomicBoolean released = new AtomicBoolean();

	/** Whether capture is running, including its startup. */
	private volatile boolean recording;

	/** The last startup or runtime failure, cleared by a start. */
	private volatile Throwable failure;

	/** The thread that runs the capture, or null before the first start. */
	private volatile Thread worker;

	/** Set by the first disposal; never reset. */
	private volatile boolean disposed;


	/**
	 * Creates an idle source. No device is opened and no recording permission
	 * is requested until {@link #start()}.
	 */
	public SystemAudioSource() {
		this(Capture::new);
	}

	/**
	 * Creates an idle source with the given platform capture, which lets tests
	 * replace the native one.
	 *
	 * @param backend Creates the platform capture, once for each start.
	 */
	SystemAudioSource(Supplier<Backend> backend) {
		this.backend = backend;
	}

	/**
	 * Starts capture, waiting for the platform to open the output. Calling this
	 * while already recording does nothing. Startup uses bounded platform
	 * waits, including the macOS permission prompt, so call it on a background
	 * thread.
	 *
	 * @throws IllegalStateException If this source is disposed, if called from
	 *                               an audio callback of this source, or if
	 *                               capture cannot start; the platform error is
	 *                               then the cause.
	 */
	public void start() {
		if (Thread.currentThread() == worker) {
			throw new IllegalStateException("Start capture outside an audio callback");
		}

		synchronized (this) {
			startLocked();
		}
	}

	/**
	 * Stops and joins capture. The source and its tracks may be started again.
	 * Called from an audio callback of this source, it requests the stop
	 * without waiting for it.
	 */
	public void stop() {
		// A local track sink can run inside pump(). It must not wait for its
		// own worker or for a lifecycle caller that is joining that worker.
		if (Thread.currentThread() == worker) {
			recording = false;
			return;
		}

		synchronized (this) {
			stopLocked();
		}
	}

	/**
	 * Returns whether capture is running, including its startup.
	 *
	 * @return True while capturing.
	 */
	public boolean isRecording() {
		return recording;
	}

	/**
	 * Returns the last startup or runtime failure. Starting again clears it.
	 *
	 * @return The failure, or {@code null} if capture has not failed.
	 */
	public Throwable getFailure() {
		return failure;
	}

	/**
	 * Rejected: this source sends the captured system audio only.
	 *
	 * @throws IllegalStateException Always, unless called by the capture
	 *                               itself.
	 */
	@Override
	public void pushAudio(byte[] audioData, int bits_per_sample, int sampleRate,
						  int channels, int frameCount) {
		requireCaptureThread();

		super.pushAudio(audioData, bits_per_sample, sampleRate, channels, frameCount);
	}

	/**
	 * Rejected: this source sends the captured system audio only.
	 *
	 * @throws IllegalStateException Always, unless called by the capture
	 *                               itself.
	 */
	@Override
	public void pushAudio(byte[] audioData, int bits_per_sample, int sampleRate,
						  int channels, int frameCount, long timestampUs) {
		requireCaptureThread();

		super.pushAudio(audioData, bits_per_sample, sampleRate, channels, frameCount, timestampUs);
	}

	/**
	 * Stops capture before releasing the native source. Safe to call more than
	 * once. Called from an audio callback of this source, it requests the stop
	 * and the source is released once the callback has returned.
	 */
	@Override
	public void dispose() {
		if (Thread.currentThread() == worker) {
			disposed = true;
			recording = false;
			return;
		}

		synchronized (this) {
			disposed = true;

			stopLocked();
			releaseSource();
		}
	}

	/**
	 * Stops capture and releases the source, see {@link #dispose()}.
	 */
	@Override
	public void close() {
		dispose();
	}

	private void startLocked() {
		if (disposed) {
			throw new IllegalStateException("System audio source is disposed");
		}
		if (recording) {
			return;
		}

		stopLocked();

		// The previous worker may have disposed the source from its last sink
		// callback while stopLocked() was joining it.
		if (disposed) {
			throw new IllegalStateException("System audio source is disposed");
		}

		CompletableFuture<Void> started = new CompletableFuture<>();

		failure = null;
		recording = true;
		worker = new Thread(() -> capture(started), "webrtc-system-audio");
		worker.setDaemon(true);

		try {
			worker.start();
			started.join();
		}
		catch (CompletionException e) {
			stopLocked();

			throw new IllegalStateException("Cannot start system audio capture", e.getCause());
		}
		catch (RuntimeException | Error e) {
			recording = false;

			throw e;
		}
	}

	private void stopLocked() {
		recording = false;

		Thread thread = worker;
		boolean interrupted = false;

		if (thread != null) {
			LockSupport.unpark(thread);

			while (thread.isAlive()) {
				try {
					thread.join();
				}
				catch (InterruptedException e) {
					interrupted = true;
				}
			}

			worker = null;
		}

		if (interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Runs on the worker: opens, polls and closes one platform capture, all on
	 * this thread, as WASAPI's COM objects require.
	 *
	 * @param started Completed once the capture is open, or with its failure.
	 */
	private void capture(CompletableFuture<Void> started) {
		try (Backend capture = backend.get()) {
			capture.initialize();

			started.complete(null);

			while (recording) {
				capture.pump(this);

				LockSupport.parkNanos(POLL_INTERVAL_NANOS);
			}
		}
		catch (Throwable e) {
			failure = e;

			started.completeExceptionally(e);
		}
		finally {
			recording = false;

			if (disposed) {
				releaseSource();
			}
		}
	}

	private void requireCaptureThread() {
		if (Thread.currentThread() != worker) {
			throw new IllegalStateException("A SystemAudioSource sends the captured system audio only. "
					+ "Push audio into a separate CustomAudioSource.");
		}
	}

	private void releaseSource() {
		if (released.compareAndSet(false, true)) {
			super.dispose();
		}
	}


	/**
	 * The platform capture of one start, opened, pumped and closed on the
	 * capture worker.
	 */
	interface Backend extends AutoCloseable {

		/**
		 * Opens the default output.
		 */
		void initialize();

		/**
		 * Pushes the audio captured since the last call into the source.
		 *
		 * @param source The source to push into.
		 */
		void pump(CustomAudioSource source);

		/**
		 * Closes the output.
		 */
		void dispose();

		@Override
		default void close() {
			dispose();
		}

	}


	/**
	 * The native platform capture.
	 */
	private static final class Capture extends DisposableNativeObject implements Backend {

		@Override
		public native void initialize();

		@Override
		public native void pump(CustomAudioSource source);

		@Override
		public native void dispose();

	}

}
