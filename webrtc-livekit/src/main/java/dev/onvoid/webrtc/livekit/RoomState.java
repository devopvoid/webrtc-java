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

package dev.onvoid.webrtc.livekit;

/**
 * The connection state of a {@link Room}.
 */
public enum RoomState {

	/** The signaling connection is being opened and the room joined. */
	CONNECTING,

	/** The room is joined. */
	CONNECTED,

	/**
	 * The connection to the server was lost, and the room is getting it back:
	 * by resuming the session, or by joining anew and publishing the tracks
	 * again. Publishing waits until it is back.
	 */
	RECONNECTING,

	/** The room is left, by the application, the server or a failure. Final. */
	DISCONNECTED

}
