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
 * Describes the state of the signaling process on the local end of the
 * RTCPeerConnection.
 *
 * @author Alex Andres
 */
public enum RTCSignalingState {

	/**
	 * There is no offer/answer exchange in progress. This is also the initial
	 * state, in which case the local and remote descriptions are empty.
	 */
	STABLE,

	/**
	 * A local description, of type "offer", has been successfully applied.
	 */
	HAVE_LOCAL_OFFER,

	/**
	 * A remote description of type "offer" has been successfully applied and a
	 * local description of type "pranswer" has been successfully applied.
	 */
	HAVE_LOCAL_PR_ANSWER,

	/**
	 * A remote description, of type "offer", has been successfully applied.
	 */
	HAVE_REMOTE_OFFER,

	/**
	 * A local description of type "offer" has been successfully applied and a
	 * remote description of type "pranswer" has been successfully applied.
	 */
	HAVE_REMOTE_PR_ANSWER,

	/**
	 * The RTCPeerConnection has been closed.
	 */
	CLOSED;

}
