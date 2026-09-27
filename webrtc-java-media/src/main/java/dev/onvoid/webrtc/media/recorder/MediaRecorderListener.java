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

package dev.onvoid.webrtc.media.recorder;

/**
 * What a {@link MediaRecorder} reports while it records.
 * <p>
 * Calls arrive in order on a thread the recorder keeps for them, never on a
 * thread that carries media, so an implementation may take its time and may
 * call back into the recorder, including to stop it.
 *
 * @author Alex Andres
 */
public interface MediaRecorderListener {

	/**
	 * The file header was written and media now goes into the file. This
	 * happens once every track has sent enough to describe its stream (for
	 * video, its first key frame), or once the tracks that have waited a few
	 * seconds for the others.
	 */
	default void onStarted() {
	}

	/**
	 * Something went wrong that the recording carries on without, e.g. a
	 * track whose codec the file cannot hold, which is then left out.
	 *
	 * @param message What went wrong.
	 */
	default void onWarning(String message) {
	}

	/**
	 * Writing the file failed, e.g. because the disk is full. Nothing more
	 * goes into the file; what is in it stays, and {@link MediaRecorder#stop()}
	 * still finishes it as far as it can.
	 *
	 * @param message What went wrong.
	 */
	default void onError(String message) {
	}

}
