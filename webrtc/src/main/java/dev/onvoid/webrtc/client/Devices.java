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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import dev.onvoid.webrtc.media.audio.AudioDevice;
import dev.onvoid.webrtc.media.video.VideoDevice;
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;

/**
 * The media devices of the system. The lists are snapshots, kept current in
 * the background, so reading them is cheap on any thread. Where the system
 * reports no device changes, as a Linux machine without PulseAudio, they are
 * read on each call. A kind of device the system cannot list, such as audio
 * devices without an audio system, comes back as an empty list.
 */
public interface Devices {

	/**
	 * @return the cameras.
	 */
	List<VideoDevice> cameras();

	/**
	 * @return the microphones.
	 */
	List<AudioDevice> microphones();

	/**
	 * @return the speakers.
	 */
	List<AudioDevice> speakers();

	/**
	 * Asks the system for its screens. Unlike the device lists, this is not a
	 * snapshot.
	 *
	 * @return the screens that can be captured.
	 */
	List<DesktopSource> screens();

	/**
	 * Asks the system for its windows. Unlike the device lists, this is not a
	 * snapshot.
	 *
	 * @return the windows that can be captured.
	 */
	List<DesktopSource> windows();

	/**
	 * Captures from another microphone, also in tracks that are sent now.
	 *
	 * @param device one of {@link #microphones()}.
	 *
	 * @return a future that completes once the microphone is used.
	 */
	CompletableFuture<Void> useMicrophone(AudioDevice device);

	/**
	 * Plays to other speakers.
	 *
	 * @param device one of {@link #speakers()}.
	 *
	 * @return a future that completes once the speakers are used.
	 */
	CompletableFuture<Void> useSpeaker(AudioDevice device);

	/**
	 * Calls the handler after a device was connected or disconnected.
	 *
	 * @param handler called on a background thread with the new snapshot.
	 *
	 * @return the subscription that removes the handler.
	 */
	Subscription onChange(Consumer<Devices> handler);

}
