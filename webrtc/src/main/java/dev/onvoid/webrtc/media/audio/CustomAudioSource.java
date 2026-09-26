/*
 * Copyright 2019 Alex Andres
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

import dev.onvoid.webrtc.media.SyncClock;

/**
 * Custom implementation of an audio source for WebRTC that allows pushing audio data
 * from external sources directly to the WebRTC audio pipeline.
 *
 * @author Alex Andres
 */
public class CustomAudioSource extends AudioTrackSource {

	/** The only sample format WebRTC accepts from a source. */
	private static final int BITS_PER_SAMPLE = 16;

	/**
	 * The most samples, counting every channel, that one pushed chunk may hold.
	 * WebRTC copies a chunk into a fixed-size audio frame, so this mirrors
	 * {@code AudioFrame::kMaxDataSizeSamples} in the native library.
	 */
	public static final int MAX_SAMPLES_PER_PUSH = 7680;

	/**
	 * The most channels one pushed chunk may hold. Mirrors
	 * {@code kMaxNumberOfAudioChannels} in the native library.
	 */
	public static final int MAX_CHANNELS = 24;


	/**
	 * Constructs a new CustomAudioSource instance.
	 */
	public CustomAudioSource() {
		super();

		initialize();
	}

	/**
	 * Constructs a new CustomAudioSource instance with a specified SyncClock.
	 *
	 * @param clock The SyncClock to use for timing and synchronization.
	 */
	public CustomAudioSource(SyncClock clock) {
		super();

		initializeWithClock(clock);
	}

	/**
	 * Pushes audio data to be processed by this audio source. The data is
	 * handed to the source's sinks on the calling thread, so it must be pushed
	 * from one thread at a time.
	 * <p>
	 * The samples must be 16-bit signed PCM in the platform byte order, with
	 * the channels interleaved. A call carries one chunk of audio, for which
	 * 10 ms is the size WebRTC works with; at most
	 * {@value #MAX_SAMPLES_PER_PUSH} samples, counting every channel, fit in
	 * one chunk.
	 *
	 * @param audioData       The raw audio data bytes to process. Must hold at
	 *                        least {@code frameCount * channels * 2} bytes.
	 * @param bits_per_sample The number of bits per sample, which must be 16.
	 * @param sampleRate      The sample rate of the audio in Hz (e.g., 44100, 48000).
	 * @param channels        The number of audio channels (1 for mono, 2 for stereo).
	 * @param frameCount      The number of frames in the provided audio data. A
	 *                        frame holds one sample for each channel, so 10 ms
	 *                        at 48 kHz is 480 frames whatever the channel count.
	 *
	 * @throws NullPointerException     If the audio data is {@code null}.
	 * @throws IllegalArgumentException If the audio format is not 16-bit PCM,
	 *                                  if a value is not positive, if the chunk
	 *                                  is larger than WebRTC can take, or if the
	 *                                  array is too short for the frames it is
	 *                                  said to hold.
	 */
	public void pushAudio(byte[] audioData, int bits_per_sample, int sampleRate,
						  int channels, int frameCount) {
		validate(audioData, bits_per_sample, sampleRate, channels, frameCount);

		pushAudioInternal(audioData, bits_per_sample, sampleRate, channels,
				frameCount);
	}

	/**
	 * Pushes audio data that was captured at the given time. Apart from the
	 * timestamp this behaves exactly like
	 * {@link #pushAudio(byte[], int, int, int, int)}.
	 * <p>
	 * A source that knows its own timing, such as one playing a media file,
	 * should use this so that the audio lines up with video pushed with
	 * matching timestamps. Chunks must still be pushed in real time.
	 *
	 * @param audioData       The raw audio data bytes to process. Must hold at
	 *                        least {@code frameCount * channels * 2} bytes.
	 * @param bits_per_sample The number of bits per sample, which must be 16.
	 * @param sampleRate      The sample rate of the audio in Hz (e.g., 44100, 48000).
	 * @param channels        The number of audio channels (1 for mono, 2 for stereo).
	 * @param frameCount      The number of frames in the provided audio data.
	 * @param timestampUs     The capture time of the chunk, on the clock of
	 *                        {@link dev.onvoid.webrtc.media.SyncClock#currentTimeUs()}.
	 *
	 * @throws NullPointerException     If the audio data is {@code null}.
	 * @throws IllegalArgumentException If the audio format is not 16-bit PCM,
	 *                                  if a value is not positive, if the chunk
	 *                                  is larger than WebRTC can take, or if the
	 *                                  array is too short for the frames it is
	 *                                  said to hold.
	 *
	 * @see dev.onvoid.webrtc.media.SyncClock#currentTimeUs()
	 */
	public void pushAudio(byte[] audioData, int bits_per_sample, int sampleRate,
						  int channels, int frameCount, long timestampUs) {
		validate(audioData, bits_per_sample, sampleRate, channels, frameCount);

		pushAudioTimestamped(audioData, bits_per_sample, sampleRate, channels,
				frameCount, timestampUs);
	}

