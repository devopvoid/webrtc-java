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
 * When a description created by the {@link Negotiator} is handed back.
 */
public enum Gathering {

	/** At once. Candidates follow through the {@link Signaling}, if there is one. */
	TRICKLE,

	/**
	 * Once ICE gathering is complete, with all local candidates in the
	 * description, as servers that take no trickled candidates need. It waits
	 * at most for the {@link PeerOptions.Builder#gatheringTimeout(java.time.Duration)
	 * gathering timeout}, and then hands back the candidates gathered so far.
	 */
	COMPLETE

}
