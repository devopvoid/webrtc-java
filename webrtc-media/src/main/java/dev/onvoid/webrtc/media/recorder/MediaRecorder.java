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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.RTCRtpSender;
import dev.onvoid.webrtc.client.RemoteTrack;
import dev.onvoid.webrtc.client.Sender;
import dev.onvoid.webrtc.client.internal.EncodedFrames;
import dev.onvoid.webrtc.internal.NativeApi;
import dev.onvoid.webrtc.media.player.FFmpeg;

/**
 * Records what a peer connection sends or receives into a media file, without
 * decoding or re-encoding it: the encoded frames of each sender or receiver
 * go into the file as they are. A recording therefore costs next to no CPU,
 * keeps the exact quality that went over the network, and needs no codec
 * beyond the container.
 * <p>
 * The container follows the file name: {@code .mkv} (Matroska) holds every
 * codec WebRTC sends and is the safe choice; {@code .webm} holds VP8, VP9,
 * AV1 and Opus; {@code .mp4} holds H.264, H.265, AV1, VP9 and Opus, and is
 * written fragmented, so that it plays up to where a recording was cut short.
 * A track whose codec the file cannot hold is left out and reported to the
 * {@link MediaRecorderListener#onWarning(String) listener}.
 * <p>
 * Example:
 * <pre>{@code
 * try (MediaRecorder recorder = new MediaRecorder(Paths.get("call.mkv"))) {
 *     recorder.addTrack(videoReceiver);
 *     recorder.addTrack(audioReceiver);
 *     recorder.start();
 *
 *     // ... the call goes on ...
 *
 *     recorder.stop();
 * }
 * }</pre>
 * <p>
 * How it behaves:
 * <ul>
 * <li>A video track is recorded from its first key frame, and the recorder
 * asks the sender or receiver for one so that it does not have to wait. The
 * sender or receiver must therefore not be disposed while recording.</li>
 * <li>The file begins once every track has sent its first frames, or once
 * the tracks that have waited three seconds for the rest; a track that sends
 * nothing by then is left out.</li>
 * <li>Tracks are placed on a common timeline by when their frames arrived,
 * and follow their own RTP timestamps from there.</li>
 * <li>A sender with simulcast is recorded at one of its layers.</li>
 * <li>A sender's frames are recorded before an {@link
 * dev.onvoid.webrtc.RTCEncodedFrameTransformer} runs on them, a receiver's
 * after, so an end-to-end encrypted call is recorded in the clear.</li>
 * </ul>
 *
 * @author Alex Andres
 */
public class MediaRecorder implements AutoCloseable {

	static {
		FFmpeg.load();
	}

	private final Object lock = new Object();

	private final Path file;

	/**
	 * How to ask each track for a key frame, by track index. Filled before
	 * the start, read by the writer thread after it.
	 */
	private final List<Runnable> keyFrameRequests = new CopyOnWriteArrayList<>();

	/** Read on the event thread, so never a stale value. */
	private volatile MediaRecorderListener listener;

	/** Where listener calls and key frame requests run. */
	private ExecutorService events;

	/** The native recorder, or 0 once this recorder has been closed. */
	private long handle;

	private MediaRecorderState state = MediaRecorderState.IDLE;

	/** How the recording ended, or null until it was stopped. */
	private MediaRecorderResult result;


	/**
	 * Creates a recorder writing to the given file, which is created, or
	 * emptied if it exists.
	 *
	 * @param file Where to record to; its extension picks the container.
	 *
	 * @throws IOException If the file cannot be written.
	 */
	public MediaRecorder(Path file) throws IOException {
		Objects.requireNonNull(file, "File is null");

		this.file = file.toAbsolutePath();
		this.handle = create(this.file.toString(), NativeApi.tableAddress());
	}

	/**
	 * Sets what to report to, replacing whatever was set before. A listener of
	 * {@code null} stops reporting.
	 *
	 * @param listener The listener, or {@code null}.
	 */
	public void setListener(MediaRecorderListener listener) {
		this.listener = listener;
	}

	/**
	 * Records what the given sender sends, as it comes out of the encoder.
	 *
	 * @param sender The sender to record.
	 *
	 * @throws IllegalStateException    If the recorder was started or closed.
	 * @throws IllegalArgumentException If the sender was disposed.
	 */
	public void addTrack(RTCRtpSender sender) {
		Objects.requireNonNull(sender, "Sender is null");

		synchronized (lock) {
			checkState(MediaRecorderState.IDLE);

			addTrack(NativeApi.encodedFramesOf(sender), sender::generateKeyFrame);
		}
	}