	/**
	 * Checks that a chunk of audio is something WebRTC can take, so that a bad
	 * chunk is rejected here instead of reading past the end of the array or
	 * aborting the process in native code.
	 *
	 * @param audioData       The raw audio data bytes to process.
	 * @param bits_per_sample The number of bits per sample, which must be 16.
	 * @param sampleRate      The sample rate of the audio in Hz.
	 * @param channels        The number of audio channels.
	 * @param frameCount      The number of frames in the provided audio data.
	 *
	 * @throws NullPointerException     If the audio data is {@code null}.
	 * @throws IllegalArgumentException If any value is out of range.
	 */
	private static void validate(byte[] audioData, int bits_per_sample,
								 int sampleRate, int channels, int frameCount) {
		requireNonNull(audioData, "audioData must not be null");

		if (bits_per_sample != BITS_PER_SAMPLE) {
			throw new IllegalArgumentException(String.format(
					"Audio must be %d-bit PCM, got %d bits per sample",
					BITS_PER_SAMPLE, bits_per_sample));
		}
		if (sampleRate <= 0) {
			throw new IllegalArgumentException(
					"Sample rate must be positive, got " + sampleRate);
		}
		if (channels <= 0 || channels > MAX_CHANNELS) {
			throw new IllegalArgumentException(String.format(
					"Channel count must be between 1 and %d, got %d",
					MAX_CHANNELS, channels));
		}
		if (frameCount <= 0) {
			throw new IllegalArgumentException(
					"Frame count must be positive, got " + frameCount);
		}

		// WebRTC copies the chunk into a fixed-size audio frame and aborts the
		// process if it does not fit, so reject an oversized chunk here.
		long samples = (long) frameCount * channels;

		if (samples > MAX_SAMPLES_PER_PUSH) {
			throw new IllegalArgumentException(String.format(
					"A chunk holds at most %d samples across all channels, got %d "
							+ "(%d frames x %d channels). Push shorter chunks, 10 ms each.",
					MAX_SAMPLES_PER_PUSH, samples, frameCount, channels));
		}

		// Native code reads this many bytes out of the array, whatever its size.
		long required = samples * (BITS_PER_SAMPLE / 8);

		if (audioData.length < required) {
			throw new IllegalArgumentException(String.format(
					"Audio data holds %d bytes, but %d frames of %d channels need %d",
					audioData.length, frameCount, channels, required));
		}
	}

	/**
	 * Disposes of any native resources held by this audio source.
	 * This method should be called when the audio source is no longer needed
	 * to prevent memory leaks.
	 */
	public native void dispose();

	/**
	 * Hands the validated audio data to the native source.
	 *
	 * @param audioData       The raw audio data bytes to process.
	 * @param bits_per_sample The number of bits per sample.
	 * @param sampleRate      The sample rate of the audio in Hz.
	 * @param channels        The number of audio channels.
	 * @param frameCount      The number of frames in the provided audio data.
	 */
	private native void pushAudioInternal(byte[] audioData, int bits_per_sample,
										  int sampleRate, int channels,
										  int frameCount);

	/**
	 * Hands the validated audio data to the native source, stamped with the
	 * given capture time.
	 *
	 * @param audioData       The raw audio data bytes to process.
	 * @param bits_per_sample The number of bits per sample.
	 * @param sampleRate      The sample rate of the audio in Hz.
	 * @param channels        The number of audio channels.
	 * @param frameCount      The number of frames in the provided audio data.
	 * @param timestampUs     The capture time of the chunk in microseconds.
	 */
	private native void pushAudioTimestamped(byte[] audioData, int bits_per_sample,
											 int sampleRate, int channels,
											 int frameCount, long timestampUs);

	/**
	 * Initializes the native resources required by this audio source.
	 */
	private native void initialize();

	/**
	 * Initializes the native resources required by this audio source with a specified SyncClock.
	 *
	 * @param clock The SyncClock to use for timing and synchronization.
	 */
	private native void initializeWithClock(SyncClock clock);

}
