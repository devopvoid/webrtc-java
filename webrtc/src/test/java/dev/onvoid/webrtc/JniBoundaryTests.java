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

import static org.junit.jupiter.api.Assertions.*;

import dev.onvoid.webrtc.internal.AllocationFault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.SAME_THREAD)
class JniBoundaryTests extends TestBase {

	@Test
	void getterTranslatesAllocationFailure() {
		RTCPeerConnection connection = factory.createPeerConnection(new RTCConfiguration(), candidate -> { });
		RTCDataChannel channel = connection.createDataChannel("boundary", new RTCDataChannelInit());
		RTCRtpTransceiver transceiver = connection.addTransceiver(
				dev.onvoid.webrtc.media.MediaType.AUDIO, new RTCRtpTransceiverInit());
		RTCRtpSender sender = transceiver.getSender();
		try {
			// Both getters used to acquire their leases outside any exception boundary.
			AllocationFault.failNextNativeLease();
			assertEquals(Error.class, assertThrows(Error.class, channel::isReliable).getClass());
			assertTrue(channel.isReliable());
			AllocationFault.failNextNativeLease();
			assertEquals(Error.class, assertThrows(Error.class, sender::getTrack).getClass());
			assertNull(sender.getTrack());
		}
		finally {
			AllocationFault.disarm();
			sender.dispose();
			transceiver.dispose();
			channel.dispose();
			connection.close();
		}
	}
}
