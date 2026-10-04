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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.onvoid.webrtc.media.video.I420Buffer;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;
import dev.onvoid.webrtc.media.video.codec.DefaultVideoDecoderFactory;
import dev.onvoid.webrtc.media.video.codec.HardwareVideoDecoderFactory;

import java.nio.ByteBuffer;
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
 * and {@code webrtc.test.hardwareAv1Decoder} for a GPU that decodes AV1, and
 * {@code webrtc.test.hardwareVp9Decoder} for one that decodes VP9 on Windows or Linux.
 * On macOS the VP9 tests follow {@code webrtc.test.hardwareDecoder}.
 */
@Execution(ExecutionMode.SAME_THREAD)
class HardwareVideoDecoderIntegrationTest extends TestBase {

	private static final long TIMEOUT_SECONDS = 10;

	private static final boolean HARDWARE_REQUIRED = Boolean.getBoolean("webrtc.test.hardwareDecoder");

	private static final boolean HARDWARE_AV1_REQUIRED = Boolean.getBoolean("webrtc.test.hardwareAv1Decoder");

	private static final boolean HARDWARE_VP9_REQUIRED = Boolean.getBoolean("webrtc.test.hardwareVp9Decoder");

	private static final String OS = System.getProperty("os.name").toLowerCase(Locale.ROOT);

	private static final Predicate<RTCRtpCodecCapability> H264 = codec ->
			"H264".equalsIgnoreCase(codec.getName())
					&& "1".equals(codec.getSDPFmtp().get("packetization-mode"))
					&& codec.getSDPFmtp().getOrDefault("profile-level-id", "").startsWith("42e0");

	private static final Predicate<RTCRtpCodecCapability> AV1 = codec -> "AV1".equalsIgnoreCase(codec.getName());

	private static final Predicate<RTCRtpCodecCapability> VP9 = codec ->
			"VP9".equalsIgnoreCase(codec.getName())
					&& "0".equals(codec.getSDPFmtp().getOrDefault("profile-id", "0"));


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
		assumeTrue(OS.contains("win") || OS.contains("linux"),
				"hardware decoders are implemented on Windows and Linux only");

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

		boolean hardwareUsed = isHardware(implementation);

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

		// The hardware factory has nothing of its own for H.264 there, and
		// hands over to the default decoders.
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

	@Test
	void hardwareDecodesVp9() throws Exception {
		assumeVp9Platform();

		PeerConnectionFactory hardware = hardwareFactory();
		String implementation;

		try {
			implementation = decoderImplementation(hardware, VP9);
		}
		finally {
			hardware.dispose();
		}

		expectVp9Hardware(implementation);
	}

	@Test
	void defaultDecodesVp9InSoftware() throws Exception {
		assumeVp9Platform();

		// Hardware decoding is opt-in; the shared factory decodes VP9 with libvpx.
		String implementation = decoderImplementation(factory, VP9);

		assertFalse(isHardware(implementation), implementation);
	}

	@Test
	void hardwareFollowsResolutionChange() throws Exception {
		assumeVp9Platform();

		PeerConnectionFactory hardware = PeerConnectionFactory.builder()
				.setAudioDeviceModule(audioDevModule)
				.setVideoDecoderFactory(new HardwareVideoDecoderFactory())
				.build();

		CountDownLatch full = new CountDownLatch(10);
		CountDownLatch half = new CountDownLatch(10);

		try (TestMediaCall call = new TestMediaCall(hardware, true, false, VP9)) {
			// Large enough that half the size is still one every hardware
			// decoder takes: NVDEC does not decode VP9 below 128 pixels on the
			// shorter side, and falls back to libvpx for such a stream.
			call.setVideoSize(640, 480);
			call.negotiate();

			RTCRtpReceiver receiver = call.getReceiver("video");
			VideoTrack track = (VideoTrack) receiver.getTrack();
			VideoTrackSink sink = frame -> {
				int width = frame.buffer.getWidth();

				if (width == 640 && frame.buffer.getHeight() == 480) {
					full.countDown();
				}
				else if (width == 320 && frame.buffer.getHeight() == 240) {
					half.countDown();
				}

				frame.release();
			};
			track.addSink(sink);

			call.awaitConnected();
			call.startMedia();

			assertTrue(full.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "too few frames received");

			String implementation = decoderImplementationOf(call);

			expectVp9Hardware(implementation);

			// The sender restarts at half the size, with a key frame; the
			// decoder has to start a session for it.
			RTCRtpSender sender = call.getVideoSender();
			RTCRtpSendParameters parameters = sender.getParameters();

			for (RTCRtpEncodingParameters encoding : parameters.encodings) {
				encoding.scaleResolutionDownBy = 2.0;
			}

			sender.setParameters(parameters);

			assertTrue(half.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "no frames at the new size");

			// Still the hardware decoder, not a fallback.
			assertTrue(isHardware(decoderImplementationOf(call)));

			track.removeSink(sink);
			receiver.dispose();
		}
		finally {
			hardware.dispose();
		}
	}

	@Test
	void vp9NeedsNoKeyFrames() throws Exception {
		assumeVp9Platform();

		CallResult result = receiveVideo(hardwareFactory(), VP9, 320, 240, null, 60);

		expectVp9Hardware(result.decoder);

		// An inter frame that fails to decode makes the receiver ask for a key
		// frame, and every key frame is then the only frame that decodes. A
		// stream that is decoded has the one key frame it started with.
		assertTrue(result.pliCount <= 1, "key frames requested: " + result.pliCount);
		assertTrue(result.keyFrames <= 2, "key frames decoded: " + result.keyFrames);
	}

