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

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrack;

/**
 * A local video track, from a camera, a screen or pushed frames.
 */
class LocalVideoImpl extends LocalTrackImpl implements LocalVideo {

	private final VideoTrack track;

	// Guarded by this.

	private final List<FrameDispatcher> dispatchers = new ArrayList<>();


	LocalVideoImpl(LocalMediaImpl media, VideoTrack track, Runnable releaseSource) {
		super(media, track, MediaKind.VIDEO, releaseSource);

		this.track = track;
	}

	@Override
	public Subscription onFrame(Consumer<VideoFrame> handler) {
		requireNonNull(handler, "handler");

		SerialExecutor executor = new SerialExecutor(rtc.framePool, rtc::reportError, false, null);
		FrameDispatcher dispatcher = new FrameDispatcher(handler, FrameDelivery.LATEST, executor, rtc::reportError);

		synchronized (this) {
			if (!isLive()) {
				return () -> { };
			}

			dispatchers.add(dispatcher);
			track.addSink(dispatcher);
		}

		return () -> {
			dispatcher.close();

			synchronized (LocalVideoImpl.this) {
				if (dispatchers.remove(dispatcher) && isLive()) {
					track.removeSink(dispatcher);
				}
			}
		};
	}

	@Override
	void beforeRelease() {
		List<FrameDispatcher> attached;

		synchronized (this) {
			attached = new ArrayList<>(dispatchers);
			dispatchers.clear();
		}

		for (FrameDispatcher dispatcher : attached) {
			track.removeSink(dispatcher);
			dispatcher.close();
		}
	}
}
