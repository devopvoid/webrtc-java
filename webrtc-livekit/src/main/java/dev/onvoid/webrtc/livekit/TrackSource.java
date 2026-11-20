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
 * Where a published track comes from. Other participants see it, and the
 * server checks it against the sources the token may publish.
 */
public enum TrackSource {

	UNKNOWN(LivekitModels.TrackSource.UNKNOWN),
	CAMERA(LivekitModels.TrackSource.CAMERA),
	MICROPHONE(LivekitModels.TrackSource.MICROPHONE),
	SCREEN_SHARE(LivekitModels.TrackSource.SCREEN_SHARE),
	SCREEN_SHARE_AUDIO(LivekitModels.TrackSource.SCREEN_SHARE_AUDIO);

	private final LivekitModels.TrackSource proto;

	TrackSource(LivekitModels.TrackSource proto) {
		this.proto = proto;
	}

	LivekitModels.TrackSource proto() {
		return proto;
	}

	static TrackSource of(LivekitModels.TrackSource proto) {
		for (TrackSource source : values()) {
			if (source.proto == proto) {
				return source;
			}
		}
		return UNKNOWN;
	}

}
