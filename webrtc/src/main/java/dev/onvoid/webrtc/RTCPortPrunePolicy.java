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

package dev.onvoid.webrtc;

/**
 * How ICE prunes TURN ports, as {@link RTCConfiguration#turnPortPrunePolicy}
 * takes it.
 *
 * @author Alex Andres
 */
public enum RTCPortPrunePolicy {

	/**
	 * Keep all TURN ports.
	 */
	NO_PRUNE,

	/**
	 * Prune TURN ports of lower priority on the same network.
	 */
	PRUNE_BASED_ON_PRIORITY,

	/**
	 * Keep the first TURN port that is ready on each network, and prune the
	 * others.
	 */
	KEEP_FIRST_READY

}
