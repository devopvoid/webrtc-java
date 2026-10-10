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

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import dev.onvoid.webrtc.media.MediaStreamTrack;

import java.util.IdentityHashMap;
import java.util.Map;

public class AudioTrack extends MediaStreamTrack {

	private final Map<AudioTrackSink, Long> sinks = new IdentityHashMap<>();


	private AudioTrack() {
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
	 * Adds an AudioSink to the track. A track can have any number of AudioSinks.
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
	 * the track, this is a no-op.
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

	/**
	 * Get the signal level from the audio track.
	 *
	 * @return The signal level.
	 */
	public native int getSignalLevel();

	/**
	 * Sets the playout volume of a remote audio track, the one of an {@link
	 * dev.onvoid.webrtc.RTCRtpReceiver}, from 0 (silent) to 10, where 1 plays
	 * the audio as received. Values above 1 amplify it. It applies to what the
	 * audio device plays, not to the audio passed to an {@link AudioTrackSink}.
	 * A local track ignores it.
	 *
	 * @param volume The volume, from 0 to 10.
	 *
	 * @throws IllegalArgumentException If the volume is outside 0 to 10.
	 */
	public void setVolume(double volume) {
		if (!(volume >= 0 && volume <= 10)) {
			throw new IllegalArgumentException("Volume must be between 0 and 10: " + volume);
		}

		setVolumeInternal(volume);
	}

	private native void setVolumeInternal(double volume);

	private native long addSinkInternal(AudioTrackSink sink);

	private native void removeSinkInternal(long sinkHandle);

}
