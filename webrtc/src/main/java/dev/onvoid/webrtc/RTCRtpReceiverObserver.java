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
 * first RTP packet, for example to show media only once it actually flows,
 * and when the sources of its media change.
 * <p>
 * The callbacks run on the peer connection's signaling thread. An exception
 * thrown by one is reported to the thread's uncaught exception handler.
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

	/**
	 * The synchronization or contributing sources of the received media have
	 * changed, so {@link RTCRtpReceiver#getSynchronizationSources()} or
	 * {@link RTCRtpReceiver#getContributingSources()} return something new.
	 * When the observer is set after sources are known, this is called right
	 * away. Does nothing unless implemented.
	 *
	 * @param ssrcChanged Whether the synchronization source has changed.
	 * @param csrcChanged Whether the set of contributing sources has changed.
	 */
	default void onSourceChanged(boolean ssrcChanged, boolean csrcChanged) {

	}

}
