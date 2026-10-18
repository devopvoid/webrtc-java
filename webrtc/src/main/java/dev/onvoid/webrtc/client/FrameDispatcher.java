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

package dev.onvoid.webrtc.client;

import java.util.ArrayDeque;
import java.util.function.Consumer;

import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrackSink;

/**
 * Hands the frames of a video track to a handler on a thread of its own, so
 * that a slow handler holds up neither WebRTC nor any other subscription.
 * Frames that wait are retained; those dropped when the queue is full, and
 * those the handler got, are released.
 */
final class FrameDispatcher implements VideoTrackSink {

	private final Consumer<VideoFrame> handler;

	private final int capacity;

	private final SerialExecutor executor;

	private final Consumer<Throwable> errorHandler;

	private final ArrayDeque<VideoFrame> queue = new ArrayDeque<>();

	private boolean scheduled;

	private boolean closed;


	FrameDispatcher(Consumer<VideoFrame> handler, FrameDelivery delivery,
			SerialExecutor executor, Consumer<Throwable> errorHandler) {
		this.handler = handler;
		this.capacity = delivery.capacity;
		this.executor = executor;
		this.errorHandler = errorHandler;
	}

	@Override
	public void onVideoFrame(VideoFrame frame) {
		VideoFrame dropped = null;

		synchronized (queue) {
			if (closed) {
				return;
			}

			frame.retain();
			queue.add(frame);

			if (queue.size() > capacity) {
				dropped = queue.poll();
			}
			if (!scheduled) {
				scheduled = true;
				executor.execute(this::drain);
			}
		}

		if (dropped != null) {
			dropped.release();
		}
	}

	void close() {
		synchronized (queue) {
			closed = true;

			for (VideoFrame frame : queue) {
				frame.release();
			}

			queue.clear();
		}
	}

	private void drain() {
		while (true) {
			VideoFrame frame;

			synchronized (queue) {
				frame = queue.poll();

				if (frame == null) {
					scheduled = false;
					return;
				}
			}

			try {
				handler.accept(frame);
			}
			catch (Throwable e) {
				errorHandler.accept(e);
			}
			finally {
				frame.release();
			}
		}
	}
}
