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

package dev.onvoid.webrtc.media.player;

/**
 * What a media source contains: how long it runs, and the format of the video
 * and audio streams that will be played from it.
 * <p>
 * A stream that is absent is reported as a zero size, frame rate, sample rate
 * or channel count, so {@link #hasVideo()} and {@link #hasAudio()} are the
 * questions to ask before reading the rest.
 *
 * @author Alex Andres
 */
public class MediaInfo {

	/** How long the source runs in microseconds, or 0 if it is not known. */
	private final long durationUs;

	/** The width of the video in pixels, or 0 if there is no video. */
	private final int videoWidth;

	/** The height of the video in pixels, or 0 if there is no video. */
	private final int videoHeight;

	/** The frame rate of the video, or 0 if there is no video. */
	private final double frameRate;

	/** The name of the video codec, or {@code null} if there is no video. */
	private final String videoCodec;

	/** The sample rate of the audio in Hz, or 0 if there is no audio. */
	private final int sampleRate;

	/** The channel count of the audio, or 0 if there is no audio. */
	private final int channels;

	/** The name of the audio codec, or {@code null} if there is no audio. */
	private final String audioCodec;


	/**
	 * Creates media information. Called by native code once a source has been
	 * opened and its streams have been examined.
	 *
	 * @param durationUs  How long the source runs in microseconds.
	 * @param videoWidth  The width of the video in pixels.
	 * @param videoHeight The height of the video in pixels.
	 * @param frameRate   The frame rate of the video.
	 * @param videoCodec  The name of the video codec.
	 * @param sampleRate  The sample rate of the audio in Hz.
	 * @param channels    The channel count of the audio.
	 * @param audioCodec  The name of the audio codec.
	 */
	public MediaInfo(long durationUs, int videoWidth, int videoHeight,
			double frameRate, String videoCodec, int sampleRate, int channels,
			String audioCodec) {
		this.durationUs = durationUs;
		this.videoWidth = videoWidth;
		this.videoHeight = videoHeight;
		this.frameRate = frameRate;
		this.videoCodec = videoCodec;
		this.sampleRate = sampleRate;
		this.channels = channels;
		this.audioCodec = audioCodec;
	}

	/**
	 * Returns how long the source runs, in microseconds. Live streams and some
	 * containers do not say, in which case this is {@code 0}.
	 *
	 * @return The duration in microseconds, or {@code 0} if it is not known.
	 */
	public long getDurationUs() {
		return durationUs;
	}

	/**
	 * Returns whether the source has a video stream that can be played.
	 *
	 * @return True if there is video.
	 */
	public boolean hasVideo() {
		return videoWidth > 0 && videoHeight > 0;
	}

	/**
	 * Returns the width of the video in pixels.
	 *
	 * @return The width, or {@code 0} if there is no video.
	 */
	public int getVideoWidth() {
		return videoWidth;
	}

	/**
	 * Returns the height of the video in pixels.
	 *
	 * @return The height, or {@code 0} if there is no video.
	 */
	public int getVideoHeight() {
		return videoHeight;
	}

	/**
	 * Returns the frame rate of the video. For a variable frame rate source
	 * this is the average the container reports.
	 *
	 * @return The frame rate, or {@code 0} if there is no video.
	 */
	public double getFrameRate() {
		return frameRate;
	}

	/**
	 * Returns the name of the video codec, as FFmpeg names it, for example
	 * {@code h264} or {@code vp9}.
	 *
	 * @return The codec name, or {@code null} if there is no video.
	 */
	public String getVideoCodec() {
		return videoCodec;
	}

	/**
	 * Returns whether the source has an audio stream that can be played.
	 *
	 * @return True if there is audio.
	 */
	public boolean hasAudio() {
		return sampleRate > 0 && channels > 0;
	}

	/**
	 * Returns the sample rate of the audio in Hz, as it is in the source. The
	 * audio is resampled before it reaches WebRTC, so this is not necessarily
	 * the rate that is sent.
	 *
	 * @return The sample rate, or {@code 0} if there is no audio.
	 */
	public int getSampleRate() {
		return sampleRate;
	}

	/**
	 * Returns the channel count of the audio, as it is in the source.
	 *
	 * @return The channel count, or {@code 0} if there is no audio.
	 */
	public int getChannels() {
		return channels;
	}

	/**
	 * Returns the name of the audio codec, as FFmpeg names it, for example
	 * {@code aac} or {@code opus}.
	 *
	 * @return The codec name, or {@code null} if there is no audio.
	 */
	public String getAudioCodec() {
		return audioCodec;
	}

	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder("MediaInfo[");

		builder.append(durationUs / 1_000_000.0).append("s");

		if (hasVideo()) {
			builder.append(", video ").append(videoWidth).append("x")
					.append(videoHeight).append(" @ ").append(frameRate)
					.append(" ").append(videoCodec);
		}
		if (hasAudio()) {
			builder.append(", audio ").append(sampleRate).append(" Hz ")
					.append(channels).append(" ch ").append(audioCodec);
		}

		return builder.append("]").toString();
	}

}
