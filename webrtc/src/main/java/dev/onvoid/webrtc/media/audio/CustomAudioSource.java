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
	 * The lowest sample rate a chunk may have. WebRTC resamples what it sends
	 * to the rate of the encoder, and aborts the process when the rate is too
	 * far below that.
	 */
	public static final int MIN_SAMPLE_RATE = 8000;

	/**
	 * The highest sample rate a chunk may have. Mirrors
	 * {@code kMaxInputSampleRateHz} of the audio coding module in the native
	 * library.
	 */
	public static final int MAX_SAMPLE_RATE = 192000;

	/**
	 * The most channels one pushed chunk may hold. The audio coding module in
	 * the native library takes 1, 2, 4, 6 or 8 channels.
	 */
	public static final int MAX_CHANNELS = 8;


	/**
	 * Constructs a new CustomAudioSource instance.
	 */
	public CustomAudioSource() {
		super();

		initialize();
	}

	/**
	 * Constructs a new CustomAudioSource instance with a specified SyncClock.
	 * The source shares the clock, which may therefore be disposed before
	 * this source.
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
	 * the channels interleaved. A call carries one chunk of exactly 10 ms,
	 * {@code sampleRate / 100} frames, of 1, 2, 4, 6 or 8 channels: a sender
	 * drops anything else without a trace, so it is rejected here. At most
	 * {@value #MAX_SAMPLES_PER_PUSH} samples, counting every channel, fit in
	 * one chunk.
	 * <p>
	 * The chunk is treated as captured at the moment of the call, and stamped
	 * with {@link dev.onvoid.webrtc.media.SyncClock#currentTimeUs()}, the clock
	 * of {@link #pushAudio(byte[], int, int, int, int, long)}, so the two may
	 * be mixed on one source.
	 *
	 * @param audioData       The raw audio data bytes to process. Must hold at
	 *                        least {@code frameCount * channels * 2} bytes.
	 * @param bits_per_sample The number of bits per sample, which must be 16.
	 * @param sampleRate      The sample rate of the audio in Hz (e.g., 44100, 48000),
	 *                        from {@value #MIN_SAMPLE_RATE} to {@value #MAX_SAMPLE_RATE}.
	 * @param channels        The number of audio channels: 1, 2, 4, 6 or 8.
	 * @param frameCount      The number of frames in the provided audio data,
	 *                        which must be {@code sampleRate / 100} with a rate divisible by 100. A frame
	 *                        holds one sample for each channel, so 10 ms at
	 *                        48 kHz is 480 frames whatever the channel count.
	 *
	 * @throws NullPointerException     If the audio data is {@code null}.
	 * @throws IllegalArgumentException If the audio format is not 16-bit PCM,
	 *                                  if the sample rate or channel count is
	 *                                  not one WebRTC can send, if the chunk is
	 *                                  not 10 ms long or larger than WebRTC can
	 *                                  take, or if the array is too short for
	 *                                  the frames it is said to hold.
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
	 * @param sampleRate      The sample rate of the audio in Hz (e.g., 44100, 48000),
	 *                        from {@value #MIN_SAMPLE_RATE} to {@value #MAX_SAMPLE_RATE}.
	 * @param channels        The number of audio channels: 1, 2, 4, 6 or 8.
	 * @param frameCount      The number of frames in the provided audio data,
	 *                        which must be {@code sampleRate / 100} with a rate divisible by 100.
	 * @param timestampUs     The capture time of the chunk, on the clock of
	 *                        {@link dev.onvoid.webrtc.media.SyncClock#currentTimeUs()}.
	 *
	 * @throws NullPointerException     If the audio data is {@code null}.
	 * @throws IllegalArgumentException If the audio format is not 16-bit PCM,
	 *                                  if the sample rate or channel count is
	 *                                  not one WebRTC can send, if the chunk is
	 *                                  not 10 ms long or larger than WebRTC can
	 *                                  take, or if the array is too short for
	 *                                  the frames it is said to hold.
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
	 * Checks that a chunk of audio is something WebRTC can send, so that a bad
	 * chunk is rejected here instead of reading past the end of the array,
	 * aborting the process in native code, or being dropped by the encoder
	 * while the push appears to succeed.
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
		if (sampleRate < MIN_SAMPLE_RATE || sampleRate > MAX_SAMPLE_RATE || sampleRate % 100 != 0) {
			throw new IllegalArgumentException(String.format(
					"Sample rate must be between %d and %d Hz and divisible by 100, got %d",
					MIN_SAMPLE_RATE, MAX_SAMPLE_RATE, sampleRate));
		}
		if (channels != 1 && (channels <= 0 || channels > MAX_CHANNELS || channels % 2 != 0)) {
			throw new IllegalArgumentException(
					"Channel count must be 1, 2, 4, 6 or 8, got " + channels);
		}

		// The encoder takes 10 ms at a time and drops any other size, which
		// the push cannot tell since encoding happens on another thread.
		int framesPer10Ms = sampleRate / 100;

		if (frameCount != framesPer10Ms) {
			throw new IllegalArgumentException(String.format(
					"A chunk holds exactly 10 ms of audio, %d frames at %d Hz, got %d frames",
					framesPer10Ms, sampleRate, frameCount));
		}

		// WebRTC copies the chunk into a fixed-size audio frame and aborts the
		// process if it does not fit, so reject an oversized chunk here.
		long samples = (long) frameCount * channels;

		if (samples > MAX_SAMPLES_PER_PUSH) {
			throw new IllegalArgumentException(String.format(
					"A chunk holds at most %d samples across all channels, got %d "
							+ "(%d frames x %d channels). Use fewer channels or a lower sample rate.",
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
