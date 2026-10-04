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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests the hardware encoders of the {@link HardwareVideoEncoderFactory}.
 * <p>
 * A machine without a hardware encoder skips the tests that need one, as CI
 * runners do. Set the system property {@code webrtc.test.hardwareEncoder} to
 * {@code true} on a machine that has one, to make those tests fail instead,
 * and {@code webrtc.test.hardwareAv1Encoder} for a GPU that encodes AV1.
 */
@Execution(ExecutionMode.SAME_THREAD)
class HardwareVideoEncoderIntegrationTest extends TestBase {

	private static final long TIMEOUT_SECONDS = 10;

	private static final boolean HARDWARE_REQUIRED = Boolean.getBoolean("webrtc.test.hardwareEncoder");

	private static final boolean HARDWARE_AV1_REQUIRED = Boolean.getBoolean("webrtc.test.hardwareAv1Encoder");

	private static final String OS = System.getProperty("os.name").toLowerCase(Locale.ROOT);

	/**
	 * H.264 Constrained Baseline with packetization mode 1, which hardware
	 * encoders take over.
	 */
	private static final Predicate<RTCRtpCodecCapability> H264 = codec ->
			"H264".equalsIgnoreCase(codec.getName())
					&& "1".equals(codec.getSDPFmtp().get("packetization-mode"))
					&& codec.getSDPFmtp().getOrDefault("profile-level-id", "").startsWith("42e0");

	private static final Predicate<RTCRtpCodecCapability> AV1 = codec -> "AV1".equalsIgnoreCase(codec.getName());


	@Test
	void hardwareKeepsCodecs() {
		// Hardware encoding must not change what is negotiated.
		assertEquals(new DefaultVideoEncoderFactory().getSupportedCodecs(),
				new HardwareVideoEncoderFactory().getSupportedCodecs());
	}

	@Test
	void hardwareEncodesH264() throws Exception {
		assumeTrue(OS.contains("win") || OS.contains("linux"),
				"hardware encoders are implemented on Windows and Linux only");

		PeerConnectionFactory hardware = PeerConnectionFactory.builder()
				.setAudioDeviceModule(audioDevModule)
				.setVideoEncoderFactory(new HardwareVideoEncoderFactory())
				.build();

		try {
			assertHardware(encoderImplementation(hardware, H264), HARDWARE_REQUIRED);
		}
		finally {
			hardware.dispose();
		}
	}

	@Test
	void hardwareEncodesAv1() throws Exception {
		assumeTrue(OS.contains("win") || OS.contains("linux"),
				"hardware encoders are implemented on Windows and Linux only");

		PeerConnectionFactory hardware = PeerConnectionFactory.builder()
				.setAudioDeviceModule(audioDevModule)
				.setVideoEncoderFactory(new HardwareVideoEncoderFactory())
				.build();

		try {
			// Frames arrive either way: from the GPU, or from libaom where the
			// GPU has no AV1 encoder.
			assertHardware(encoderImplementation(hardware, AV1), HARDWARE_AV1_REQUIRED);
		}
		finally {
			hardware.dispose();
		}
	}

	@Test
	void hardwareEncodes1080p() throws Exception {
		assumeNvenc();

		PeerConnectionFactory hardware = hardwareFactory();

		try (Run run = new Run(hardware, H264, 1920, 1080)) {
			run.awaitFrames(10);

			assertHardware(run.implementation(), HARDWARE_REQUIRED);
		}
		finally {
			hardware.dispose();
		}
	}

	@Test
	void hardwareEncodesOddSize() throws Exception {
		assumeNvenc();

		PeerConnectionFactory hardware = hardwareFactory();

		// NV12 needs even dimensions; the encoder asks for them to be aligned
		// and is not left with a size it has to refuse.
		try (Run run = new Run(hardware, H264, 641, 361)) {
			run.awaitFrames(10);

			assertHardware(run.implementation(), HARDWARE_REQUIRED);
			assertEquals(0, run.width.get() % 2, "width " + run.width.get());
		}
		finally {
			hardware.dispose();
		}
	}

