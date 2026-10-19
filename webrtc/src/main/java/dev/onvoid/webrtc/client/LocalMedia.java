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

package dev.onvoid.webrtc.client;

import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import dev.onvoid.webrtc.media.video.VideoTrackSource;

/**
 * Creates the tracks a runtime sends, from {@link WebRtc#media()}. Every track
 * belongs to the runtime and is closed with it, unless it was closed before.
 */
public interface LocalMedia {

	/**
	 * Returns the devices. The first call reads them from the system; later
	 * calls return a snapshot that is kept current in the background.
	 *
	 * @return the cameras, microphones and speakers.
	 */
	Devices devices();

	/**
	 * Opens the first camera with its first capability.
	 *
	 * @return the camera's track, capturing.
	 *
	 * @throws MediaException if there is no camera or it cannot be opened.
	 */
	LocalVideo camera();

	/**
	 * Opens a camera. A camera that is already open is shared when the options
	 * match.
	 *
	 * @param options the camera, resolution and frame rate.
	 *
	 * @return the camera's track, capturing.
	 *
	 * @throws MediaException if the camera cannot be opened, or is already open
	 *                        with other options.
	 */
	LocalVideo camera(CameraOptions options);

	/**
	 * Captures the microphone selected in {@link Devices}, with echo
	 * cancellation, noise suppression and gain control.
	 *
	 * @return the microphone's track.
	 *
	 * @throws ConfigurationException if the runtime's {@link AudioMode} is not
	 *                                {@link AudioMode#DEVICES}.
	 */
	LocalAudio microphone();

	/**
	 * Captures the microphone selected in {@link Devices}.
	 *
	 * @param options the audio processing to apply.
	 *
	 * @return the microphone's track.
	 *
	 * @throws ConfigurationException if the runtime's {@link AudioMode} is not
	 *                                {@link AudioMode#DEVICES}.
	 */
	LocalAudio microphone(AudioOptions options);

	/**
	 * Captures a screen or a window.
	 *
	 * @param options the screen or window and the frame rate.
	 *
	 * @return the capture's track.
	 */
	LocalVideo screen(ScreenOptions options);

	/**
	 * Creates a video track fed with frames the application pushes.
	 *
	 * @param label the track label.
	 *
	 * @return the track.
	 */
	CustomVideo customVideo(String label);

	/**
	 * Creates a track over a video source of the low-level API, such as the
	 * video source of a {@code MediaFileSource} of the media module. The
	 * source stays the caller's: closing the track does not dispose it, and it
	 * must outlive the track.
	 *
	 * @param label  the track label.
	 * @param source the source that feeds the track.
	 *
	 * @return the track.
	 */
	LocalVideo videoTrack(String label, VideoTrackSource source);

	/**
	 * Creates a track over an audio source of the low-level API, such as the
	 * audio source of a {@code MediaFileSource} of the media module. The
	 * source stays the caller's: closing the track does not dispose it, and it
	 * must outlive the track.
	 *
	 * @param label  the track label.
	 * @param source the source that feeds the track. A {@link CustomAudioSource}
	 *               needs a runtime built with {@link AudioMode#CUSTOM} or
	 *               {@link AudioMode#HEADLESS}, any other source {@link
	 *               AudioMode#DEVICES}.
	 *
	 * @return the track.
	 *
	 * @throws ConfigurationException if the source does not fit the runtime's
	 *                                {@link AudioMode}.
	 */
	LocalAudio audioTrack(String label, AudioTrackSource source);

	/**
	 * Creates an audio track fed with audio the application pushes.
	 *
	 * @param label the track label.
	 *
	 * @return the track.
	 *
	 * @throws ConfigurationException if the runtime's {@link AudioMode} is
	 *                                {@link AudioMode#DEVICES}.
	 */
	CustomAudio customAudio(String label);

}