	/**
	 * Records what the given receiver receives, as it goes into the decoder.
	 *
	 * @param receiver The receiver to record.
	 *
	 * @throws IllegalStateException    If the recorder was started or closed.
	 * @throws IllegalArgumentException If the receiver was disposed.
	 */
	public void addTrack(RTCRtpReceiver receiver) {
		Objects.requireNonNull(receiver, "Receiver is null");

		synchronized (lock) {
			checkState(MediaRecorderState.IDLE);

			addTrack(NativeApi.encodedFramesOf(receiver), receiver::requestKeyFrame);
		}
	}

	/**
	 * Records what a sender of the client API sends, as it comes out of the
	 * encoder. Key frames are asked for on the sender's peer, which keeps
	 * the sender to itself; removing the sender or closing the peer ends the
	 * track in the recording.
	 *
	 * @param sender The sender to record.
	 *
	 * @throws IllegalStateException    If the recorder was started or closed.
	 * @throws IllegalArgumentException If the sender was removed, or its peer
	 *                                  closed.
	 */
	public void addTrack(Sender sender) {
		Objects.requireNonNull(sender, "Sender is null");

		synchronized (lock) {
			checkState(MediaRecorderState.IDLE);
			addTrack(sourceOf(EncodedFrames.of(sender)));
		}
	}

	/**
	 * Records what a remote track of the client API receives, as it goes into
	 * the decoder. Key frames are asked for on the track's peer; the track
	 * ending, or the peer closing, ends the track in the recording.
	 *
	 * @param track The remote track to record.
	 *
	 * @throws IllegalStateException    If the recorder was started or closed.
	 * @throws IllegalArgumentException If the track ended.
	 */
	public void addTrack(RemoteTrack track) {
		Objects.requireNonNull(track, "Track is null");

		synchronized (lock) {
			checkState(MediaRecorderState.IDLE);
			addTrack(sourceOf(EncodedFrames.of(track)));
		}
	}

	/**
	 * Starts recording the tracks added so far.
	 *
	 * @throws IllegalStateException If the recorder has no tracks, or was
	 *                               started or closed before.
	 */
	public void start() {
		synchronized (lock) {
			checkState(MediaRecorderState.IDLE);

			if (keyFrameRequests.isEmpty()) {
				throw new IllegalStateException("A recorder needs at least one track");
			}

			events = Executors.newSingleThreadExecutor(runnable -> {
				Thread thread = new Thread(runnable, "MediaRecorder-events");
				thread.setDaemon(true);
				return thread;
			});

			try {
				start(handle);
			}
			catch (RuntimeException | Error e) {
				// The native recorder could not start its writer, and is as
				// it was: still idle, and closing it deletes its file.
				events.shutdown();
				events = null;

				throw e;
			}

			state = MediaRecorderState.RECORDING;
		}
	}

	/**
	 * Stops recording and finishes the file, waiting for everything received
	 * so far to be written. A recorder that never got any media to write
	 * deletes its file rather than leave an unplayable one behind.
	 * <p>
	 * A file that holds media is kept even when writing, finishing or closing
	 * it failed, since what made it into the file may still play. Whether it
	 * is complete, {@link #getResult()} tells.
	 * <p>
	 * Stopping a recorder that is stopped does nothing, and returns the same.
	 * Stopping one that was never started deletes its file.
	 *
	 * @return True if the file holds a recording, complete or not, false if it
	 * was deleted.
	 */
	public boolean stop() {
		boolean recorded;

		synchronized (lock) {
			if (result != null) {
				return result.keepsFile();
			}
			if (handle == 0) {
				return false;
			}

			// In the order of the native MediaRecorder::Result.
			result = MediaRecorderResult.values()[stop(handle)];
			state = MediaRecorderState.STOPPED;
			recorded = result.keepsFile();

			if (events != null) {
				// Lets the reports already queued run, then ends the thread.
				events.shutdown();
			}
		}

		if (!recorded) {
			try {
				Files.deleteIfExists(file);
			}
			catch (IOException e) {
				// The file stays behind, empty; there is nothing else to do.
			}
		}

		return recorded;
	}

	/**
	 * Returns what the recorder is currently doing.
	 *
	 * @return The recorder state.
	 */
	public MediaRecorderState getState() {
		synchronized (lock) {
			return state;
		}
	}