	@Test
	void hardwareFollowsResolutionChange() throws Exception {
		assumeNvenc();

		PeerConnectionFactory hardware = hardwareFactory();

		try (Run run = new Run(hardware, H264, 320, 240)) {
			run.awaitFrames(10);

			assertHardware(run.implementation(), HARDWARE_REQUIRED);

			// A new size starts the encoder over, with a new session.
			run.call.setVideoSize(640, 480);
			run.awaitWidth(640);

			run.call.setVideoSize(320, 240);
			run.awaitWidth(320);

			assertHardware(run.implementation(), HARDWARE_REQUIRED);
		}
		finally {
			hardware.dispose();
		}
	}

	@Test
	void hardwareFollowsBitrateCap() throws Exception {
		assumeNvenc();

		PeerConnectionFactory hardware = hardwareFactory();

		try (Run run = new Run(hardware, H264, 640, 480)) {
			run.awaitFrames(10);

			assertHardware(run.implementation(), HARDWARE_REQUIRED);

			// The encoder is reconfigured in place, without a new session or
			// a lost stream.
			run.setMaxBitrate(150_000);
			run.awaitTargetBitrate(150_000);
			run.awaitMoreFrames(10);

			run.setMaxBitrate(2_000_000);
			run.awaitMoreFrames(10);

			assertHardware(run.implementation(), HARDWARE_REQUIRED);
		}
		finally {
			hardware.dispose();
		}
	}

	@Test
	void hardwareRestartsCleanly() throws Exception {
		assumeNvenc();

		PeerConnectionFactory hardware = hardwareFactory();

		try {
			// A session or a CUDA context left behind by each call would run
			// the GPU out of encoder sessions, and the later calls would be
			// encoded in software.
			for (int i = 0; i < 15; i++) {
				try (Run run = new Run(hardware, H264, 320, 240)) {
					run.awaitFrames(5);

					assertHardware(run.implementation(), HARDWARE_REQUIRED);
				}
			}
		}
		finally {
			hardware.dispose();
		}
	}

	@Test
	void hardwareEncodesSeveralStreams() throws Exception {
		assumeNvenc();

		PeerConnectionFactory hardware = hardwareFactory();
		List<Run> runs = new ArrayList<>();

		try {
			for (int i = 0; i < 4; i++) {
				runs.add(new Run(hardware, H264, 320, 240));
			}
			for (Run run : runs) {
				run.awaitFrames(10);

				assertHardware(run.implementation(), HARDWARE_REQUIRED);
			}
		}
		finally {
			for (Run run : runs) {
				run.close();
			}

			hardware.dispose();
		}
	}

	@Test
	void defaultEncodesH264InSoftware() throws Exception {
		assumeFalse(OS.contains("mac"), "macOS encodes H.264 through VideoToolbox by default");

		// The shared factory uses the default encoders.
		String implementation = encoderImplementation(factory, H264);

		assertTrue(implementation.contains("OpenH264"), implementation);
	}

	@Test
	void macEncodesH264WithVideoToolbox() throws Exception {
		assumeTrue(OS.contains("mac"), "VideoToolbox is available on macOS only");

		// The default encoders use VideoToolbox on macOS.
		assertTrue(encoderImplementation(factory, H264).contains("VideoToolbox"));

		// The hardware factory has nothing of its own there, and hands over to
		// the default encoders.
		PeerConnectionFactory hardware = PeerConnectionFactory.builder()
				.setAudioDeviceModule(audioDevModule)
				.setVideoEncoderFactory(new HardwareVideoEncoderFactory())
				.build();

		try {
			assertTrue(encoderImplementation(hardware, H264).contains("VideoToolbox"));
		}
		finally {
			hardware.dispose();
		}
	}

	private static void assumeNvenc() {
		assumeTrue(OS.contains("win") || OS.contains("linux"),
				"hardware encoders are implemented on Windows and Linux only");
	}

