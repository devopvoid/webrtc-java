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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import dev.onvoid.webrtc.livekit.internal.proto.LivekitModels.ParticipantInfo;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitModels.TrackInfo;

/**
 * A participant of a {@link Room}, the local one or a remote one. Its name,
 * metadata and attributes follow the updates of the server.
 */
public final class Participant {

	private final String sid;

	private final String identity;

	private volatile String name = "";

	private volatile String metadata = "";

	private volatile Map<String, String> attributes = Collections.emptyMap();

	private volatile Set<String> trackSids = Collections.emptySet();


	Participant(ParticipantInfo info) {
		this.sid = info.getSid();
		this.identity = info.getIdentity();

		update(info);
	}

	/**
	 * @return the server's id of this participant in this session.
	 */
	public String sid() {
		return sid;
	}

	/**
	 * @return the identity the token names.
	 */
	public String identity() {
		return identity;
	}

	/**
	 * @return the display name, empty if none.
	 */
	public String name() {
		return name;
	}

	/**
	 * @return the metadata, empty if none.
	 */
	public String metadata() {
		return metadata;
	}

	/**
	 * @return the attributes, as of the last update.
	 */
	public Map<String, String> attributes() {
		return attributes;
	}

	@Override
	public String toString() {
		return "Participant[" + identity + ", " + sid + "]";
	}

	void update(ParticipantInfo info) {
		name = info.getName();
		metadata = info.getMetadata();
		attributes = Collections.unmodifiableMap(new HashMap<>(info.getAttributesMap()));
		trackSids = info.getTracksList().stream().map(TrackInfo::getSid).collect(Collectors.toUnmodifiableSet());
	}

	boolean publishes(String trackSid) {
		return trackSids.contains(trackSid);
	}

}
