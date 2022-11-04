/*
 * Copyright 2022 Alex Andres
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

package dev.onvoid.webrtc.media;

/**
 * A 'mute' condition change listener is notified whenever a media track has
 * been (un)muted.
 *
 * @author Alex Andres
 */
@FunctionalInterface
public interface MediaStreamTrackMuteListener {

	/**
	 * The 'mute' condition has changed for the given track.
	 *
	 * @param track The track that has been (un)muted.
	 * @param muted True if the track has been muted.
	 */
	void onTrackMute(MediaStreamTrack track, boolean muted);

}