	private PeerConnectionFactory hardwareFactory() {
		return PeerConnectionFactory.builder()
				.setAudioDeviceModule(audioDevModule)
				.setVideoEncoderFactory(new HardwareVideoEncoderFactory())
				.build();
	}

	private static void assertHardware(String implementation, boolean required) {
		boolean hardwareUsed = implementation.startsWith("NVENC")
				|| implementation.startsWith("VA-API")
				|| implementation.contains("MediaFoundation");

		if (required) {
			assertTrue(hardwareUsed, implementation);
		}
		else {
			assumeTrue(hardwareUsed, "no hardware encoder: " + implementation);
		}
	}

	/**
	 * Sends video in the preferred codec through a call until frames arrive,
	 * and returns what the sender reports its encoder to be.
	 */
	private static String encoderImplementation(PeerConnectionFactory factory,
			Predicate<RTCRtpCodecCapability> codec) throws Exception {
		CountDownLatch received = new CountDownLatch(10);

		try (TestMediaCall call = new TestMediaCall(factory, true, false, codec)) {
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

	/**
	 * A call that sends video in the given codec and size, and counts what
	 * the receiver gets.
	 */
	private static final class Run implements AutoCloseable {

		final TestMediaCall call;
		final RTCRtpReceiver receiver;
		final VideoTrack track;
		final VideoTrackSink sink;
		final AtomicInteger frames = new AtomicInteger();
		final AtomicInteger width = new AtomicInteger();


		Run(PeerConnectionFactory factory, Predicate<RTCRtpCodecCapability> codec, int width,
				int height) throws Exception {
			call = new TestMediaCall(factory, true, false, codec);
			call.setVideoSize(width, height);
			call.negotiate();

			receiver = call.getReceiver("video");
			track = (VideoTrack) receiver.getTrack();
			sink = frame -> {
				this.width.set(frame.buffer.getWidth());
				frame.release();
				frames.incrementAndGet();
			};
			track.addSink(sink);

			call.awaitConnected();
			call.startMedia();
		}

		void awaitFrames(int count) throws InterruptedException {
			await(() -> frames.get() >= count, "frames: " + frames.get() + " of " + count);
		}

		void awaitMoreFrames(int count) throws InterruptedException {
			awaitFrames(frames.get() + count);
		}

		void awaitWidth(int expected) throws InterruptedException {
			await(() -> width.get() == expected, "width " + width.get() + ", expected " + expected);
		}

		void setMaxBitrate(int bitrate) {
			RTCRtpSendParameters parameters = call.getVideoSender().getParameters();
			parameters.encodings.get(0).maxBitrate = bitrate;

			call.getVideoSender().setParameters(parameters);
		}

		void awaitTargetBitrate(double atMost) throws InterruptedException {
			await(() -> {
				Map<String, Object> outbound = call.getOutboundVideoStats();
				Object target = outbound == null ? null : outbound.get("targetBitrate");

				return target instanceof Number && ((Number) target).doubleValue() <= atMost;
			}, "target bitrate not at or below " + atMost);
		}

		String implementation() throws InterruptedException {
			// The statistics catch up with the encoder shortly after.
			Object[] implementation = new Object[1];

			await(() -> {
				Map<String, Object> outbound = call.getOutboundVideoStats();

				implementation[0] = outbound == null ? null : outbound.get("encoderImplementation");

				return implementation[0] != null;
			}, "no encoder implementation in the statistics");

			return String.valueOf(implementation[0]);
		}

		@Override
		public void close() throws InterruptedException {
			track.removeSink(sink);
			receiver.dispose();
			call.close();
		}

		private static void await(Condition condition, String description) throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

			while (System.nanoTime() < deadline) {
				if (condition.test()) {
					return;
				}

				Thread.sleep(50);
			}

			assertTrue(condition.test(), description);
		}

		private interface Condition {

			boolean test() throws InterruptedException;

		}
	}

}
