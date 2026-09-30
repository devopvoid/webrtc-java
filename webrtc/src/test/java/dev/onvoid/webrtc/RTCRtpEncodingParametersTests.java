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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.onvoid.webrtc.media.MediaType;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;
import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests the encoding parameters of senders: that they reach WebRTC and come
 * back, and that WebRTC acts on those it checks.
 */
@Execution(ExecutionMode.SAME_THREAD)
class RTCRtpEncodingParametersTests extends TestBase {

	private static final long TIMEOUT_SECONDS = 10;

	private TestPeerConnection connection;

	private CustomVideoSource videoSource;

	private VideoTrack videoTrack;

	private AudioTrackSource audioSource;

	private AudioTrack audioTrack;


	@BeforeEach
	void init() {
		connection = new TestPeerConnection(factory);
		videoSource = new CustomVideoSource();
		videoTrack = factory.createVideoTrack("video", videoSource);
	}

	@AfterEach
	void dispose() {
		// The tracks go after the connection, whose senders hold them.
		connection.close();
		videoTrack.dispose();
		videoSource.dispose();

		if (audioTrack != null) {
			audioTrack.dispose();
			audioSource.dispose();
		}
	}

	@Test
	void simulcastEncodingsRoundTrip() {
		RTCRtpTransceiverInit init = new RTCRtpTransceiverInit();
		init.sendEncodings = Arrays.asList(
				encoding("q", 4.0),
				encoding("h", 2.0),
				encoding("f", 1.0));

		// The priorities apply to the whole sender, set on the first encoding.
		init.sendEncodings.get(0).networkPriority = RTCPriorityType.HIGH;
		init.sendEncodings.get(0).bitratePriority = 2.0;
		init.sendEncodings.get(2).maxBitrate = 1_500_000;

		RTCRtpTransceiver transceiver = connection.getPeerConnection().addTransceiver(videoTrack, init);
		RTCRtpSender sender = transceiver.getSender();

		List<RTCRtpEncodingParameters> encodings = sender.getParameters().encodings;

		assertEquals(Arrays.asList("q", "h", "f"),
				encodings.stream().map(encoding -> encoding.rid).collect(Collectors.toList()));
		assertEquals(4.0, encodings.get(0).scaleResolutionDownBy);
		assertEquals(RTCPriorityType.HIGH, encodings.get(0).networkPriority);
		assertEquals(2.0, encodings.get(0).bitratePriority);
		assertEquals(1_500_000, encodings.get(2).maxBitrate);

		sender.dispose();
		transceiver.dispose();
	}

	@Test
	void priorityOnlyOnFirstEncoding() {
		RTCRtpTransceiverInit init = new RTCRtpTransceiverInit();
		init.sendEncodings = Arrays.asList(encoding("h", 2.0), encoding("f", 1.0));
		init.sendEncodings.get(1).networkPriority = RTCPriorityType.HIGH;

		assertThrows(RuntimeException.class, () -> connection.getPeerConnection().addTransceiver(videoTrack, init));
	}

	@Test
	void ridCannotChange() {
		RTCRtpTransceiverInit init = new RTCRtpTransceiverInit();
		init.sendEncodings = Arrays.asList(encoding("h", 2.0), encoding("f", 1.0));

		RTCRtpTransceiver transceiver = connection.getPeerConnection().addTransceiver(videoTrack, init);
		RTCRtpSender sender = transceiver.getSender();

		RTCRtpSendParameters parameters = sender.getParameters();
		parameters.encodings.get(0).rid = "x";

		assertThrows(RuntimeException.class, () -> sender.setParameters(parameters));

		sender.dispose();
		transceiver.dispose();
	}

	@Test
	void resolutionAndDegradationRoundTrip() {
		RTCRtpSender sender = connection.getPeerConnection().addTrack(videoTrack, Collections.singletonList("stream"));

		RTCRtpSendParameters parameters = sender.getParameters();
		parameters.degradationPreference = RTCDegradationPreference.MAINTAIN_RESOLUTION;
		parameters.encodings.get(0).scaleResolutionDownTo = new RTCResolutionRestriction(640, 360);

		sender.setParameters(parameters);

		RTCRtpSendParameters applied = sender.getParameters();
		RTCResolutionRestriction restriction = applied.encodings.get(0).scaleResolutionDownTo;

		assertEquals(RTCDegradationPreference.MAINTAIN_RESOLUTION, applied.degradationPreference);
		assertNotNull(restriction);
		assertEquals(640, restriction.maxWidth);
		assertEquals(360, restriction.maxHeight);

		sender.dispose();
	}

