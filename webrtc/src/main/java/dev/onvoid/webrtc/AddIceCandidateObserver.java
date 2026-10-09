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
 * Callback interface used to get notified when the {@link RTCPeerConnection}
 * has applied, or rejected, a remote ICE candidate passed to {@link
 * RTCPeerConnection#addIceCandidate(RTCIceCandidate, AddIceCandidateObserver)
 * addIceCandidate}.
 * <p>
 * The callbacks run on the peer connection's signaling thread. An exception
 * thrown by them is reported to the thread's uncaught exception handler.
 *
 * @author Alex Andres
 */
public interface AddIceCandidateObserver {

	/**
	 * The candidate has been added to the remote description and handed to
	 * the ICE agent.
	 */
	void onSuccess();

	/**
	 * The candidate could not be added, for example because there is no remote
	 * description yet, or because its media section does not exist.
	 *
	 * @param error The error type in brackets followed by the error message.
	 */
	void onFailure(String error);

}
