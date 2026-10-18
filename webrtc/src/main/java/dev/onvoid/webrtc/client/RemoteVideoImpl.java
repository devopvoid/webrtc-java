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

import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrack;

/**
 * A remote video track. Each frame subscription is a sink of the native track
 * with a thread of its own.
 */
final class RemoteVideoImpl extends RemoteTrackImpl implements RemoteVideo {

	private final VideoTrack track;

	// Confined to the peer's control executor.

	private final List<FrameDispatcher> dispatchers = new ArrayList<>();


	RemoteVideoImpl(PeerImpl peer, RTCRtpReceiver receiver, VideoTrack track,
			String id, List<String> streamIds) {
		super(peer, receiver, track, MediaKind.VIDEO, id, streamIds);

		this.track = track;
	}

	@Override
	public RemoteVideo asVideo() {
		return this;
	}

	@Override
	public Subscription onFrame(Consumer<VideoFrame> handler) {
		return onFrame(FrameDelivery.LATEST, handler);
	}

	@Override
	public Subscription onFrame(FrameDelivery delivery, Consumer<VideoFrame> handler) {
		requireNonNull(delivery, "delivery");
		requireNonNull(handler, "handler");

		SerialExecutor executor = new SerialExecutor(peer.rtc.framePool, peer.rtc::reportError, false, null);
		FrameDispatcher dispatcher = new FrameDispatcher(handler, delivery, executor, peer.rtc::reportError);

		onControl(() -> {
			dispatchers.add(dispatcher);
			track.addSink(dispatcher);
		});

		return () -> {
			dispatcher.close();

			onControl(() -> {
				if (dispatchers.remove(dispatcher)) {
					track.removeSink(dispatcher);
				}
			});
		};
	}

	@Override
	void detachSinks() {
		for (FrameDispatcher dispatcher : dispatchers) {
			track.removeSink(dispatcher);
			dispatcher.close();
		}

		dispatchers.clear();
	}
}
