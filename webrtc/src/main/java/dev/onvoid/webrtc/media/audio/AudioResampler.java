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
import dev.onvoid.webrtc.internal.NativeLoader;

/**
 * Audio sampling rate converter. This resampler operates on audio frames of 10
 * milliseconds. Each sample is assumed to be a 16-bit PCM sample.
 *
 * @author Alex Andres
 */
public class AudioResampler extends DisposableNativeObject {

	/** The most samples of one channel in 10 ms that WebRTC resamples, at 384 kHz. */
	private static final int MAX_SAMPLES_PER_CHANNEL = 3840;

	/** The most channels that WebRTC resamples at once. */
	private static final int MAX_CHANNELS = 8;

	/** Bytes of a 16-bit PCM sample. */
	private static final int BYTES_PER_SAMPLE = 2;

	static {
		try {
			NativeLoader.loadLibrary("webrtc-java");
		}
		catch (Exception e) {
			throw new RuntimeException("Load library 'webrtc-java' failed", e);
		}
	}

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
		int srcSamplesPerChannel = getSamplesPerChannel(sourceSampleRate);
		int dstSamplesPerChannel = getSamplesPerChannel(targetSampleRate);

		checkSamplesPerChannel(srcSamplesPerChannel, "source");
		checkSamplesPerChannel(dstSamplesPerChannel, "destination");
		checkChannels(channels);

		initialize(srcSamplesPerChannel, dstSamplesPerChannel, channels);
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
	 * @return The number of converted audio samples per channel.
	 *
	 * @throws IllegalArgumentException if a sample count or the channel count is out of range, or if
	 *                                  an array holds fewer than {@code samplesPerChannel * channels}
	 *                                  samples.
	 */
	public int resample(byte[] samplesIn, int srcSamplesPerChannel, byte[] samplesOut, int dstSamplesPerChannel,
						int channels) {
		requireNonNull(samplesIn);
		requireNonNull(samplesOut);

		checkSamplesPerChannel(srcSamplesPerChannel, "source");
		checkSamplesPerChannel(dstSamplesPerChannel, "destination");
		checkChannels(channels);
		checkLength(samplesIn, srcSamplesPerChannel, channels, "input");
		checkLength(samplesOut, dstSamplesPerChannel, channels, "output");

		return resampleInternal(samplesIn, srcSamplesPerChannel, samplesOut, dstSamplesPerChannel, channels);
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

	private static void checkSamplesPerChannel(int samplesPerChannel, String name) {
		if (samplesPerChannel <= 0 || samplesPerChannel > MAX_SAMPLES_PER_CHANNEL) {
			throw new IllegalArgumentException("Invalid number of " + name + " samples per channel: "
					+ samplesPerChannel + ", must be between 1 and " + MAX_SAMPLES_PER_CHANNEL);
		}
	}

	private static void checkChannels(int channels) {
		if (channels <= 0 || channels > MAX_CHANNELS) {
			throw new IllegalArgumentException("Invalid number of channels: " + channels
					+ ", must be between 1 and " + MAX_CHANNELS);
		}
	}

	private static void checkLength(byte[] samples, int samplesPerChannel, int channels, String name) {
		// Both counts are bounded, so this cannot overflow.
		final int required = samplesPerChannel * channels * BYTES_PER_SAMPLE;

		if (samples.length < required) {
			throw new IllegalArgumentException("Insufficient samples " + name + " length: need "
					+ required + " bytes, but was " + samples.length);
		}
	}
}
