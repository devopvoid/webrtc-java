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

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import dev.onvoid.webrtc.media.video.CustomVideoSource;

/**
 * A media file or stream as a pair of media sources, ready to make tracks
 * from.
 * <p>
 * This is the short way to do what the module is for. It opens the source,
 * makes a {@link CustomVideoSource} and a {@link CustomAudioSource} for
 * whichever streams it actually has, and wires a {@link MediaPlayer} to feed
 * them:
 * <pre>{@code
 * MediaFileSource source = new MediaFileSource(Path.of("movie.mp4"));
 *
 * VideoTrack videoTrack = factory.createVideoTrack("video", source.getVideoSource());
 * AudioTrack audioTrack = factory.createAudioTrack("audio", source.getAudioSource());
 *
 * peerConnection.addTrack(videoTrack, List.of("stream"));
 * peerConnection.addTrack(audioTrack, List.of("stream"));
 *
 * source.play();
 * }</pre>
 * <p>
 * A source with no video has no video source, and likewise for audio, so check
 * {@link #getVideoSource()} and {@link #getAudioSource()} for {@code null}
 * before making a track, or ask {@link #getInfo()} first.
 * <p>
 * A factory fed from here is sending pushed audio, so it cannot also send
 * audio captured by its {@code AudioDeviceModule}. Use a separate factory if
 * an application needs both.
 * <p>
 * Closing this releases the player and both media sources. Dispose of any
 * tracks made from them first.
 *
 * @author Alex Andres
 */
public class MediaFileSource implements AutoCloseable {

	private final MediaInfo info;

	private final CustomVideoSource videoSource;

	private final CustomAudioSource audioSource;

	private final MediaPlayer player;

	private boolean closed;


	/**
	 * Opens the media file at the given path.
	 *
	 * @param path The path of the file to play.
	 *
	 * @throws IOException if the source cannot be opened or decoded.
	 */
	public MediaFileSource(Path path) throws IOException {
		this(path.toAbsolutePath().toString());
	}

	/**
	 * Opens the given media source.
	 *
	 * @param source The path or {@code rtsp://} URL of the source to play.
	 *
	 * @throws IOException if the source cannot be opened or decoded.
	 */
	public MediaFileSource(String source) throws IOException {
		this(source, MediaReader.DEFAULT_TIMEOUT);
	}

	/**
	 * Opens the given media source, allowing each operation on it the given
	 * time before it fails, as {@link MediaReader} describes.
	 *
	 * @param source  The path or {@code rtsp://} URL of the source to play.
	 * @param timeout How long opening, and later any single read, may wait on
	 *                the source. {@link Duration#ZERO} waits as long as it
	 *                takes.
	 *
	 * @throws IOException if the source cannot be opened in time, or decoded.
	 */
	public MediaFileSource(String source, Duration timeout) throws IOException {
		MediaReader reader = new MediaReader(source, timeout);

		// Read while the reader is still ours: the player takes it over.
		info = reader.getInfo();

		videoSource = info.hasVideo() ? new CustomVideoSource() : null;
		audioSource = info.hasAudio() ? new CustomAudioSource() : null;

		try {
			player = new MediaPlayer(reader, videoSource, audioSource);
		}
		catch (IOException | RuntimeException e) {
			// The player did not take charge, so what was made here has to be
			// unwound rather than left to leak.
			disposeSources();

			reader.close();

			throw e;
		}
	}

	/**
	 * Returns what this source contains.
	 *
	 * @return The media information.
	 */
	public MediaInfo getInfo() {
		return info;
	}

	/**
	 * Returns the video source fed by this file, to make a video track from.
	 *
	 * @return The video source, or {@code null} if the source has no video.
	 */
	public CustomVideoSource getVideoSource() {
		return videoSource;
	}

	/**
	 * Returns the audio source fed by this file, to make an audio track from.
	 *
	 * @return The audio source, or {@code null} if the source has no audio.
	 */
	public CustomAudioSource getAudioSource() {
		return audioSource;
	}

	/**
	 * Returns the player driving this source, for anything beyond the methods
	 * below.
	 *
	 * @return The player.
	 */
	public MediaPlayer getPlayer() {
		return player;
	}

	/**
	 * Sets what to report playback events to.
	 *
	 * @param listener The listener, or {@code null} to stop reporting.
	 */
	public void setListener(MediaPlayerListener listener) {
		player.setListener(listener);
	}

	/**
	 * Starts or resumes playback.
	 */
	public void play() {
		player.play();
	}

	/**
	 * Holds playback where it is.
	 */
	public void pause() {
		player.pause();
	}

	/**
	 * Moves playback to the given position.
	 *
	 * @param positionUs The position in microseconds from the start.
	 */
	public void seek(long positionUs) {
		player.seek(positionUs);
	}

	/**
	 * Sets whether the source starts again when it runs out.
	 *
	 * @param looping True to play the source over and over.
	 */
	public void setLooping(boolean looping) {
		player.setLooping(looping);
	}

	/**
	 * Returns where playback has got to, in microseconds.
	 *
	 * @return The position in microseconds.
	 */
	public long getPositionUs() {
		return player.getPositionUs();
	}

	/**
	 * Returns what the player is currently doing.
	 *
	 * @return The player state.
	 */
	public MediaPlayerState getState() {
		return player.getState();
	}

	/**
	 * Stops playback and releases the player and both media sources. Closing
	 * a source that is already closed does nothing.
	 */
	@Override
	public synchronized void close() {
		if (closed) {
			return;
		}

		closed = true;

		player.close();

		disposeSources();
	}

	private void disposeSources() {
		if (videoSource != null) {
			videoSource.dispose();
		}
		if (audioSource != null) {
			audioSource.dispose();
		}
	}

}
