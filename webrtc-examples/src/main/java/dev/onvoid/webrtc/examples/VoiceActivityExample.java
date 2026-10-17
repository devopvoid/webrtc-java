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

package dev.onvoid.webrtc.examples;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;
import java.util.Objects;

import dev.onvoid.webrtc.media.audio.AudioConverter;
import dev.onvoid.webrtc.media.audio.AudioTrackSink;
import dev.onvoid.webrtc.media.audio.StandaloneVoiceActivityDetector;

/**
 * Attach to an AudioTrack with addSink(), then removeSink() before close().
 * Buffers arbitrary callback lengths into 10 ms frames and converts interleaved
 * signed 16-bit PCM to 16 kHz mono, including input at 44.1 kHz. The consumer runs
 * on the audio callback thread and should return promptly. It receives raw frame
 * decisions; a UI should apply its own speaking-state timing off this thread.
 */
public final class VoiceActivityExample implements AudioTrackSink, AutoCloseable {

    private final Consumer<Boolean> onDecision;
    private final StandaloneVoiceActivityDetector detector;
    private AudioConverter converter;
    private int rate;
    private int channels;
    private byte[] pending;
    private int buffered;
    private final byte[] converted = new byte[320];
    private final short[] mono = new short[160];
    private final ByteBuffer convertedSamples = ByteBuffer.wrap(converted).order(ByteOrder.nativeOrder());
    private boolean closed;

    public VoiceActivityExample(Consumer<Boolean> onDecision) {
        this.onDecision = Objects.requireNonNull(onDecision, "onDecision");
        detector = new StandaloneVoiceActivityDetector(StandaloneVoiceActivityDetector.Mode.AGGRESSIVE);
    }

    @Override
    public synchronized void onData(byte[] data, int bitsPerSample, int sampleRate, int channels, int frames) {
        if (closed) {
            return;
        }
        if (bitsPerSample != 16 || sampleRate < 8000 || sampleRate > 96000 || sampleRate % 100 != 0
                || (channels != 1 && channels != 2) || frames < 0
                || (long) frames * channels * 2 != data.length) {
            throw new IllegalArgumentException("Expected interleaved 16-bit mono/stereo PCM at a whole 10 ms rate");
        }
        if (rate != sampleRate || this.channels != channels) {
            AudioConverter replacement = new AudioConverter(sampleRate, channels, 16000, 1);
            if (converter != null) {
                converter.dispose();
            }
            converter = replacement;
            rate = sampleRate;
            this.channels = channels;
            pending = new byte[sampleRate / 100 * channels * 2];
            buffered = 0;
            detector.reset();
        }
        int offset = 0;
        while (offset < data.length && !closed) {
            int count = Math.min(data.length - offset, pending.length - buffered);
            System.arraycopy(data, offset, pending, buffered, count);
            buffered += count;
            offset += count;
            if (buffered == pending.length) {
                converter.convert(pending, converted);
                convertedSamples.asShortBuffer().get(mono);
                buffered = 0;
                onDecision.accept(detector.isSpeech(mono, 16000));
            }
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            if (converter != null) {
                converter.dispose();
            }
            detector.dispose();
        }
    }
}
