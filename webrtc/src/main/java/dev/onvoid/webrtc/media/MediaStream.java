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

package dev.onvoid.webrtc.media;

import dev.onvoid.webrtc.internal.DisposableNativeObject;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.video.VideoTrack;

/**
 * A MediaStream is used to group several MediaStreamTrack objects into one unit
 * that can be recorded or rendered. Each MediaStream can contain zero or more
 * MediaStreamTrack objects.
 *
 * @link https://www.w3.org/TR/mediacapture-streams/#mediastream
 */
public class MediaStream extends DisposableNativeObject {

	private MediaStream() {

	}

	/**
	 * Returns the media stream ID which was initialized when the object was
	 * created.
	 *
	 * @return The media stream ID.
	 */
	public native String id();

	/**
	 * Returns an array of MediaStreamTrack objects representing the audio
	 * tracks in this stream.
	 *
	 * @return The audio tracks in this stream.
	 */
	public native AudioTrack[] getAudioTracks();

	/**
	 * Returns an array of MediaStreamTrack objects representing the video
	 * tracks in this stream.
	 *
	 * @return The video tracks in this stream.
	 */
	public native VideoTrack[] getVideoTracks();

	/**
	 * Adds the given MediaStreamTrack to this MediaStream.
	 *
	 * @param track The track to add.
	 */
	public native void addTrack(MediaStreamTrack track);

	/**
	 * Removes the given MediaStreamTrack object from this MediaStream.
	 *
	 * @param track The track to remove.
	 */
	public native void removeTrack(MediaStreamTrack track);

	@Override
	public native void dispose();

}
