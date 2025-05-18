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

package dev.onvoid.webrtc.media.audio;

import dev.onvoid.webrtc.media.MediaSource;

import java.util.IdentityHashMap;
import java.util.Map;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * A source for one or more AudioTracks.
 *
 * @author Alex Andres
 */
public class AudioTrackSource extends MediaSource {

	private final Map<AudioTrackSink, Long> sinks = new IdentityHashMap<>();

	protected AudioTrackSource() {

	}

	public void dispose() {
		for (long nativeSink : sinks.values()) {
			removeSinkInternal(nativeSink);
		}

		sinks.clear();
	}

	/**
	 * Adds an AudioSink to the track source. A track source can have any number of
	 * AudioSinks.
	 *
	 * @param sink The audio sink that will receive audio data from the track.
	 */
	public void addSink(AudioTrackSink sink) {
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
	 * Removes an AudioSink from the track. If the AudioSink was not attached to
	 * the track source, this is a no-op.
	 */
	public void removeSink(AudioTrackSink sink) {
		if (isNull(sink)) {
			throw new NullPointerException();
		}

		final Long nativeSink = sinks.remove(sink);

		if (nonNull(nativeSink)) {
			removeSinkInternal(nativeSink);
		}
	}

	private native long addSinkInternal(AudioTrackSink sink);

	private native void removeSinkInternal(long sinkHandle);

	public native void setVolume(double volume);

}
