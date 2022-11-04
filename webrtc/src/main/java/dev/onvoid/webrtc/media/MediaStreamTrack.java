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

/**
 * The MediaStreamTrack represents media of a single type that originates from
 * one media source, e.g. video produced by a web camera.
 *
 * @link https://www.w3.org/TR/mediacapture-streams/#mediastreamtrack
 *
 * @author Alex Andres
 */
public abstract class MediaStreamTrack extends DisposableNativeObject {

	/**
	 * Field used to store a pointer to the native listener. Don't modify this
	 * value directly, or else you risk causing segfaults or memory leaks.
	 */
	@SuppressWarnings("unused")
	private long listenerNativeHandle;

	/**
	 * If this object represents an audio track.
	 */
	public static final String AUDIO_TRACK_KIND = "audio";

	/**
	 * If this object represents a video track.
	 */
	public static final String VIDEO_TRACK_KIND = "video";


	protected MediaStreamTrack() {

	}

	/**
	 * Add a listener to observe the mute condition of this track.
	 *
	 * @param listener The listener to add.
	 */
	public void addTrackMuteListener(MediaStreamTrackMuteListener listener) {
		addMuteEventListener(listener);
	}

	/**
	 * Remove a listener that observes the mute condition of this track.
	 *
	 * @param listener The listener to remove.
	 */
	public void removeTrackMuteListener(MediaStreamTrackMuteListener listener) {
		removeMuteEventListener(listener);
	}

	/**
	 * Add a listener to get notified when this track ends.
	 *
	 * @param listener The listener to add.
	 */
	public void addTrackEndedListener(MediaStreamTrackEndedListener listener) {
		addEndedEventListener(listener);
	}

	/**
	 * Remove a listener that gets notified when this track ends.
	 *
	 * @param listener The listener to remove.
	 */
	public void removeTrackEndedListener(MediaStreamTrackEndedListener listener) {
		removeEndedEventListener(listener);
	}

	@Override
	public native void dispose();

	/**
	 * Returns the string "audio" if this object represents an audio track or
	 * "video" if this object represents a video track.
	 *
	 * @return "audio" if this object is an audio track or "video" if this
	 * object is a video track.
	 */
	public native String getKind();

	/**
	 * When a MediaStreamTrack is created, the application must generate an
	 * identifier string, and must initialize the object's id attribute to that
	 * string, unless the object is created as part of a special purpose
	 * algorithm that specifies how the stream id must be initialized.
	 *
	 * @return The identifier string of this track.
	 */
	public native String getId();

	/**
	 * Indicates if this media track is enabled or not.
	 *
	 * @return true if enabled, false otherwise.
	 */
	public native boolean isEnabled();

	/**
	 * Controls the enabled state for the media track. A disabled track will
	 * produce silence (if audio) or black frames (if video). After a
	 * MediaStreamTrack has ended, its enabled attribute still changes value
	 * when set; it just doesn't do anything wit that new value.
	 *
	 * @param enable The new value.
	 */
	public native void setEnabled(boolean enable);

	/**
	 * Represents the state of the media track. A track will never be live again
	 * after becoming ended.
	 *
	 * @return The current media track state.
	 */
	public native MediaStreamTrackState getState();

	private native void addEndedEventListener(MediaStreamTrackEndedListener listener);
	private native void removeEndedEventListener(MediaStreamTrackEndedListener listener);

	private native void addMuteEventListener(MediaStreamTrackMuteListener listener);
	private native void removeMuteEventListener(MediaStreamTrackMuteListener listener);

}
