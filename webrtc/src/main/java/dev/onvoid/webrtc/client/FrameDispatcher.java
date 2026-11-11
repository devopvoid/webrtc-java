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

import java.util.function.Consumer;

import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrackSink;

/**
 * Hands the frames of a video track to a handler on a thread of its own, so
 * that a slow handler holds up neither WebRTC nor any other subscription.
 * <p>
 * The native sink hands over one reference to each frame, which this object
 * owns: it releases a frame once the handler returned, when the queue is full
 * and the frame is dropped, or when the subscription closed.
 */
final class FrameDispatcher extends Dispatcher<VideoFrame> implements VideoTrackSink {

	FrameDispatcher(Consumer<VideoFrame> handler, FrameDelivery delivery,
			SerialExecutor executor, Workload workload, Consumer<Throwable> errorHandler) {
		super(handler, delivery.capacity, executor, workload, errorHandler);
	}

	@Override
	public void onVideoFrame(VideoFrame frame) {
		offer(frame);
	}

	@Override
	long weight(VideoFrame frame) {
		return 1;
	}

	@Override
	void release(VideoFrame frame) {
		frame.release();
	}
}
