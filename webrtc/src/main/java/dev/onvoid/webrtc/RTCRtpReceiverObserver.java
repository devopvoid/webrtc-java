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

import dev.onvoid.webrtc.media.MediaType;

/**
 * Callback interface to learn when an {@link RTCRtpReceiver} receives its
 * first RTP packet, for example to show media only once it actually flows.
 * <p>
 * The callback runs on the peer connection's signaling thread. An exception
 * thrown by it is reported to the thread's uncaught exception handler.
 *
 * @author Alex Andres
 *
 * @see RTCRtpReceiver#setObserver(RTCRtpReceiverObserver)
 */
public interface RTCRtpReceiverObserver {

	/**
	 * The receiver has received its first RTP packet. When the observer is
	 * set after that has happened, this is called right away.
	 *
	 * @param mediaType The media type of the receiver.
	 */
	void onFirstPacketReceived(MediaType mediaType);

}