	/**
	 * Returns how the recording ended: completely, partly, without media, or
	 * failed. It is known once the recorder is stopped, and stays the same
	 * from then on, after {@link #close()} as well.
	 *
	 * @return The result, or {@code null} if the recorder was not stopped
	 * yet.
	 */
	public MediaRecorderResult getResult() {
		synchronized (lock) {
			return result;
		}
	}

	/**
	 * @return The file this recorder writes to.
	 */
	public Path getFile() {
		return file;
	}

	/**
	 * Stops the recording if it still runs and releases the native recorder.
	 * Closing a recorder that is closed does nothing.
	 */
	@Override
	public void close() {
		stop();

		synchronized (lock) {
			if (handle != 0) {
				dispose(handle);
				handle = 0;
			}
		}
	}

	/** Called with the lock held, in the idle state. */
	private void addTrack(EncodedFrames.Source source) {
		addTrack(source.take(), source::requestKeyFrame);
	}

	/**
	 * Waits for the client API's peer to hand over the encoded frames. Its
	 * control thread never takes this recorder's lock, so waiting under it
	 * cannot deadlock.
	 */
	static EncodedFrames.Source sourceOf(CompletableFuture<EncodedFrames.Source> future) {
		try {
			return future.get(10, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) {
			giveUp(future);
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while adding the track", e);
		}
		catch (ExecutionException e) {
			throw new IllegalArgumentException(e.getCause().getMessage(), e.getCause());
		}
		catch (TimeoutException e) {
			giveUp(future);
			throw new IllegalStateException("The peer did not hand over the track in time", e);
		}
	}

	/**
	 * Stops waiting for a source. The peer closes one that comes after the
	 * cancellation; this closes one that came before it, since no recorder
	 * takes it over.
	 */
	static void giveUp(CompletableFuture<EncodedFrames.Source> future) {
		future.cancel(false);
		future.thenAccept(EncodedFrames.Source::close);
	}

	private void addTrack(long frames, Runnable keyFrameRequest) {
		if (frames == 0) {
			throw new IllegalArgumentException("The sender or receiver was disposed");
		}

		// The native recorder takes over the reference the handle carries.
		addTrack(handle, frames);

		keyFrameRequests.add(keyFrameRequest);
	}

	private void checkState(MediaRecorderState expected) {
		if (handle == 0) {
			throw new IllegalStateException("The recorder is closed");
		}
		if (state != expected) {
			throw new IllegalStateException("The recorder is " + state.name().toLowerCase()
					+ ", not " + expected.name().toLowerCase());
		}
	}

	private void post(Runnable event) {
		ExecutorService executor = events;

		if (executor == null) {
			return;
		}

		try {
			executor.execute(() -> {
				try {
					event.run();
				}
				catch (Throwable e) {
					Thread thread = Thread.currentThread();
					thread.getUncaughtExceptionHandler().uncaughtException(thread, e);
				}
			});
		}
		catch (RejectedExecutionException e) {
			// Stopped in the meantime; nobody is waiting for this any more.
		}
	}

	/** Called by native code on the writer thread. */
	private void onNativeStarted() {
		post(() -> {
			MediaRecorderListener current = listener;

			if (current != null) {
				current.onStarted();
			}
		});
	}

	/** Called by native code on the writer thread. */
	private void onNativeWarning(String message) {
		post(() -> {
			MediaRecorderListener current = listener;

			if (current != null) {
				current.onWarning(message);
			}
		});
	}

	/** Called by native code on the writer thread. */
	private void onNativeError(String message) {
		post(() -> {
			MediaRecorderListener current = listener;

			if (current != null) {
				current.onError(message);
			}
		});
	}

	/** Called by native code on the writer thread. */
	private void onNativeKeyFrameNeeded(int track) {
		if (track < 0 || track >= keyFrameRequests.size()) {
			return;
		}

		Runnable request = keyFrameRequests.get(track);

		// Run away from the writer thread: asking WebRTC waits for its
		// signaling thread, which may be busy stopping this very recorder.
		post(() -> {
			try {
				request.run();
			}
			catch (RuntimeException e) {
				// Not negotiated yet, or disposed of by the application. The
				// recording waits for the next key frame instead.
			}
		});
	}

	private native long create(String path, long tableAddress) throws IOException;

	private static native int addTrack(long handle, long frames);

	private static native void start(long handle);

	private static native int stop(long handle);

	private static native void dispose(long handle);

}
