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

package dev.onvoid.webrtc.livekit;

import java.util.List;

import dev.onvoid.webrtc.client.LocalTrack;
import dev.onvoid.webrtc.client.Sender;

/**
 * A local track the server accepted and that is negotiated to it.
 */
public final class Publication {

	private final String sid;

	private final TrackSource source;

	private final LocalTrack track;

	private final Sender sender;


	Publication(String sid, TrackSource source, LocalTrack track, Sender sender) {
		this.sid = sid;
		this.source = source;
		this.track = track;
		this.sender = sender;
	}

	/**
	 * @return the server's id of the track, as other participants see it.
	 */
	public String sid() {
		return sid;
	}

	/**
	 * @return where the track comes from.
	 */
	public TrackSource source() {
		return source;
	}

	/**
	 * @return the published track, which stays the application's.
	 */
	public LocalTrack track() {
		return track;
	}

	/**
	 * @return the rids of the simulcast layers the track is sent in, none
	 * without simulcast.
	 */
	public List<String> rids() {
		return sender.rids();
	}

	Sender sender() {
		return sender;
	}

	@Override
	public String toString() {
		return "Publication[" + sid + ", " + source + ", " + track.id() + "]";
	}

}
