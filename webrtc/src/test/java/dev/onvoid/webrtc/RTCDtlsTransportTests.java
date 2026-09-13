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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link RTCDtlsTransport} class.
 */
class RTCDtlsTransportTests extends TestBase {

	private TestPeerConnection caller;
	private TestPeerConnection callee;

	private RTCDtlsTransport transport;


	@BeforeEach
	void init() throws Exception {
		caller = new TestPeerConnection(factory);
		callee = new TestPeerConnection(factory);

		AudioTrackSource audioSource = factory.createAudioSource(new AudioOptions());
		AudioTrack audioTrack = factory.createAudioTrack("audioTrack", audioSource);

		List<String> streamIds = new ArrayList<>();
		streamIds.add("stream1");

		RTCRtpSender sender = caller.getPeerConnection().addTrack(audioTrack, streamIds);

		caller.setRemotePeerConnection(callee);
		callee.setRemotePeerConnection(caller);

		callee.setRemoteDescription(caller.createOffer());
		caller.setRemoteDescription(callee.createAnswer());

		caller.waitUntilConnected();
		callee.waitUntilConnected();

		transport = sender.getTransport();
	}

	@AfterEach
	void dispose() {
		caller.close();
		callee.close();
	}

	@Test
	void replaceObserverStopsPreviousObserver() {
		assertNotNull(transport);

		AtomicInteger firstObserverCalls = new AtomicInteger();
		AtomicInteger secondObserverCalls = new AtomicInteger();

		RTCDtlsTransportObserver first = new RTCDtlsTransportObserver() {
			@Override
			public void onStateChange(RTCDtlsTransportState info) {
				firstObserverCalls.incrementAndGet();
			}

			@Override
			public void onError(String error) { }
		};
		RTCDtlsTransportObserver second = new RTCDtlsTransportObserver() {
			@Override
			public void onStateChange(RTCDtlsTransportState info) {
				secondObserverCalls.incrementAndGet();
			}

			@Override
			public void onError(String error) { }
		};

		transport.registerObserver(first);

		// Replacing the observer must unregister and free the previous
		// native observer wrapper instead of leaking it and leaving it
		// registered alongside the new one.
		assertDoesNotThrow(() -> transport.registerObserver(second));

		int firstCallsAfterReplace = firstObserverCalls.get();

		// Registering and unregistering repeatedly must not leak or crash;
		// this used to leak the previous native observer on every call.
		for (int i = 0; i < 50; i++) {
			transport.registerObserver(second);
		}

		transport.unregisterObserver();

		// Unregistering with nothing registered anymore must be a safe no-op.
		assertDoesNotThrow(transport::unregisterObserver);

		assertEquals(firstCallsAfterReplace, firstObserverCalls.get(),
				"The replaced observer must not receive further events");
	}

}
