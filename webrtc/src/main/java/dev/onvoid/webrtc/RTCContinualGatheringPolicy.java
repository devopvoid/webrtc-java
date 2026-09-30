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
 * Whether ICE keeps gathering candidates after the first ones, as {@link
 * RTCConfiguration#continualGatheringPolicy} takes it.
 *
 * @author Alex Andres
 */
public enum RTCContinualGatheringPolicy {

	/**
	 * Gather once, when ICE starts.
	 */
	GATHER_ONCE,

	/**
	 * Keep gathering, so that a network that comes up later, or a changed
	 * address, gets candidates too, which lets a connection survive a network
	 * change.
	 */
	GATHER_CONTINUALLY

}
