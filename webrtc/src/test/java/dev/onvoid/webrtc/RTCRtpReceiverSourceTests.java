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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import dev.onvoid.webrtc.media.MediaType;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSink;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests what a receiver tells about the sources of its media, and the offer
 * option that keeps offers to the states the WebRTC specification allows.
 */
@Execution(ExecutionMode.SAME_THREAD)
class RTCRtpReceiverSourceTests extends TestBase {

	private static final long TIMEOUT_SECONDS = 10;


	@Test
	void observerHearsSourceChange() throws Exception {
		AtomicBoolean ssrcChanged = new AtomicBoolean();
		CountDownLatch changed = new CountDownLatch(1);

		// Sources are tracked as frames are delivered, which for audio needs
		// playout pulling it; video frames are delivered as they are decoded.
		try (TestMediaCall call = new TestMediaCall(factory, true, false)) {
			call.negotiate();

			RTCRtpReceiver receiver = call.getReceiver("video");
			receiver.setObserver(new RTCRtpReceiverObserver() {
				@Override
				public void onFirstPacketReceived(MediaType mediaType) { }

				@Override
				public void onSourceChanged(boolean ssrc, boolean csrc) {
					if (ssrc) {
						ssrcChanged.set(true);
					}
					changed.countDown();
				}
			});

			call.awaitConnected();
			call.startMedia();

			assertTrue(changed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "no source change reported");
			assertTrue(ssrcChanged.get(), "the first packet did not change the SSRC");

			receiver.setObserver(null);
			receiver.dispose();
		}
	}

	@Test
	void sinkReceivesPacketInfos() throws Exception {
		// Remote audio reaches a sink only while playout pulls it, which the
		// dummy module of the other tests does not do.
		HeadlessAudioDeviceModule audioModule = new HeadlessAudioDeviceModule();
		PeerConnectionFactory headlessFactory = new PeerConnectionFactory(audioModule);

		audioModule.initPlayout();
		audioModule.startPlayout();

		AtomicReference<RTCRtpPacketInfo> info = new AtomicReference<>();
		AtomicInteger withoutPackets = new AtomicInteger();
		CountDownLatch received = new CountDownLatch(10);

		try {
			try (TestMediaCall call = new TestMediaCall(headlessFactory, false, true)) {
				call.negotiate();

				RTCRtpReceiver receiver = call.getReceiver("audio");
				AudioTrack track = (AudioTrack) receiver.getTrack();
				AudioTrackSink sink = new AudioTrackSink() {
					@Override
					public void onData(byte[] data, int bitsPerSample, int sampleRate, int channels, int frames) {
						withoutPackets.incrementAndGet();
					}

					@Override
					public void onData(byte[] data, int bitsPerSample, int sampleRate, int channels, int frames,
							RTCRtpPacketInfo[] packetInfos) {
						if (packetInfos.length > 0) {
							info.compareAndSet(null, packetInfos[0]);
							received.countDown();
						}
					}
				};
				track.addSink(sink);

				call.awaitConnected();
				call.startMedia();

				assertTrue(received.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "no audio with packets received");
				assertEquals(0, withoutPackets.get(), "the sink was also called without packets");

				RTCRtpPacketInfo first = info.get();

				assertTrue(first.ssrc > 0);
				assertTrue(first.receiveTimeMs > 0);
				assertNotNull(first.contributingSources);

				track.removeSink(sink);
				receiver.dispose();
			}
		}
		finally {
			audioModule.stopPlayout();
			headlessFactory.dispose();
			audioModule.dispose();
		}
	}

	@Test
	void offerCanBeRestrictedToStableState() throws Exception {
		TestPeerConnection offerer = new TestPeerConnection(factory);
		TestPeerConnection answerer = new TestPeerConnection(factory);

		try {
			// The answerer now has a remote offer: JSEP lets it create an
			// offer of its own, the WebRTC specification does not.
			answerer.setRemoteDescription(offerer.createOffer());

			RTCOfferOptions restrictedOptions = new RTCOfferOptions();
			restrictedOptions.restrictToStableOrHaveLocalOffer = true;

			TestCreateDescObserver restricted = new TestCreateDescObserver();
			answerer.getPeerConnection().createOffer(restrictedOptions, restricted);

			assertThrows(ExecutionException.class, () -> restricted.get(5, TimeUnit.SECONDS));

			TestCreateDescObserver allowed = new TestCreateDescObserver();
			answerer.getPeerConnection().createOffer(new RTCOfferOptions(), allowed);

			assertNotNull(allowed.get(5, TimeUnit.SECONDS));
		}
		finally {
			offerer.close();
			answerer.close();
		}
	}

}
