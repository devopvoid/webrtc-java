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

import java.util.concurrent.CompletableFuture;

/**
 * Sends one track on one peer, made by {@link Peer#addTrack}.
 */
public interface Sender {

	/**
	 * @return the track sent now, or null if nothing is sent.
	 */
	LocalTrack track();

	/**
	 * Sends another track of the same kind, without renegotiating.
	 *
	 * @param track the track to send, or null to send nothing.
	 *
	 * @return a future that completes once the track is replaced.
	 */
	CompletableFuture<Void> replaceTrack(LocalTrack track);

	/**
	 * Changes how the track is encoded. Values left unset stay as they are.
	 *
	 * @param encoding the values to change.
	 *
	 * @return a future that completes once the values are applied.
	 */
	CompletableFuture<Void> configure(Encoding encoding);

	/**
	 * Stops sending. With {@link Negotiation#AUTOMATIC} the peer renegotiates.
	 * The track stays open.
	 *
	 * @return a future that completes once the sender is removed.
	 */
	CompletableFuture<Void> remove();

}
