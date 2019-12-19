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

package dev.onvoid.webrtc;

/**
 * The bundle policy affects which media tracks are negotiated if the remote
 * endpoint is not bundle-aware, and what ICE candidates are gathered. If the
 * remote endpoint is bundle-aware, all media tracks and data channels are
 * bundled onto the same transport.
 *
 * @author Alex Andres
 */
public enum RTCBundlePolicy {

	/**
	 * Gather ICE candidates for each media type in use (audio, video, and
	 * data). If the remote endpoint is not bundle-aware, negotiate only one
	 * audio and video track on separate transports.
	 */
	BALANCED,

	/**
	 * Gather ICE candidates for only one track. If the remote endpoint is not
	 * bundle-aware, negotiate only one media track.
	 */
	MAX_BUNDLE,

	/**
	 * Gather ICE candidates for each track. If the remote endpoint is not
	 * bundle-aware, negotiate all media tracks on separate transports.
	 */
	MAX_COMPAT;

}
