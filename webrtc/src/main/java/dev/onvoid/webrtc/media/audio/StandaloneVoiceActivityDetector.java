/*
 * Copyright 2021 Alex Andres
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

import java.util.Objects;

import dev.onvoid.webrtc.internal.DisposableNativeObject;
import dev.onvoid.webrtc.internal.NativeLoader;

/**
 * Stateful WebRTC voice activity detector for mono signed 16-bit PCM samples.
 * Accepts 10, 20 or 30 ms frames at 8000, 16000, 32000 or 48000 Hz. Each instance
 * belongs to one continuous audio stream. Calls are serialized, including disposal;
 * callers must still supply frames in stream order and avoid modifying samples
 * during a call. Changing the sample rate or starting a new stream requires reset.
 * Call {@link #dispose()} when finished. No peer connection factory is required.
 * For the combined pitch-based probability estimator, use {@link VoiceActivityDetector}.
 * Each class maintains its own stream history.
 */
public final class StandaloneVoiceActivityDetector extends DisposableNativeObject {

    static {
        try {
            NativeLoader.loadLibrary("webrtc-java");
        }
        catch (Exception e) {
            throw new RuntimeException("Load library 'webrtc-java' failed", e);
        }
    }

    /** Higher aggressiveness rejects more noise but can miss more speech. */
    public enum Mode {
        /** Default quality mode. */
        QUALITY(0),
        /** Low bitrate mode. */
        LOW_BITRATE(1),
        /** Aggressive mode. */
        AGGRESSIVE(2),
        /** Most aggressive mode. */
        VERY_AGGRESSIVE(3);

        private final int value;

        Mode(int value) {
            this.value = value;
        }
    }

    private Mode mode;

    /** Creates a detector in {@link Mode#QUALITY} mode. */
    public StandaloneVoiceActivityDetector() {
        this(Mode.QUALITY);
    }

    /**
     * Creates a detector with the given aggressiveness.
     * @param mode detection mode, never null.
     */
    public StandaloneVoiceActivityDetector(Mode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
        initialize(mode.value);
    }

    /**
     * Analyzes one frame in stream order. Samples are numeric PCM values, so no
     * byte order applies. This method performs no mixing or resampling.
     * @param samples one mono frame of signed 16-bit PCM.
     * @param sampleRate sample rate in Hz.
     * @return true if WebRTC classifies the frame as speech; no confidence score
     *         is available. Decisions include WebRTC's built-in hangover.
     * @throws NullPointerException if samples is null.
     * @throws IllegalArgumentException if the rate or frame length is unsupported.
     * @throws IllegalStateException if the detector has been disposed.
     */
    public synchronized boolean isSpeech(short[] samples, int sampleRate) {
        Objects.requireNonNull(samples, "samples");
        if (sampleRate != 8000 && sampleRate != 16000 && sampleRate != 32000 && sampleRate != 48000) {
            throw new IllegalArgumentException("Unsupported sample rate: " + sampleRate);
        }
        int tenMs = sampleRate / 100;
        if (samples.length != tenMs && samples.length != tenMs * 2 && samples.length != tenMs * 3) {
            throw new IllegalArgumentException("Expected a mono frame of 10, 20 or 30 ms");
        }
        return processInternal(samples, sampleRate);
    }

    /**
     * Changes aggressiveness without clearing stream history.
     * @param mode detection mode, never null.
     * @throws IllegalStateException if disposed.
     */
    public synchronized void setMode(Mode mode) {
        Objects.requireNonNull(mode, "mode");
        setModeInternal(mode.value);
        this.mode = mode;
    }

    /**
     * Clears stream history while preserving the selected mode.
     * @throws IllegalStateException if disposed.
     */
    public synchronized void reset() {
        resetInternal(mode.value);
    }

    /** Frees native resources. Repeated calls are harmless. */
    @Override
    public synchronized native void dispose();

    private native void initialize(int mode);
    private native boolean processInternal(short[] samples, int sampleRate);
    private native void setModeInternal(int mode);
    private native void resetInternal(int mode);
}
