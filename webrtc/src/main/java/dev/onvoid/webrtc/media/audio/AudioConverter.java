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

import static java.util.Objects.requireNonNull;

import dev.onvoid.webrtc.internal.DisposableNativeObject;

/**
 * Audio format converter to remix and resample audio input data. This converter
 * operates on audio frames of 10 milliseconds. Each sample is assumed to be a
 * 16-bit PCM sample.
 *
 * @author Alex Andres
 */
public class AudioConverter extends DisposableNativeObject {

	/** The highest sampling frequency that WebRTC converts. */
	private static final int MAX_SAMPLE_RATE = 384000;

	/** The most channels that WebRTC converts. */
	private static final int MAX_CHANNELS = 8;

	/** The number of samples over all channels in a 10 ms frame for the input audio. */
	private final int srcSamples;

	/** The number of samples over all channels in a 10 ms frame for the output audio. */
	private final int dstSamples;


	/**
	 * Creates a new {@code AudioConverter} with specified sampling frequency
	 * and channel parameters.
	 *
	 * @param srcSampleRate The sampling frequency of the input signal.
	 * @param srcChannels   The number of audio channels of the input signal.
	 * @param dstSampleRate The sampling frequency of the output signal.
	 * @param dstChannels   The number of audio channels of the output signal.
	 *
	 * @throws IllegalArgumentException if a sampling frequency is not between
	 *                                  100 Hz and 384 kHz, or a channel count
	 *                                  not between 1 and 8.
	 */
	public AudioConverter(int srcSampleRate, int srcChannels, int dstSampleRate,
			int dstChannels) {
		checkSampleRate(srcSampleRate, "source");
		checkSampleRate(dstSampleRate, "destination");
		checkChannels(srcChannels, "source");
		checkChannels(dstChannels, "destination");

		this.srcSamples = srcSampleRate / 100 * srcChannels;
		this.dstSamples = dstSampleRate / 100 * dstChannels;

		initialize(srcSampleRate, srcChannels, dstSampleRate, dstChannels);
	}

	/**
	 * Calculates the buffer size in bytes for the destination buffer used in
	 * {@link #convert}.
	 *
	 * @return The target buffer size in bytes.
	 */
	public int getTargetBufferSize() {
		return dstSamples * 2;
	}

	/**
	 * Converts the input samples into the output sample buffer with the
	 * sampling frequency and channel layout specified in the constructor. The
	 * audio input must be of the length of 10 milliseconds. Accordingly, the
	 * output has the same length of 10 milliseconds.
	 *
	 * @param src The audio samples to convert.
	 * @param dst The output buffer for converted audio samples.
	 *
	 * @return The number of converted samples over all channels.
	 *
	 * @throws IllegalArgumentException if the buffer sizes do not match the
	 *                                  frame sizes.
	 */
	public int convert(byte[] src, byte[] dst) {
		requireNonNull(src);
		requireNonNull(dst);

		if (src.length / 2 < srcSamples) {
			throw new IllegalArgumentException(String.format(
					"Insufficient samples input length: %d vs. %d",
					src.length / 2, srcSamples));
		}
		if (dst.length / 2 < dstSamples) {
			throw new IllegalArgumentException(String.format(
					"Insufficient samples output length: %d vs. %d",
					dst.length / 2, dstSamples));
		}

		convertInternal(src, srcSamples, dst, dstSamples);

		return dstSamples;
	}

	@Override
	public native void dispose();

	private native void initialize(int srcSampleRate, int srcChannels, int dstSampleRate, int dstChannels);

	private native void convertInternal(byte[] src, int nSrcSamples, byte[] dst, int nDstSamples);

	private static void checkSampleRate(int sampleRate, String name) {
		if (sampleRate < 100 || sampleRate > MAX_SAMPLE_RATE) {
			throw new IllegalArgumentException("Invalid " + name + " sampling frequency: "
					+ sampleRate + ", must be between 100 and " + MAX_SAMPLE_RATE);
		}
	}

	private static void checkChannels(int channels, String name) {
		if (channels <= 0 || channels > MAX_CHANNELS) {
			throw new IllegalArgumentException("Invalid number of " + name + " channels: "
					+ channels + ", must be between 1 and " + MAX_CHANNELS);
		}
	}

}
