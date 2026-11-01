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

package dev.onvoid.webrtc.media.video;

import static java.util.Objects.isNull;

import dev.onvoid.webrtc.internal.SinkRegistry;
import dev.onvoid.webrtc.media.MediaStreamTrack;

/**
 * A video track. Its sinks may be added and removed from any thread, also at
 * the same time, and also from within the callback of a sink, the sink's own
 * included.
 * <p>
 * WebRTC holds a lock while it hands a frame to the sinks, which adding and
 * removing a sink take as well. A change made from within a callback
 * therefore takes effect for the sinks at once, but the native sink is added
 * or removed on another thread right after the callback has returned.
 */
public class VideoTrack extends MediaStreamTrack {

	private final SinkRegistry<VideoTrackSink> sinks = new SinkRegistry<>(this::removeSinkInternal);


	private VideoTrack() {
		super();
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Removes the sinks of the track first, and waits for those that other
	 * threads are still adding or removing.
	 *
	 * @throws IllegalStateException if called from within the callback of a
	 *                               sink, of this track or any other.
	 */
	@Override
	public void dispose() {
		sinks.close();

		super.dispose();
	}

	/**
	 * Adds a VideoSink to the track, which gets a copy of each frame of its
	 * own, as with {@link VideoFrameAccess#COPY}. A track can have any number
	 * of VideoSinks.
	 *
	 * @param sink The video sink to add. Adding it again does nothing.
	 *
	 * @throws IllegalStateException if the track has been disposed.
	 *
	 * @see #addSink(VideoTrackSink, VideoFrameAccess)
	 */
	public void addSink(VideoTrackSink sink) {
		addSink(sink, VideoFrameAccess.COPY);
	}

	/**
	 * Adds a VideoSink to the track. A track can have any number of
	 * VideoSinks. Called from within the callback of a sink, it returns
	 * before the sink is registered, and the sink gets the frames that follow
	 * once it is.
	 *
	 * @param sink   The video sink to add. Adding it again does nothing,
	 *               whatever the access.
	 * @param access Whether the sink gets a writable copy of each frame or
	 *               the read-only pixels the source handed out.
	 *
	 * @throws IllegalStateException if the track has been disposed.
	 */
	public void addSink(VideoTrackSink sink, VideoFrameAccess access) {
		if (isNull(sink) || isNull(access)) {
			throw new NullPointerException();
		}

		boolean shared = access == VideoFrameAccess.SHARED;

		sinks.add(sink, (s, registration) -> addSinkInternal(new Delivery(s, registration), shared));
	}

	/**
	 * Removes a VideoSink from the track. If the VideoSink was not attached to
	 * the track, this is a no-op. Once this returns, the sink gets no further
	 * frames, except one that another thread is handing it at that moment. A
	 * sink may remove itself in its {@link VideoTrackSink#onVideoFrame}.
	 */
	public void removeSink(VideoTrackSink sink) {
		if (isNull(sink)) {
			throw new NullPointerException();
		}

		sinks.remove(sink);
	}

	private native long addSinkInternal(VideoTrackSink sink, boolean shared);

	private native void removeSinkInternal(long sinkHandle);



	/**
	 * What the native sink calls: it tells the registry when a thread hands
	 * the sink a frame, and drops the frames of a removed sink.
	 */
	private static final class Delivery implements VideoTrackSink {

		private final VideoTrackSink sink;

		private final SinkRegistry.Registration registration;


		Delivery(VideoTrackSink sink, SinkRegistry.Registration registration) {
			this.sink = sink;
			this.registration = registration;
		}

		@Override
		public void onVideoFrame(VideoFrame frame) {
			if (!registration.enter()) {
				// The sink would have owned the reference.
				frame.release();
				return;
			}

			try {
				sink.onVideoFrame(frame);
			}
			finally {
				registration.exit();
			}
		}
	}

}
