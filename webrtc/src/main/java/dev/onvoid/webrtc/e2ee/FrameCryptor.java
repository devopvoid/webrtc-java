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

package dev.onvoid.webrtc.e2ee;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import dev.onvoid.webrtc.RTCEncodedFrame;
import dev.onvoid.webrtc.RTCEncodedFrameTransformer;
import dev.onvoid.webrtc.RTCEncodedVideoFrame;

/**
 * What the encryptor and the decryptor share: the state of each RTP stream,
 * the reports of failing streams, the key frame requests, and the buffers.
 * <p>
 * The actual work is done on byte arrays by {@link #process}, so that it can
 * be tested without a call.
 */
abstract class FrameCryptor implements RTCEncodedFrameTransformer {

	/** The least time between two key frame requests of a stream. */
	private static final long KEY_FRAME_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(500);

	/** The largest buffer a transform thread keeps for the next frame. */
	private static final int MAX_RETAINED_BUFFER = 1024 * 1024;

	private static final ThreadLocal<byte[][]> BUFFERS = ThreadLocal.withInitial(() -> new byte[2][]);

	final KeyProvider keys;

	private final Runnable keyFrameRequest;

	private final ConcurrentHashMap<Long, Stream> streams = new ConcurrentHashMap<>();


	FrameCryptor(KeyProvider keys, Runnable keyFrameRequest) {
		this.keys = keys;
		this.keyFrameRequest = keyFrameRequest;
	}

	@Override
	public void transform(RTCEncodedFrame frame) {
		boolean video = frame instanceof RTCEncodedVideoFrame;
		boolean keyFrame = video && ((RTCEncodedVideoFrame) frame).isKeyFrame();

		ByteBuffer data = frame.getData();
		int size = data.remaining();

		byte[][] buffers = BUFFERS.get();
		byte[] in = buffer(buffers, 0, size);
		byte[] out = buffer(buffers, 1, size + FrameFormat.MAX_OVERHEAD);

		data.get(in, 0, size);

		try {
			int length = process(frame.getSsrc(), frame.getMimeType(), video, keyFrame, in, size, out);

			if (length < 0) {
				frame.drop();
			}
			else {
				frame.setData(out, 0, length);
			}
		}
		finally {
			release(buffers, 0, in);
			release(buffers, 1, out);
		}
	}

	@Override
	public boolean allowsPlaintextObservers() {
		return keys.allowsPlaintextRecording();
	}

	/**
	 * Encrypts or decrypts one frame.
	 *
	 * @param in  The frame, {@code size} bytes.
	 * @param out Room for the result, {@link FrameFormat#MAX_OVERHEAD} bytes
	 *            more than the frame.
	 *
	 * @return The size of the result, or -1 if the frame is to be dropped.
	 */
	abstract int process(long ssrc, String mimeType, boolean video, boolean keyFrame, byte[] in, int size,
			byte[] out);

	abstract boolean isOutgoing();

	Stream stream(long ssrc) {
		return streams.computeIfAbsent(ssrc, Stream::new);
	}

	/**
	 * Drops a frame: counts it, and reports the stream if it was not failing
	 * for this reason already.
	 *
	 * @return -1, for the caller to return.
	 */
	int fail(Stream stream, CryptoFailure failure, long participant, int generation, String mimeType) {
		keys.dropped(failure);

		if (failure != CryptoFailure.REPLAY) {
			// A replayed frame was a copy; the stream itself lost nothing.
			stream.interrupted = true;
		}

		if (stream.failure != failure) {
			stream.failure = failure;

			keys.report(new CryptoEvent(failure, isOutgoing(), participant, generation, stream.ssrc, mimeType));
		}

		return -1;
	}

	/**
	 * Notes that a frame went through, and asks for a key frame if a video
	 * stream needs one.
	 *
	 * @param needsKeyFrame Whether a key frame would help even if nothing was
	 *                      dropped before, as after a key change.
	 */
	void succeed(Stream stream, boolean video, boolean keyFrame, boolean needsKeyFrame) {
		boolean wanted = needsKeyFrame || stream.interrupted;

		stream.failure = null;
		stream.interrupted = false;

		if (!video || keyFrame) {
			stream.keyFramePending = false;
			return;
		}

		if (wanted) {
			stream.keyFramePending = true;
		}

		if (stream.keyFramePending && keyFrameRequest != null) {
			long now = System.nanoTime();

			if (stream.lastKeyFrameRequest == 0 || now - stream.lastKeyFrameRequest >= KEY_FRAME_INTERVAL_NANOS) {
				stream.lastKeyFrameRequest = now;

				try {
					keyFrameRequest.run();
				}
				catch (RuntimeException e) {
					// Not negotiated yet, or gone; the next frame asks again.
				}
			}
		}
	}

	private static byte[] buffer(byte[][] buffers, int index, int size) {
		byte[] buffer = buffers[index];

		if (buffer == null || buffer.length < size) {
			buffer = new byte[Math.max(size, 4096)];
		}

		buffers[index] = null;

		return buffer;
	}

	private static void release(byte[][] buffers, int index, byte[] buffer) {
		if (buffer.length <= MAX_RETAINED_BUFFER) {
			buffers[index] = buffer;
		}
	}



	/**
	 * One RTP stream through the transform. Its frames come one at a time,
	 * from the transform thread of its sender or receiver.
	 */
	static final class Stream {

		final long ssrc;

		/** The context of an outgoing stream, or -1 until it has one. */
		int context = -1;

		/** The next counter of an outgoing stream; never reset. */
		long counter;

		/** The generation the last outgoing frame was encrypted with, or -1. */
		int generation = -1;

		/** Why the last frame was dropped, or null if it went through. */
		CryptoFailure failure;

		/** Whether frames were lost since the last one went through. */
		boolean interrupted;

		/** Whether a key frame was asked for and has not come yet. */
		boolean keyFramePending;

		long lastKeyFrameRequest;


		Stream(long ssrc) {
			this.ssrc = ssrc;
		}
	}

}
