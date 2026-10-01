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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;
import dev.onvoid.webrtc.media.video.codec.DefaultVideoDecoderFactory;
import dev.onvoid.webrtc.media.video.codec.HardwareVideoDecoderFactory;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests the hardware decoders of the {@link HardwareVideoDecoderFactory}.
 * <p>
 * A machine without a hardware decoder skips the tests that need one, as CI
 * runners do. Set the system property {@code webrtc.test.hardwareDecoder} to
 * {@code true} on a machine that has one, to make those tests fail instead,
 * and {@code webrtc.test.hardwareAv1Decoder} for a GPU that decodes AV1.
 */
@Execution(ExecutionMode.SAME_THREAD)
class HardwareVideoDecoderIntegrationTest extends TestBase {

	private static final long TIMEOUT_SECONDS = 10;

	private static final boolean HARDWARE_REQUIRED = Boolean.getBoolean("webrtc.test.hardwareDecoder");

	private static final boolean HARDWARE_AV1_REQUIRED = Boolean.getBoolean("webrtc.test.hardwareAv1Decoder");

	private static final String OS = System.getProperty("os.name").toLowerCase(Locale.ROOT);

	private static final Predicate<RTCRtpCodecCapability> H264 = codec ->
			"H264".equalsIgnoreCase(codec.getName())
					&& "1".equals(codec.getSDPFmtp().get("packetization-mode"))
					&& codec.getSDPFmtp().getOrDefault("profile-level-id", "").startsWith("42e0");

	private static final Predicate<RTCRtpCodecCapability> AV1 = codec -> "AV1".equalsIgnoreCase(codec.getName());


	@Test
	void hardwareKeepsCodecs() {
		// Hardware decoding must not change what is negotiated.
		assertEquals(new DefaultVideoDecoderFactory().getSupportedCodecs(),
				new HardwareVideoDecoderFactory().getSupportedCodecs());
	}

	@Test
	void hardwareDecodesH264() throws Exception {
		assertHardwareDecodes(H264, HARDWARE_REQUIRED);
	}

	@Test
	void hardwareDecodesAv1() throws Exception {
		assertHardwareDecodes(AV1, HARDWARE_AV1_REQUIRED);
	}

	private void assertHardwareDecodes(Predicate<RTCRtpCodecCapability> codec, boolean required) throws Exception {
		assumeTrue(OS.contains("win"), "hardware decoders are implemented on Windows only");

		PeerConnectionFactory hardware = PeerConnectionFactory.builder()
				.setAudioDeviceModule(audioDevModule)
				.setVideoDecoderFactory(new HardwareVideoDecoderFactory())
				.build();

		String implementation;

		try {
			implementation = decoderImplementation(hardware, codec);
		}
		finally {
			hardware.dispose();
		}

		boolean hardwareUsed = implementation.contains("MediaFoundation");

		if (required) {
			assertTrue(hardwareUsed, implementation);
		}
		else {
			assumeTrue(hardwareUsed, "no hardware decoder: " + implementation);
		}
	}

	@Test
	void macDecodesH264WithVideoToolbox() throws Exception {
		assumeTrue(OS.contains("mac"), "VideoToolbox is available on macOS only");

		// The default decoders use VideoToolbox on macOS.
		assertTrue(decoderImplementation(factory, H264).contains("VideoToolbox"));

		// The hardware factory has nothing of its own there, and hands over to
		// the default decoders.
		PeerConnectionFactory hardware = PeerConnectionFactory.builder()
				.setAudioDeviceModule(audioDevModule)
				.setVideoDecoderFactory(new HardwareVideoDecoderFactory())
				.build();

		try {
			assertTrue(decoderImplementation(hardware, H264).contains("VideoToolbox"));
		}
		finally {
			hardware.dispose();
		}
	}

	/**
	 * Receives video in the preferred codec through a call, checks that the
	 * decoded frames are the size that was sent, and returns what the receiver
	 * reports its decoder to be.
	 */
	private static String decoderImplementation(PeerConnectionFactory factory,
			Predicate<RTCRtpCodecCapability> codec) throws Exception {
		CountDownLatch received = new CountDownLatch(10);
		AtomicReference<String> wrongSize = new AtomicReference<>();
		String implementation;

		try (TestMediaCall call = new TestMediaCall(factory, true, false, codec)) {
			call.negotiate();

			RTCRtpReceiver receiver = call.getReceiver("video");
			VideoTrack track = (VideoTrack) receiver.getTrack();
			VideoTrackSink sink = frame -> {
				// The call sends 320x240; a decoder that returned the padded
				// frame rather than the picture would be larger.
				int width = frame.buffer.getWidth();
				int height = frame.buffer.getHeight();

				if (width != 320 || height != 240) {
					wrongSize.compareAndSet(null, width + "x" + height);
				}

				frame.release();
				received.countDown();
			};
			track.addSink(sink);

			call.awaitConnected();
			call.startMedia();

			assertTrue(received.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "too few frames received");

			implementation = decoderImplementationOf(call);

			track.removeSink(sink);
			receiver.dispose();
		}

		assertNull(wrongSize.get(), "decoded frames of the wrong size: " + wrongSize.get());

		return implementation;
	}

	/**
	 * Returns what the receiver reports its decoder to be, once the
	 * statistics have caught up with it.
	 */
	private static String decoderImplementationOf(TestMediaCall call) throws InterruptedException {
		Object implementation = null;
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

		while (implementation == null && System.nanoTime() < deadline) {
			Map<String, Object> inbound = call.getInboundVideoStats();

			if (inbound != null) {
				implementation = inbound.get("decoderImplementation");
			}
			if (implementation == null) {
				Thread.sleep(100);
			}
		}

		return String.valueOf(implementation);
	}

}
