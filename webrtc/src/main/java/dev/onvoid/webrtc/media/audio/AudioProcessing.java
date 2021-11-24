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

import dev.onvoid.webrtc.internal.DisposableNativeObject;
import dev.onvoid.webrtc.internal.NativeLoader;

/**
 * AudioProcessing provides a collection of voice processing components designed
 * for real-time communications software. Accepts only linear PCM audio in
 * chunks of 10 milliseconds.
 *
 * @author Alex Andres
 */
public class AudioProcessing extends DisposableNativeObject {

	static {
		try {
			NativeLoader.loadLibrary("webrtc-java");
		}
		catch (Exception e) {
			throw new RuntimeException("Load library 'webrtc-java' failed", e);
		}
	}


	/** Cached statistics object to avoid recreation. */
	private final AudioProcessingStats stats = new AudioProcessingStats();


	/**
	 * Creates a new instance of {@code AudioProcessing}. Make sure to call
	 * {@link #dispose()} to release resources when finished processing.
	 */
	public AudioProcessing() {
		initialize();
	}

	/**
	 * Get the audio processing statistics.
	 *
	 * @return The audio processing statistics.
	 */
	public AudioProcessingStats getStatistics() {
		updateStats();

		return stats;
	}

	/**
	 * Calculates the buffer size in bytes for the destination buffer used in
	 * {@link #processStream} and {@link #processReverseStream}.
	 *
	 * @param inputConfig  The config that describes the audio input format.
	 * @param outputConfig The config that describes the desired audio output
	 *                     format.
	 *
	 * @return The target buffer size in bytes.
	 */
	public int getTargetBufferSize(AudioProcessingStreamConfig inputConfig,
			AudioProcessingStreamConfig outputConfig) {
		int nSamplesIn = inputConfig.sampleRate / 100;
		int nSamplesOut = outputConfig.sampleRate / 100;

		return Math.max(nSamplesIn, nSamplesOut) * outputConfig.channels * 2;
	}

	/**
	 * Set the {@link AudioProcessingConfig} to enable/disable processing
	 * effects. Should be called prior processing, during processing may cause
	 * undesired effects and affect the audio quality.
	 *
	 * @param config The config to setup audio processing.
	 */
	public native void applyConfig(AudioProcessingConfig config);

	/**
	 * This must be called if and only if echo processing is enabled.
	 * <p>
	 * Sets the delay in milliseconds between {@link #processReverseStream}
	 * receiving a far-end frame and {@link #processStream} receiving a near-end
	 * frame containing the corresponding echo.
	 * <p>
	 * On the client-side this can be expressed as {@code delay = (t_render -
	 * t_analyze) + (t_process - t_capture)}.
	 * <p>
	 * where,
	 * <p>
	 * - t_analyze is the time a frame is passed to {@link
	 * #processReverseStream} and t_render is the time the first sample of the
	 * same frame is rendered by the audio hardware.
	 * <p>
	 * - t_capture is the time the first sample of a frame is captured by the
	 * audio hardware and t_process is the time the same frame is passed to
	 * {@link #processStream}.
	 *
	 * @param delay The new stream delay in milliseconds.
	 */
	public native void setStreamDelayMs(int delay);

	/**
	 * This must be called if and only if echo processing is enabled.
	 *
	 * @return The stream delay in milliseconds.
	 *
	 * @see #setStreamDelayMs(int)
	 */
	public native int getStreamDelayMs();

	/**
	 * Accepts and produces a 10 ms frame interleaved 16-bit PCM audio as
	 * specified in {@code inputConfig} and {@code outputConfig}. {@code src}
	 * and {@code dest} may use the same memory, if desired.
	 *
	 * @param src          The input audio samples to process.
	 * @param inputConfig  The config that describes the audio input format.
	 * @param outputConfig The config that describes the desired audio output
	 *                     format.
	 * @param dest         The target buffer for processed audio samples.
	 *
	 * @return The success/error code. 0 if processed successfully.
	 */
	public native int processStream(byte[] src,
			AudioProcessingStreamConfig inputConfig,
			AudioProcessingStreamConfig outputConfig, byte[] dest);

	/**
	 * Accepts and produces a 10 ms frame of interleaved 16-bit PCM audio for
	 * the reverse direction audio stream as specified in {@code inputConfig}
	 * and {@code outputConfig}. {@code src} and {@code dest} may use the same
	 * memory, if desired.
	 *
	 * @param src          The input audio samples to process.
	 * @param inputConfig  The config that describes the audio input format.
	 * @param outputConfig The config that describes the desired audio output
	 *                     format.
	 * @param dest         The target buffer for processed audio samples.
	 *
	 * @return The success/error code. 0 if processed successfully.
	 */
	public native int processReverseStream(byte[] src,
			AudioProcessingStreamConfig inputConfig,
			AudioProcessingStreamConfig outputConfig, byte[] dest);

	@Override
	public native void dispose();

	private native void initialize();

	private native void updateStats();

}