	@Test
	void vp9TemporalLayers() throws Exception {
		assumeVp9Platform();

		// Temporal layers are one spatial layer, which the hardware decodes.
		CallResult result = receiveVideo(hardwareFactory(), VP9, 320, 240, "L1T3", 60);

		expectVp9Hardware(result.decoder);

		assertTrue(result.pliCount <= 1, "key frames requested: " + result.pliCount);
	}

	@Test
	void vp9SpatialLayers() throws Exception {
		assumeVp9Platform();

		// WebRTC encodes spatial layers only above a size, and drops them
		// below it.
		CallResult result = receiveVideo(hardwareFactory(), VP9, 640, 480, "L2T2", 60);

		assumeTrue(result.scalability.startsWith("L2"), "no spatial layers were encoded: " + result.scalability);

		// The frames arrive, from libvpx: the layers of a frame come without a
		// superframe index, which VideoToolbox does not decode, and which a
		// Media Foundation or NVDEC decoder is not known to.
		assertFalse(isHardware(result.decoder), result.decoder);
	}

	private PeerConnectionFactory hardwareFactory() {
		return PeerConnectionFactory.builder()
				.setAudioDeviceModule(audioDevModule)
				.setVideoDecoderFactory(new HardwareVideoDecoderFactory())
				.build();
	}

	/**
	 * Skips the test where VP9 is not decoded in hardware: macOS and Windows.
	 */
	private static void assumeVp9Platform() {
		assumeTrue(OS.contains("mac") || OS.contains("win") || OS.contains("linux"),
				"VP9 is decoded in hardware on macOS, Windows and Linux only");
	}

	private static boolean isHardware(String implementation) {
		return implementation.contains("VideoToolbox") || implementation.contains("MediaFoundation")
				|| implementation.contains("NVDEC");
	}

	/**
	 * Where a hardware decoder is required, the test fails without one;
	 * elsewhere it is skipped.
	 */
	private static void expectVp9Hardware(String implementation) {
		boolean required = OS.contains("mac") ? HARDWARE_REQUIRED : HARDWARE_VP9_REQUIRED;

		if (required) {
			assertTrue(isHardware(implementation), implementation);
		}
		else {
			assumeTrue(isHardware(implementation), "no hardware decoder: " + implementation);
		}
	}

	/**
	 * What a call that received video tells about its decoder.
	 */
	private static final class CallResult {

		String decoder = "";
		String scalability = "";
		long pliCount;
		long keyFrames;

	}

	/**
	 * Receives the given number of frames through a call that sends video of
	 * the given size, and reads what the receiver reports. Disposes the
	 * factory.
	 */
	private static CallResult receiveVideo(PeerConnectionFactory hardware, Predicate<RTCRtpCodecCapability> codec,
			int width, int height, String scalabilityMode, int frames) throws Exception {
		CountDownLatch received = new CountDownLatch(frames);
		CallResult result = new CallResult();

		try (TestMediaCall call = new TestMediaCall(hardware, true, false, codec)) {
			call.setVideoSize(width, height);
			call.negotiate();

			RTCRtpReceiver receiver = call.getReceiver("video");
			VideoTrack track = (VideoTrack) receiver.getTrack();
			VideoTrackSink sink = frame -> {
				frame.release();
				received.countDown();
			};
			track.addSink(sink);

			call.awaitConnected();

			if (scalabilityMode != null) {
				RTCRtpSender sender = call.getVideoSender();
				RTCRtpSendParameters parameters = sender.getParameters();
				parameters.encodings.get(0).scalabilityMode = scalabilityMode;

				sender.setParameters(parameters);
			}

			call.startMedia();

			assertTrue(received.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "too few frames received");

			result.decoder = decoderImplementationOf(call);

			Map<String, Object> inbound = call.getInboundVideoStats();
			Map<String, Object> outbound = call.getOutboundVideoStats();

			result.pliCount = count(inbound, "pliCount");
			result.keyFrames = count(inbound, "keyFramesDecoded");
			result.scalability = outbound == null ? "" : String.valueOf(outbound.get("scalabilityMode"));

			track.removeSink(sink);
			receiver.dispose();
		}
		finally {
			hardware.dispose();
		}

		return result;
	}

	private static long count(Map<String, Object> stats, String name) {
		Object value = stats == null ? null : stats.get(name);

		return value instanceof Number ? ((Number) value).longValue() : 0;
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
		AtomicReference<String> blank = new AtomicReference<>();
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
				else if (!hasPicture(frame.buffer.toI420())) {
					blank.compareAndSet(null, "a frame without a picture");
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
		assertNull(blank.get(), "decoded " + blank.get());

		return implementation;
	}

	/**
	 * Whether the luma of the frame has the gradient the call sends, rather
	 * than a flat or empty picture. The buffer of a frame is I420 already, so
	 * it is read as it is and stays with the frame.
	 */
	private static boolean hasPicture(I420Buffer buffer) {
		ByteBuffer y = buffer.getDataY();
		int min = 255;
		int max = 0;

		// The first row has the whole ramp, and a wrap of it.
		for (int x = 0; x < buffer.getWidth(); x++) {
			int value = y.get(x) & 0xff;

			min = Math.min(min, value);
			max = Math.max(max, value);
		}

		return max - min > 150;
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
