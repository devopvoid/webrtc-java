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

	private int targetFrames;

	private boolean initialized;


	/**
	 * Creates a new {@code AudioResampler} without sampling parameters. Make
	 * sure to call {@link #reset(int, int, int)} afterwards.
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
		initialize();
		reset(sourceSampleRate, targetSampleRate, channels);
	}

	/**
	 * Must be called when created with default constructor or whenever the
	 * sampling parameters change. Can be called at any time as it is a no-op if
	 * parameters have not changed since the last call.
	 *
	 * @param sourceSampleRate The sampling frequency of the input signal.
	 * @param targetSampleRate The sampling frequency of the output signal.
	 * @param channels         The number of audio channels to resample.
	 */
	public void reset(int sourceSampleRate, int targetSampleRate, int channels) {
		initialized = false;

		resetInternal(sourceSampleRate, targetSampleRate, channels);

		targetFrames = targetSampleRate / 100 * channels; // 10 ms frame

		initialized = true;
	}

	/**
	 * Converts the input samples into the output samples with the sampling
	 * frequency specified in the constructor. The audio input must be of the
	 * length of 10 milliseconds. Accordingly, the output has the length of 10
	 * milliseconds.
	 *
	 * @param samplesIn  The audio samples to convert.
	 * @param nSamplesIn The number of audio samples to consider from {@code
	 *                   samplesIn}.
	 * @param samplesOut The converted audio samples.
	 *
	 * @return The number of converted audio samples.
	 */
	public int resample(byte[] samplesIn, int nSamplesIn, byte[] samplesOut) {
		requireNonNull(samplesIn);
		requireNonNull(samplesOut);

		if (!initialized) {
			throw new IllegalStateException("Not initialized: Use reset() to set parameters");
		}

		final int arraySamplesIn = samplesIn.length / 2; // 16-bit PCM sample
		final int maxSamplesOut = samplesOut.length / 2;

		nSamplesIn = Math.min(arraySamplesIn, Math.max(0, nSamplesIn));

		if (targetFrames > maxSamplesOut) {
			throw new IllegalArgumentException("Insufficient samples output length");
		}

		return resampleInternal(samplesIn, nSamplesIn, samplesOut, maxSamplesOut);
	}

	@Override
	public native void dispose();

	private native void initialize();

	private native void resetInternal(int sourceSampleRate, int targetSampleRate,
			int channels);

	private native int resampleInternal(byte[] samplesIn, int nSamplesIn,
			byte[] samplesOut, int maxSamplesOut);

}
