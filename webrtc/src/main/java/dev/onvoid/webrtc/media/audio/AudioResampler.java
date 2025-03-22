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
 * Audio sampling rate converter. This resampler operates on audio frames of 10
 * milliseconds. Each sample is assumed to be a 16-bit PCM sample.
 *
 * @author Alex Andres
 */
public class AudioResampler extends DisposableNativeObject {

	/**
	 * Creates a new {@code AudioResampler} without sampling parameters.
	 */
	public AudioResampler() {
		initialize();
	}

	/**
	 * Creates a new {@code AudioResampler} with specified sampling frequency
	 * parameters.
	 *
	 * @param sourceSampleRate The sampling frequency of the input signal.
	 * @param targetSampleRate The sampling frequency of the output signal.
	 * @param channels         The number of audio channels to resample.
	 */
	public AudioResampler(int sourceSampleRate, int targetSampleRate, int channels) {
		initialize(getSamplesPerChannel(sourceSampleRate), getSamplesPerChannel(targetSampleRate), channels);
	}

	/**
	 * Converts the input samples into the output samples with the sampling
	 * frequency specified in the constructor. The audio input must be of the
	 * length of 10 milliseconds. Accordingly, the output has the length of 10
	 * milliseconds.
	 *
	 * @param samplesIn            The audio samples to convert.
	 * @param srcSamplesPerChannel The number of samples per source channel.
	 * @param samplesOut           The converted audio samples.
	 * @param dstSamplesPerChannel The number of samples per destination channel.
	 * @param channels             The number of audio channels to resample.
	 *
	 * @return The number of converted audio samples.
	 */
	public int resample(byte[] samplesIn, int srcSamplesPerChannel, byte[] samplesOut, int dstSamplesPerChannel,
						int channels) {
		requireNonNull(samplesIn);
		requireNonNull(samplesOut);

		final int maxSamplesOut = samplesOut.length / 2; // 16-bit PCM sample

		if (dstSamplesPerChannel > maxSamplesOut) {
			throw new IllegalArgumentException("Insufficient samples output length");
		}

		return resampleInternal(samplesIn, srcSamplesPerChannel, samplesOut, maxSamplesOut, channels);
	}

	@Override
	public native void dispose();

	private native void initialize();

	private native void initialize(int srcSamplesPerChannel, int dstSamplesPerChannel, int channels);

	private native int resampleInternal(byte[] samplesIn, int srcSamplesPerChannel,
										byte[] samplesOut, int dstSamplesPerChannel, int channels);

	/**
	 * Returns the number of samples a buffer needs to hold for ~10ms of a single audio channel at a given sample rate.
	 *
	 * @param sampleRate The sample rate.
	 *
	 * @return The number of samples per channel.
	 */
	public static int getSamplesPerChannel(int sampleRate) {
		return sampleRate / 100;
	}
}
