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

package dev.onvoid.webrtc.media.video;

/**
 * What a video track shows, which decides what its senders keep when the
 * network or the CPU cannot keep up: the frame rate or the resolution. See
 * <a href="https://w3c.github.io/mst-content-hint/">MediaStreamTrack Content
 * Hints</a>.
 * <p>
 * A {@link dev.onvoid.webrtc.RTCRtpSendParameters#degradationPreference
 * degradation preference} set on a sender takes precedence over the hint in
 * that choice.
 *
 * @author Alex Andres
 *
 * @see VideoTrack#setContentHint(VideoContentHint)
 */
public enum VideoContentHint {

	/**
	 * No hint: the source decides. Screen shares keep the resolution, other
	 * video the frame rate.
	 */
	DEFAULT,

	/**
	 * Motion, such as a camera, a video or an animation: keep the frame rate,
	 * and lower the resolution where necessary.
	 */
	MOTION,

	/**
	 * Detail, such as drawings, slides or photos: keep the resolution, and
	 * lower the frame rate where necessary.
	 */
	DETAIL,

	/**
	 * Text, such as source code, a terminal or tables: keep the resolution,
	 * as with {@link #DETAIL}, which WebRTC treats it as for now, with every
	 * codec. No hint keeps text readable at any bitrate.
	 */
	TEXT

}
