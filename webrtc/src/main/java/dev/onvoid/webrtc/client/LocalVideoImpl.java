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

	// Guarded by this.

	private final List<FrameDispatcher> dispatchers = new ArrayList<>();


	LocalVideoImpl(LocalMediaImpl media, VideoTrack track, Runnable releaseSource) {
		super(media, track, MediaKind.VIDEO, releaseSource);
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
			videoTrack().addSink(dispatcher);
		}

		return () -> {
			dispatcher.close();

			synchronized (LocalVideoImpl.this) {
				if (dispatchers.remove(dispatcher) && isLive()) {
					videoTrack().removeSink(dispatcher);
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
			videoTrack().removeSink(dispatcher);
			dispatcher.close();
		}
	}

	/**
	 * Moves the preview handlers from a track that was replaced to the
	 * current one. Called under this object's lock.
	 */
	void moveSinks(VideoTrack from) {
		VideoTrack to = videoTrack();

		for (FrameDispatcher dispatcher : dispatchers) {
			from.removeSink(dispatcher);
			to.addSink(dispatcher);
		}
	}

	private VideoTrack videoTrack() {
		return (VideoTrack) nativeTrack();
	}
}
