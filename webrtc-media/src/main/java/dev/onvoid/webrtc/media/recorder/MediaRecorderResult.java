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
 * How a {@link MediaRecorder} recording ended, as {@link
 * MediaRecorder#getResult()} tells once the recorder is stopped.
 *
 * @author Alex Andres
 */
public enum MediaRecorderResult {

	// In the order of the native MediaRecorder::Result.

	/**
	 * Everything the recorder took went into the file, and the file was
	 * finished and closed without an error.
	 */
	COMPLETE,

	/**
	 * Media went into the file, but writing, finishing or closing it failed
	 * later, as {@link MediaRecorderListener#onError(String)} was told. The
	 * file is kept: it may end before the recording was stopped, lack its
	 * index, or miss what the system had not yet written when closing it
	 * failed.
	 */
	PARTIAL,

	/**
	 * Nothing went wrong, but no media arrived to be written, e.g. because
	 * the recorder was stopped right after it was started, or never started.
	 * The file is deleted.
	 */
	EMPTY,

	/**
	 * Writing failed before any media went into the file, e.g. writing its
	 * header. The file is deleted.
	 */
	FAILED;


	/**
	 * Returns whether the recorder kept its file, with media in it.
	 *
	 * @return True for {@link #COMPLETE} and {@link #PARTIAL}.
	 */
	public boolean keepsFile() {
		return this == COMPLETE || this == PARTIAL;
	}

}
