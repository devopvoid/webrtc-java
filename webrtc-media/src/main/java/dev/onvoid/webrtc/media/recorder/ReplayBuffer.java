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

package dev.onvoid.webrtc.media.recorder;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.RTCRtpSender;
import dev.onvoid.webrtc.client.RemoteTrack;
import dev.onvoid.webrtc.client.Sender;
import dev.onvoid.webrtc.internal.NativeApi;

/**
 * Keeps the last seconds of what a peer connection sends or receives in
 * memory, and saves them into a file on demand: "save the last 30 seconds".
 * Like a {@link MediaRecorder}, it takes the encoded frames as they are,
 * without decoding or re-encoding them, so keeping them costs memory, not
 * CPU.
 * <p>
 * Example:
 * <pre>{@code
 * try (ReplayBuffer buffer = new ReplayBuffer(Duration.ofSeconds(30))) {
 *     buffer.addTrack(videoReceiver);
 *     buffer.addTrack(audioReceiver);
 *     buffer.start();
 *
 *     // ... something happens worth keeping ...
 *
 *     buffer.save(Paths.get("replay.mkv"))
 *             .thenAccept(replay -> System.out.println("Saved " + replay.getDuration()));
 * }
 * }</pre>
 * <p>
 * A replay begins with a key frame of every video track, so that it plays on
 * its own: at the last key frame before the window, which therefore holds at
 * least the window, and a few seconds more. Since WebRTC sends key frames
 * rarely of its own accord, the buffer asks its video tracks for one now and
 * then: every third of the window, at least every two seconds and at most
 * every ten. A replay holds what the buffer took up to the moment it was asked
 * for, and the buffer goes on taking frames while the replay is written.
 * <p>
 * What the buffer holds is limited twice: by the window, and by a number of
 * bytes, {@value #DEFAULT_MAX_BYTES} unless the constructor is given
 * another. Beyond the limit in bytes, the oldest frames go first, and a
 * replay is shorter than the window.
 * <p>
 * The tracks behave as in a {@link MediaRecorder}, end-to-end encrypted ones
 * included, with two differences:
 * a track is kept as soon as it is known, without waiting for the others;
 * and a track that changes its codec is kept with the new one from then on,
 * having its frames in the old one dropped. A track whose codec the file
 * of a replay cannot hold is left out of that replay, and reported to the
 * {@link MediaRecorderListener#onWarning(String) listener}.
 *
 * @author Alex Andres
 */
public class ReplayBuffer implements AutoCloseable {

	/** The most a buffer holds unless told otherwise, 256 MB. */
	public static final long DEFAULT_MAX_BYTES = 256L * 1024 * 1024;

	private final MediaRecorder recorder;

	private final Duration window;


	/**
	 * Creates a buffer for replays of the given length, holding at most
	 * {@value #DEFAULT_MAX_BYTES} bytes.
	 *
	 * @param window How far back a replay reaches, at least a second.
	 *
	 * @throws IOException If the native buffer cannot be created.
	 */
	public ReplayBuffer(Duration window) throws IOException {
		this(window, DEFAULT_MAX_BYTES);
	}

	/**
	 * Creates a buffer for replays of the given length, holding at most the
	 * given number of bytes.
	 *
	 * @param window   How far back a replay reaches, at least a second.
	 * @param maxBytes The most the buffer holds, more than 0.
	 *
	 * @throws IOException If the native buffer cannot be created.
	 */
	public ReplayBuffer(Duration window, long maxBytes) throws IOException {
		this(window, maxBytes, NativeApi.tableAddress());
	}

	/**
	 * Creates a buffer that takes its frames and its clock from the given
	 * webrtc-java function table, as the tests do.
	 */
	ReplayBuffer(Duration window, long maxBytes, long tableAddress) throws IOException {
		Objects.requireNonNull(window, "Window is null");

		if (window.compareTo(Duration.ofSeconds(1)) < 0) {
			throw new IllegalArgumentException("A replay reaches back at least a second, not " + window);
		}
		if (maxBytes <= 0) {
			throw new IllegalArgumentException("The buffer must hold more than 0 bytes, got " + maxBytes);
		}

		this.window = window;
		this.recorder = MediaRecorder.replayBuffer(window, maxBytes, tableAddress);
	}

	/**
	 * Sets what to report to, replacing whatever was set before: {@link
	 * MediaRecorderListener#onStarted()} once the first frames are kept,
	 * warnings, and {@link MediaRecorderListener#onError(String)} if the
	 * buffer ran out of native memory and keeps nothing more. A listener of
	 * {@code null} stops reporting.
	 *
	 * @param listener The listener, or {@code null}.
	 */
	public void setListener(MediaRecorderListener listener) {
		recorder.setListener(listener);
	}

