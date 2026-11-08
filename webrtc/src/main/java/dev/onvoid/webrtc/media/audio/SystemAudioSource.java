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
import java.util.function.Supplier;
import java.util.concurrent.locks.LockSupport;

/**
 * Captures the system output as a separate audio track source, at 48 kHz stereo.
 * Use with {@code PeerConnectionFactory.createAudioTrack}, alongside a screen
 * video track. A separate microphone track must also use pushed audio;
 * do not combine this with the factory's device-captured audio sources.
 * <p>
 * Windows uses WASAPI loopback of the default multimedia output; Linux uses
 * the default output's PulseAudio monitor (also available with pipewire-pulse).
 * macOS 13 or later uses ScreenCaptureKit and excludes this process's audio.
 * Screen/audio recording permission is required on macOS. This class does not
 * capture a selected window's or process's audio.
 * <p>
 * Call {@link #start()} to begin and {@link #stop()} before changing output
 * devices. A restart selects the current default output. Capture errors stop
 * recording and are available through {@link #getFailure()}. Remote call audio
 * played on the captured output is included on Windows and Linux: route it to
 * another output to prevent feedback. No microphone mixing or echo cancellation
 * is performed. Do not call the inherited pushAudio methods during capture.
 */
public final class SystemAudioSource extends CustomAudioSource implements AutoCloseable {

    private volatile boolean recording;
    private volatile Throwable failure;
    private volatile Thread worker;
    private volatile boolean disposed;
    private final AtomicBoolean released = new AtomicBoolean();
    private final Supplier<Backend> backend;

    /** Creates an idle source. No device or recording permission is opened yet. */
    public SystemAudioSource() {
        this(Capture::new);
    }

    SystemAudioSource(Supplier<Backend> backend) {
        this.backend = backend;
    }

    /**
     * Starts capture, waiting for the platform to open the output.
     * Calling this while already recording does nothing. Startup uses bounded platform waits,
     * including the macOS permission prompt; use a background thread.
     *
     * @throws IllegalStateException if disposed or capture cannot start
     */
    public void start() {
        if (Thread.currentThread() == worker) {
            throw new IllegalStateException("Start capture outside an audio callback");
        }
        synchronized (this) {
            startLocked();
        }
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
        // callback while stopLocked was joining it.
        if (disposed) {
            throw new IllegalStateException("System audio source is disposed");
        }
        failure = null;
        CompletableFuture<Void> started = new CompletableFuture<>();
        recording = true;
        worker = new Thread(() -> capture(started), "webrtc-system-audio");
        worker.setDaemon(true);
        try {
            worker.start();
            started.join();
        }
        catch (CompletionException e) {
            stop();
            throw new IllegalStateException("Cannot start system audio capture", e.getCause());
        }
        catch (RuntimeException | Error e) {
            recording = false;
            throw e;
        }
    }

    private void capture(CompletableFuture<Void> started) {
        try (Backend capture = backend.get()) {
            capture.initialize();
            started.complete(null);
            while (recording) {
                capture.pump(this);
                LockSupport.parkNanos(2_000_000L);
            }
        }
        catch (Throwable e) {
            failure = e;
            started.completeExceptionally(e);
        }
        finally {
            recording = false;
            if (disposed) releaseSource();
        }
    }

    /** Stops and joins capture; the source and its tracks may be reused. */
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

    private void stopLocked() {
        recording = false;
        boolean interrupted = false;
        if (worker != null) {
            LockSupport.unpark(worker);
            while (worker.isAlive()) {
                try {
                    worker.join();
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

    /** @return whether capture is running (including startup) */
    public boolean isRecording() {
        return recording;
    }

    /** @return the last startup/runtime failure, or null; cleared by start */
    public Throwable getFailure() {
        return failure;
    }

    /** Stops capture before releasing the native source. Safe to call repeatedly. */
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

    /** Stops capture and releases the source. */
    @Override
    public void close() {
        dispose();
    }

    private void releaseSource() {
        if (released.compareAndSet(false, true)) super.dispose();
    }

    interface Backend extends AutoCloseable {
        void initialize();
        void pump(CustomAudioSource source);
        void dispose();
        default void close() { dispose(); }
    }

    // Open, poll and close on the same thread (required by WASAPI's COM objects).
    private static final class Capture extends DisposableNativeObject implements Backend {
        public native void initialize();
        public native void pump(CustomAudioSource source);
        public native void dispose();
    }
}
