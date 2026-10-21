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

/**
 * Who drives the offer and answer exchange of a peer.
 */
public enum Negotiation {

	/**
	 * The peer negotiates by itself, with perfect negotiation, whenever tracks
	 * or channels change. Requires a {@link Signaling}.
	 */
	AUTOMATIC,

	/**
	 * The application drives the exchange through {@link Peer#negotiator()},
	 * as WHIP, WHEP and most SFUs require.
	 */
	MANUAL

}
