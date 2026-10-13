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
 * Receives events from an {@link RTCDtlsTransport}.
 * <p>
 * The events arrive in order on a native callback thread that all DTLS
 * transport and data channel send observers share. An observer may call other
 * WebRTC methods, but should not block, since that holds up the other
 * observers. Once {@link RTCDtlsTransport#unregisterObserver()} returns, the
 * observer receives no more events.
 *
 * @author Alex Andres
 */
public interface RTCDtlsTransportObserver {

	/**
	 * The transport state has changed.
	 *
	 * @param info The information about the state of the transport.
	 */
	void onStateChange(RTCDtlsTransportState info);

	/**
	 * An error has occurred, causing the transport to go to the {@code FAILED}
	 * state.
	 *
	 * @param error The error message.
	 */
	void onError(String error);

}