	/**
	 * Keeps what the given sender sends, as it comes out of the encoder.
	 *
	 * @param sender The sender to keep.
	 *
	 * @throws IllegalStateException    If the buffer was started or closed,
	 *                                  or the track's transform does not
	 *                                  allow recording.
	 * @throws IllegalArgumentException If the sender was disposed.
	 *
	 * @see MediaRecorder#addTrack(RTCRtpSender)
	 */
	public void addTrack(RTCRtpSender sender) {
		recorder.addTrack(sender);
	}

	/**
	 * Keeps what the given receiver receives, as it goes into the decoder.
	 *
	 * @param receiver The receiver to keep.
	 *
	 * @throws IllegalStateException    If the buffer was started or closed,
	 *                                  or the track's transform does not
	 *                                  allow recording.
	 * @throws IllegalArgumentException If the receiver was disposed.
	 *
	 * @see MediaRecorder#addTrack(RTCRtpReceiver)
	 */
	public void addTrack(RTCRtpReceiver receiver) {
		recorder.addTrack(receiver);
	}

	/**
	 * Keeps what a sender of the client API sends.
	 *
	 * @param sender The sender to keep.
	 *
	 * @throws IllegalStateException    If the buffer was started or closed,
	 *                                  or the track's transform does not
	 *                                  allow recording.
	 * @throws IllegalArgumentException If the sender was removed, or its peer
	 *                                  closed.
	 *
	 * @see MediaRecorder#addTrack(Sender)
	 */
	public void addTrack(Sender sender) {
		recorder.addTrack(sender);
	}

	/**
	 * Keeps what a remote track of the client API receives.
	 *
	 * @param track The remote track to keep.
	 *
	 * @throws IllegalStateException    If the buffer was started or closed,
	 *                                  or the track's transform does not
	 *                                  allow recording.
	 * @throws IllegalArgumentException If the track ended.
	 *
	 * @see MediaRecorder#addTrack(RemoteTrack)
	 */
	public void addTrack(RemoteTrack track) {
		recorder.addTrack(track);
	}

	/**
	 * @see MediaRecorder#addEncodedFrames(long, Runnable)
	 */
	void addEncodedFrames(long frames, Runnable keyFrameRequest) {
		recorder.addEncodedFrames(frames, keyFrameRequest);
	}

	/**
	 * @see MediaRecorder#sync()
	 */
	void sync() throws InterruptedException {
		recorder.sync();
	}

	/**
	 * Starts keeping the tracks added so far. If that fails, the buffer is as
	 * it was before and may be started again.
	 *
	 * @throws IllegalStateException If the buffer has no tracks, or was
	 *                               started or closed before.
	 */
	public void start() {
		recorder.start();
	}

	/**
	 * Saves what the buffer holds of the last {@link #getWindow() window} into
	 * the given file, which must not exist yet; its extension picks the
	 * container, as for a {@link MediaRecorder}. The file is created at once,
	 * and written by the buffer's own thread.
	 * <p>
	 * The future completes on the listener's thread, with the replay once it
	 * is finished and closed; it is {@link MediaRecorderResult#PARTIAL} if
	 * writing it failed after media went into it, which the listener's
	 * {@link MediaRecorderListener#onWarning(String)} is told. It completes
	 * exceptionally, and the file is deleted, if nothing could be written:
	 * with an {@link IOException} if writing failed, and an {@link
	 * IllegalStateException} if the buffer held nothing to save, or was
	 * stopped first.
	 *
	 * @param file Where to save the replay.
	 *
	 * @return The replay, once it is saved.
	 *
	 * @throws IllegalStateException                     If the buffer is not
	 *                                                   started, was stopped,
	 *                                                   or failed.
	 * @throws java.nio.file.FileAlreadyExistsException If the file exists.
	 * @throws IOException                               If the file cannot be
	 *                                                   created.
	 */
	public CompletableFuture<MediaRecorderSegment> save(Path file) throws IOException {
		return recorder.saveReplay(file);
	}

	/**
	 * Stops keeping frames and lets go of what the buffer holds, once the
	 * replays asked for so far are saved. Stopping a buffer that is stopped
	 * does nothing.
	 */
	public void stop() {
		recorder.stop();
	}

	/**
	 * Returns what the buffer is doing: {@link MediaRecorderState#RECORDING}
	 * while it keeps frames.
	 *
	 * @return The buffer state.
	 */
	public MediaRecorderState getState() {
		return recorder.getState();
	}

	/**
	 * Returns what the buffer lost so far, before it could keep it.
	 *
	 * @return The numbers as of now.
	 *
	 * @see MediaRecorder#getStats()
	 */
	public MediaRecorderStats getStats() {
		return recorder.getStats();
	}

	/**
	 * @return How far back a replay reaches.
	 */
	public Duration getWindow() {
		return window;
	}

	/**
	 * Stops the buffer if it still runs and releases the native buffer.
	 * Closing a buffer that is closed does nothing.
	 */
	@Override
	public void close() {
		recorder.close();
	}

}
