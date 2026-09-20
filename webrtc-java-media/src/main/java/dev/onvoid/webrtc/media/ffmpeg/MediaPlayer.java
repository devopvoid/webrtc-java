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

package dev.onvoid.webrtc.media.ffmpeg;

import java.io.IOException;
import java.util.Objects;

import dev.onvoid.webrtc.internal.NativeApi;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import dev.onvoid.webrtc.media.video.CustomVideoSource;

/**
 * Plays a media source into webrtc-java's custom media sources, so that a
 * peer connection sends a file the way it would send a camera and microphone.
 * <p>
 * Nothing is carried through Java: the player decodes in native code and hands
 * the result straight to the native side of the given sources. Frames are
 * delivered in real time and carry the presentation times of the source, so
 * what reaches the receiver keeps the timing of the file rather than the
 * timing of a thread.
 * <p>
 * The player takes over the reader it is given. That reader must not be used
 * or closed afterwards; closing the player releases it.
 * <p>
 * Example:
 * <pre>{@code
 * CustomVideoSource videoSource = new CustomVideoSource();
 * CustomAudioSource audioSource = new CustomAudioSource();
 *
 * MediaPlayer player = new MediaPlayer(new MediaReader(path), videoSource, audioSource);
 *
 * VideoTrack videoTrack = factory.createVideoTrack("video", videoSource);
 * AudioTrack audioTrack = factory.createAudioTrack("audio", audioSource);
 *
 * player.play();
 * }</pre>
 * <p>
 * A factory fed by this player is sending pushed audio, so it cannot also send
 * audio captured by its {@code AudioDeviceModule}. Use a separate factory if
 * an application needs both.
 *
 * @author Alex Andres
 */
public class MediaPlayer implements AutoCloseable {

	static {
		FFmpeg.load();
	}

	/** Guards the handle against a close racing a command. */
	private final Object lock = new Object();

	/** The native player, or 0 once this player has been closed. */
	private long handle;

	/** Read on the native player's thread, so never a stale value. */
	private volatile MediaPlayerListener listener;


	/**
	 * Creates a player for the given source, delivering into the given custom
	 * media sources. At least one of them has to be present; media of a kind
	 * with no source is decoded and dropped.
	 *
	 * @param reader      The source to play, which this player takes over.
	 * @param videoSource Where video goes, or {@code null} for none.
	 * @param audioSource Where audio goes, or {@code null} for none.
	 *
	 * @throws IOException              if the source cannot be decoded.
	 * @throws IllegalArgumentException if both sources are {@code null}.
	 */
	public MediaPlayer(MediaReader reader, CustomVideoSource videoSource,
			CustomAudioSource audioSource) throws IOException {
		Objects.requireNonNull(reader, "MediaReader is null");

		if (videoSource == null && audioSource == null) {
			throw new IllegalArgumentException("A player needs at least one media source");
		}

		// Taking the reader over rather than sharing it: the native player
		// owns it from here, and a Java reader that still held the same
		// pointer would release it a second time.
		long readerHandle = reader.detach();

		if (readerHandle == 0) {
			throw new IOException("The reader is closed");
		}

		handle = create(readerHandle, NativeApi.tableAddress(),
				videoSource != null ? NativeApi.handleOf(videoSource) : 0,
				audioSource != null ? NativeApi.handleOf(audioSource) : 0);
	}

	/**
	 * Sets what to report playback events to, replacing whatever was set
	 * before. A listener of {@code null} stops reporting.
	 *
	 * @param listener The listener, or {@code null}.
	 */
	public void setListener(MediaPlayerListener listener) {
		this.listener = listener;
	}

	/**
	 * Starts or resumes playback. A player that has reached the end starts
	 * over. Playing a player that is already playing does nothing.
	 */
	public void play() {
		synchronized (lock) {
			start(handle);
		}
	}

	/**
	 * Holds playback where it is. Resuming carries on from there rather than
	 * delivering everything that fell due in the meantime.
	 */
	public void pause() {
		synchronized (lock) {
			suspend(handle);
		}
	}

	/**
	 * Moves playback to the given position. Whatever has been decoded but not
	 * yet delivered is dropped.
	 *
	 * @param positionUs The position in microseconds from the start.
	 */
	public void seek(long positionUs) {
		synchronized (lock) {
			seek(handle, positionUs);
		}
	}

	/**
	 * Sets whether the source starts again when it runs out. A looping player
	 * keeps its timing across the seam, and never reports an end of stream.
	 *
	 * @param looping True to play the source over and over.
	 */
	public void setLooping(boolean looping) {
		synchronized (lock) {
			setLooping(handle, looping);
		}
	}

	/**
	 * Returns where playback has got to within the current pass, in
	 * microseconds. A looping source starts again from zero.
	 *
	 * @return The position in microseconds.
	 */
	public long getPositionUs() {
		synchronized (lock) {
			return position(handle);
		}
	}

	/**
	 * Returns what the player is currently doing.
	 *
	 * @return The player state.
	 */
	public MediaPlayerState getState() {
		synchronized (lock) {
			return MediaPlayerState.of(state(handle));
		}
	}

	/**
	 * Stops playback, releases the native player and the reader it took over,
	 * and waits for the player's thread to finish. Closing a player that is
	 * already closed does nothing.
	 */
	@Override
	public void close() {
		long closing;

		synchronized (lock) {
			closing = handle;

			// Cleared first, so that a command arriving from another thread
			// finds nothing to act on rather than a handle being freed.
			handle = 0;
		}

		dispose(closing);
	}

	/** Called by native code on the player's thread. */
	private void onNativeStateChanged(int state) {
		MediaPlayerListener current = listener;

		if (current != null) {
			current.onStateChanged(MediaPlayerState.of(state));
		}
	}

	/** Called by native code on the player's thread. */
	private void onNativeEndOfStream() {
		MediaPlayerListener current = listener;

		if (current != null) {
			current.onEndOfStream();
		}
	}

	/** Called by native code on the player's thread. */
	private void onNativeError(String message) {
		MediaPlayerListener current = listener;

		if (current != null) {
			current.onError(message);
		}
	}

	private native long create(long readerHandle, long tableAddress,
			long videoSourceHandle, long audioSourceHandle) throws IOException;

	private static native void start(long handle);

	private static native void suspend(long handle);

	private static native void seek(long handle, long positionUs);

	private static native void setLooping(long handle, boolean looping);

	private static native long position(long handle);

	private static native int state(long handle);

	private static native void dispose(long handle);

}
