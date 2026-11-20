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

import dev.onvoid.webrtc.livekit.internal.proto.LivekitModels;

/**
 * A simulcast layer of a video track, by LiveKit's names: the server forwards
 * each subscriber one of them.
 */
public enum VideoQuality {

	/** The smallest layer, rid {@code q}. */
	LOW(LivekitModels.VideoQuality.LOW, "q"),

	/** The middle layer, rid {@code h}. */
	MEDIUM(LivekitModels.VideoQuality.MEDIUM, "h"),

	/** The largest layer, rid {@code f}. */
	HIGH(LivekitModels.VideoQuality.HIGH, "f");

	private final LivekitModels.VideoQuality proto;

	private final String rid;

	VideoQuality(LivekitModels.VideoQuality proto, String rid) {
		this.proto = proto;
		this.rid = rid;
	}

	LivekitModels.VideoQuality proto() {
		return proto;
	}

	/**
	 * @return the rid LiveKit's clients send this layer with.
	 */
	String rid() {
		return rid;
	}

	static VideoQuality of(LivekitModels.VideoQuality proto) {
		for (VideoQuality quality : values()) {
			if (quality.proto == proto) {
				return quality;
			}
		}
		return null;
	}

	static VideoQuality ofRid(String rid) {
		for (VideoQuality quality : values()) {
			if (quality.rid.equals(rid)) {
				return quality;
			}
		}
		return null;
	}

}
