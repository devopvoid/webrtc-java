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
 * The RTCSdpType describes the type of an {@link RTCSessionDescription}.
 *
 * @author Alex Andres
 */
public enum RTCSdpType {

	/**
	 * Indicates that a description MUST be treated as an SDP offer.
	 */
	OFFER,

	/**
	 * Indicates that a description MUST be treated as an SDP answer, but not a
	 * final answer. A description used as an SDP PrAnswer may be applied as a
	 * response to an SDP offer, or an update to a previously sent SDP
	 * PrAnswer.
	 */
	PR_ANSWER,

	/**
	 * Indicates that a description MUST be treated as an SDP final answer, and
	 * the offer-answer exchange MUST be considered complete. A description used
	 * as an SDP answer may be applied as a response to an SDP offer or as an
	 * update to a previously sent SDP PrAnswer.
	 */
	ANSWER,

	/**
	 * Indicates that a description MUST be treated as canceling the current SDP
	 * negotiation and moving the SDP offer and answer back to what it was in
	 * the previous stable state. Note the local or remote SDP descriptions in
	 * the previous stable state could be null if there has not yet been a
	 * successful offer-answer negotiation.
	 */
	ROLLBACK;

}
