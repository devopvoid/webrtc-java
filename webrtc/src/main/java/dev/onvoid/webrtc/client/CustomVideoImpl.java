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

import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrack;

/**
 * A video track fed with frames the application pushes.
 */
final class CustomVideoImpl extends LocalVideoImpl implements CustomVideo {

	private final CustomVideoSource source;


	CustomVideoImpl(LocalMediaImpl media, VideoTrack track, CustomVideoSource source) {
		super(media, track, source::dispose);

		this.source = source;
	}

	@Override
	public synchronized void push(VideoFrame frame) {
		requireNonNull(frame, "frame");

		if (isLive()) {
			source.pushFrame(frame);
		}
	}

	@Override
	public synchronized void push(VideoFrame frame, long timestampMicros) {
		requireNonNull(frame, "frame");

		if (isLive()) {
			source.pushFrame(frame, timestampMicros);
		}
	}
}
