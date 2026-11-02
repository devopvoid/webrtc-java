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

package dev.onvoid.webrtc.media.audio;

/**
 * How the audio of a track reaches an {@link AudioTrackSink}, chosen when the
 * sink is added with {@link AudioTrack#addSink(AudioTrackSink,
 * AudioDataAccess)}.
 */
public enum AudioDataAccess {

	/**
	 * Each call hands the sink a new array, which the sink may keep, for
	 * example in a queue for another thread. This costs an array per chunk
	 * and sink, a hundred a second.
	 */
	COPY,

	/**
	 * The sink gets the same array in every call, filled with the new audio
	 * each time, so that no array is allocated for it per chunk. The array
	 * holds the audio only until the call returns: a sink that keeps the
	 * audio beyond that copies it. A new array takes the place of the old one
	 * when the size of the chunks changes, so its length is always that of
	 * the audio. Should a source hand the sink audio on two threads at once,
	 * the call that finds the array in use gets one of its own.
	 */
	REUSED

}
