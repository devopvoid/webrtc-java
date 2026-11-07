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

package dev.onvoid.webrtc.client;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCRtpCodecCapability;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCRtpTransceiverInit;
import dev.onvoid.webrtc.media.MediaType;

import org.junit.jupiter.api.Test;

class CodecPreferencesTests extends ClientTestBase {

	@Test
	void releasesUnvisitedTransceivers() {
		RTCPeerConnection connection = rtc.factory.createPeerConnection(new RTCConfiguration(),
				new PeerConnectionObserver() {
					@Override
					public void onIceCandidate(RTCIceCandidate candidate) { }
				});
		try {
			for (int i = 0; i < 3; i++) {
				connection.addTransceiver(MediaType.AUDIO, new RTCRtpTransceiverInit()).dispose();
			}
			RTCRtpTransceiver[] transceivers = connection.getTransceivers();
			try {
				// Video codecs on an audio transceiver fail in setCodecPreferences.
				List<RTCRtpCodecCapability> video = rtc.factory.getRtpReceiverCapabilities(MediaType.VIDEO).getCodecs();
				Map<MediaKind, List<RTCRtpCodecCapability>> codecs = Collections.singletonMap(MediaKind.AUDIO, video);
				assertThrows(Error.class, () -> CodecPreferences.apply(transceivers, codecs));
				for (RTCRtpTransceiver transceiver : transceivers) {
					assertThrows(IllegalStateException.class, transceiver::getMid);
				}
			}
			finally {
				for (RTCRtpTransceiver transceiver : transceivers) {
					transceiver.dispose();
				}
			}
		}
		finally {
			connection.close();
		}
	}
}
