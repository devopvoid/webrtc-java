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
 * milliseconds.
 *
 * @author Alex Andres
 */
public class AudioResampler extends DisposableNativeObject {

	private final int sourceFrames;

	private final int targetFrames;


	/**
	 * Creates a new {@code AudioResampler} with specified sampling frequency
	 * parameters.
	 *
	 * @param sourceSampleRate The sampling frequency of the input signal.
	 * @param targetSampleRate The sampling frequency of the output signal.
	 */
	public AudioResampler(int sourceSampleRate, int targetSampleRate) {
		// 10 ms frames
		sourceFrames = sourceSampleRate / 100;
		targetFrames = targetSampleRate / 100;

		initialize(sourceFrames, targetFrames);
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

		final int arraySamplesIn = samplesIn.length / 2;
		final int maxSamplesOut = samplesOut.length / 2;

		nSamplesIn = Math.min(arraySamplesIn, Math.max(0, nSamplesIn));

		if (targetFrames > maxSamplesOut) {
			throw new IllegalArgumentException("Insufficient samples output length");
		}

		return resampleInternal(samplesIn, nSamplesIn, samplesOut, maxSamplesOut);
	}

	@Override
	public native void dispose();

	private native void initialize(int sourceFrames, int targetFrames);

	private native int resampleInternal(byte[] samplesIn, int nSamplesIn,
			byte[] samplesOut, int maxSamplesOut);

}
