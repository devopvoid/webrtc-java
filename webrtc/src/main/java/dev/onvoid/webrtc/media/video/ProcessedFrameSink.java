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

package dev.onvoid.webrtc.media.video;

/**
 * The sink a {@link VideoProcessor} delivers processed frames to. It passes
 * them on to its source until it is detached, when the processor is replaced
 * or the source is disposed, and drops them afterwards.
 * <p>
 * A delivery holds no lock: a callback further down may call into the source
 * while another thread, holding the monitor of the source, detaches this sink.
 * The native call keeps the native source alive by itself and drops the frame
 * of a disposed source. So {@link #detach()} does not wait for a delivery that
 * is running, which may still reach the tracks, and every delivery that starts
 * after it returned is dropped.
 */
final class ProcessedFrameSink implements VideoTrackSink {

	private volatile VideoTrackSource source;


	ProcessedFrameSink(VideoTrackSource source) {
		this.source = source;
	}

	@Override
	public void onVideoFrame(VideoFrame frame) {
		if (frame == null) {
			throw new NullPointerException("VideoFrame must not be null");
		}

		VideoTrackSource target = source;

		if (target != null) {
			target.deliverProcessedFrame(frame);
		}
	}

	void detach() {
		source = null;
	}
}
