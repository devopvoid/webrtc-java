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
 * Callback interface used to get notified when the {@link RTCPeerConnection}
 * has successfully set an local or remote {@link RTCSessionDescription} by
 * calling either {@link RTCPeerConnection#setLocalDescription
 * setLocalDescription} or {@link RTCPeerConnection#setRemoteDescription
 * setRemoteDescription}.
 *
 * @author Alex Andres
 */
public interface SetSessionDescriptionObserver {

	/**
	 * The local/remote session description has been successfully set.
	 */
	void onSuccess();

	/**
	 * An error has occurred, causing the RTCPeerConnection to abort the SDP
	 * setting procedure.
	 *
	 * @param error The error message.
	 */
	void onFailure(String error);

}
