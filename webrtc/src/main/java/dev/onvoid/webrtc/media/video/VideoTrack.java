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
import static java.util.Objects.nonNull;

import dev.onvoid.webrtc.media.MediaStreamTrack;

import java.util.IdentityHashMap;
import java.util.Map;

public class VideoTrack extends MediaStreamTrack {

	private final Map<VideoTrackSink, Long> sinks = new IdentityHashMap<>();


	private VideoTrack() {
		super();
	}

	@Override
	public void dispose() {
		for (long nativeSink : sinks.values()) {
			removeSinkInternal(nativeSink);
		}

		sinks.clear();

		super.dispose();
	}

	/**
	 * Adds a VideoSink to the track. A track can have any number of
	 * VideoSinks.
	 *
	 * @param sink The video sink to add.
	 */
	public void addSink(VideoTrackSink sink) {
		if (isNull(sink)) {
			throw new NullPointerException();
		}
		if (sinks.containsKey(sink)) {
			return;
		}

		final long nativeSink = addSinkInternal(sink);

		sinks.put(sink, nativeSink);
	}

	/**
	 * Removes a VideoSink from the track. If the VideoSink was not attached to
	 * the track, this is a no-op.
	 */
	public void removeSink(VideoTrackSink sink) {
		if (isNull(sink)) {
			throw new NullPointerException();
		}

		final Long nativeSink = sinks.remove(sink);

		if (nonNull(nativeSink)) {
			removeSinkInternal(nativeSink);
		}
	}

	private native long addSinkInternal(VideoTrackSink sink);

	private native void removeSinkInternal(long sinkHandle);

}
