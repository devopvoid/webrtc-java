/*
 * Copyright 2019 Alex Andres
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
 * Defines the ICE candidate policy the application uses to surface the
 * permitted candidates to the application; only these candidates will be used
 * for connectivity checks.
 *
 * @author Alex Andres
 */
public enum RTCIceTransportPolicy {

	NONE,

	/**
	 * The ICE Agent uses only media relay candidates such as candidates passing
	 * through a TURN server.
	 * <p>
	 * NOTE: This can be used to prevent the remote endpoint from learning the
	 * user's IP addresses, which may be desired in certain use cases. For
	 * example, in a "call"-based application, the application may want to
	 * prevent an unknown caller from learning the callee's IP addresses until
	 * the callee has consented in some way.
	 */
	RELAY,

	NO_HOST,

	/**
	 * The ICE Agent can use any type of candidate when this value is
	 * specified.
	 * <p>
	 * NOTE: The implementation can still use its own candidate filtering policy
	 * in order to limit the IP addresses exposed to the application, as noted
	 * in the description of RTCIceCandidate.address.
	 */
	ALL;

}