	@Test
	void adaptivePtimeRoundTrips() {
		audioSource = factory.createAudioSource(new AudioOptions());
		audioTrack = factory.createAudioTrack("audio", audioSource);

		RTCRtpSender sender = connection.getPeerConnection().addTrack(audioTrack, Collections.singletonList("stream"));

		RTCRtpSendParameters parameters = sender.getParameters();
		parameters.encodings.get(0).adaptivePtime = true;

		sender.setParameters(parameters);

		assertEquals(Boolean.TRUE, sender.getParameters().encodings.get(0).adaptivePtime);

		sender.dispose();
	}

	@Test
	void scalabilityModeIsApplied() throws Exception {
		CountDownLatch received = new CountDownLatch(10);

		// The call prefers VP8, which encodes up to three temporal layers.
		try (TestMediaCall call = new TestMediaCall(factory, true, false)) {
			call.negotiate();

			RTCRtpReceiver receiver = call.getReceiver("video");
			VideoTrack track = (VideoTrack) receiver.getTrack();
			VideoTrackSink sink = frame -> {
				frame.release();
				received.countDown();
			};
			track.addSink(sink);

			call.awaitConnected();

			RTCRtpSender sender = call.getVideoSender();
			RTCRtpSendParameters parameters = sender.getParameters();
			parameters.encodings.get(0).scalabilityMode = "L1T3";

			sender.setParameters(parameters);

			assertEquals("L1T3", sender.getParameters().encodings.get(0).scalabilityMode);

			call.startMedia();

			assertTrue(received.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "too few frames received");

			track.removeSink(sink);
			receiver.dispose();
		}
	}

	@Test
	void capabilitiesListScalabilityModes() {
		RTCRtpCodecCapability vp8 = factory.getRtpSenderCapabilities(MediaType.VIDEO).getCodecs().stream()
				.filter(codec -> "VP8".equalsIgnoreCase(codec.getName()))
				.findFirst()
				.orElseThrow(IllegalStateException::new);

		assertTrue(vp8.getScalabilityModes().contains("L1T3"), vp8.getScalabilityModes().toString());
		assertFalse(vp8.getScalabilityModes().contains("L3T3"), vp8.getScalabilityModes().toString());
	}

	@Test
	void unsupportedScalabilityModeFails() throws Exception {
		try (TestMediaCall call = new TestMediaCall(factory, true, false)) {
			call.negotiate();
			call.awaitConnected();

			RTCRtpSender sender = call.getVideoSender();
			RTCRtpSendParameters parameters = sender.getParameters();

			// Spatial layers, which VP8 does not have.
			parameters.encodings.get(0).scalabilityMode = "L3T3";

			assertThrows(RuntimeException.class, () -> sender.setParameters(parameters));
		}
	}

	@Test
	void encodingCodecRoundTrips() throws Exception {
		try (TestMediaCall call = new TestMediaCall(factory, true, false)) {
			call.negotiate();
			call.awaitConnected();

			RTCRtpCodecCapability vp8 = factory.getRtpSenderCapabilities(MediaType.VIDEO).getCodecs().stream()
					.filter(codec -> "VP8".equalsIgnoreCase(codec.getName()))
					.findFirst()
					.orElseThrow(IllegalStateException::new);

			RTCRtpSender sender = call.getVideoSender();
			RTCRtpSendParameters parameters = sender.getParameters();
			parameters.encodings.get(0).codec = vp8;

			sender.setParameters(parameters);

			RTCRtpCodecCapability codec = sender.getParameters().encodings.get(0).codec;

			assertNotNull(codec);
			assertEquals("VP8", codec.getName());
		}
	}

	private static RTCRtpEncodingParameters encoding(String rid, double scale) {
		RTCRtpEncodingParameters encoding = new RTCRtpEncodingParameters();
		encoding.rid = rid;
		encoding.scaleResolutionDownBy = scale;

		return encoding;
	}

}
