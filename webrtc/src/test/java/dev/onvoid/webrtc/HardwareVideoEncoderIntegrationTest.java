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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;
import dev.onvoid.webrtc.media.video.codec.DefaultVideoEncoderFactory;
import dev.onvoid.webrtc.media.video.codec.HardwareVideoEncoderFactory;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests the hardware encoders of the {@link HardwareVideoEncoderFactory}.
 * <p>
 * A machine without a hardware encoder skips the tests that need one, as CI
 * runners do. Set the system property {@code webrtc.test.hardwareEncoder} to
 * {@code true} on a machine that has one, to make those tests fail instead.
 */
@Execution(ExecutionMode.SAME_THREAD)
class HardwareVideoEncoderIntegrationTest extends TestBase {

	private static final long TIMEOUT_SECONDS = 10;

	private static final boolean HARDWARE_REQUIRED = Boolean.getBoolean("webrtc.test.hardwareEncoder");

	private static final String OS = System.getProperty("os.name").toLowerCase(Locale.ROOT);

	/**
	 * H.264 Constrained Baseline with packetization mode 1, which hardware
	 * encoders take over.
	 */
	private static final Predicate<RTCRtpCodecCapability> H264 = codec ->
			"H264".equalsIgnoreCase(codec.getName())
					&& "1".equals(codec.getSDPFmtp().get("packetization-mode"))
					&& codec.getSDPFmtp().getOrDefault("profile-level-id", "").startsWith("42e0");


	@Test
	void hardwareKeepsCodecs() {
		// Hardware encoding must not change what is negotiated.
		assertEquals(new DefaultVideoEncoderFactory().getSupportedCodecs(),
				new HardwareVideoEncoderFactory().getSupportedCodecs());
	}

	@Test
	void hardwareEncodesH264() throws Exception {
		assumeTrue(OS.contains("win"), "hardware encoders are implemented on Windows only");

		PeerConnectionFactory hardware = PeerConnectionFactory.builder()
				.setAudioDeviceModule(audioDevModule)
				.setVideoEncoderFactory(new HardwareVideoEncoderFactory())
				.build();

		try {
			String implementation = encoderImplementation(hardware);

			if (HARDWARE_REQUIRED) {
				assertTrue(implementation.contains("MediaFoundation"), implementation);
			}
			else {
				assumeTrue(implementation.contains("MediaFoundation"), "no hardware encoder: " + implementation);
			}
		}
		finally {
			hardware.dispose();
		}
	}

	@Test
	void defaultEncodesH264InSoftware() throws Exception {
		assumeFalse(OS.contains("mac"), "macOS encodes H.264 through VideoToolbox by default");

		// The shared factory uses the default encoders.
		String implementation = encoderImplementation(factory);

		assertTrue(implementation.contains("OpenH264"), implementation);
	}

	/**
	 * Sends H.264 through a call until frames arrive, and returns what the
	 * sender reports its encoder to be.
	 */
	private static String encoderImplementation(PeerConnectionFactory factory) throws Exception {
		CountDownLatch received = new CountDownLatch(10);

		try (TestMediaCall call = new TestMediaCall(factory, true, false, H264)) {
			call.negotiate();

			RTCRtpReceiver receiver = call.getReceiver("video");
			VideoTrack track = (VideoTrack) receiver.getTrack();
			VideoTrackSink sink = frame -> {
				frame.release();
				received.countDown();
			};
			track.addSink(sink);

			call.awaitConnected();
			call.startMedia();

			assertTrue(received.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "too few frames received");

			// The statistics catch up with the encoder shortly after.
			Object implementation = null;
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

			while (implementation == null && System.nanoTime() < deadline) {
				Map<String, Object> outbound = call.getOutboundVideoStats();

				if (outbound != null) {
					implementation = outbound.get("encoderImplementation");
				}
				if (implementation == null) {
					Thread.sleep(100);
				}
			}

			track.removeSink(sink);
			receiver.dispose();

			return String.valueOf(implementation);
		}
	}

}
