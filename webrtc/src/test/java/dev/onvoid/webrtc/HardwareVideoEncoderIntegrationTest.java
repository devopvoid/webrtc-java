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
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;
import dev.onvoid.webrtc.media.video.codec.DefaultVideoDecoderFactory;
import dev.onvoid.webrtc.media.video.codec.DefaultVideoEncoderFactory;
import dev.onvoid.webrtc.media.video.codec.EncodedImage;
import dev.onvoid.webrtc.media.video.codec.HardwareVideoDecoderFactory;
import dev.onvoid.webrtc.media.video.codec.HardwareVideoEncoderFactory;
import dev.onvoid.webrtc.media.video.codec.VideoCodecInfo;
import dev.onvoid.webrtc.media.video.codec.VideoCodecStatus;
import dev.onvoid.webrtc.media.video.codec.VideoDecoder;
import dev.onvoid.webrtc.media.video.codec.VideoDecoderFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
 * {@code webrtc.test.hardwareAv1Encoder} for a GPU that encodes AV1,
 * {@code webrtc.test.hardwareH265Encoder} for one that encodes and decodes
 * H.265, which a call in H.265 needs at both ends, having no software codec,
 * and {@code webrtc.test.hardwareVp9Encoder} for one that encodes VP9 (Intel,
 * on Windows), which is tested with hardware VP9 encoding turned on.
 */
@Execution(ExecutionMode.SAME_THREAD)
class HardwareVideoEncoderIntegrationTest extends TestBase {

	private static final long TIMEOUT_SECONDS = 10;

	private static final boolean HARDWARE_REQUIRED = Boolean.getBoolean("webrtc.test.hardwareEncoder");

	private static final boolean HARDWARE_AV1_REQUIRED = Boolean.getBoolean("webrtc.test.hardwareAv1Encoder");

	private static final boolean HARDWARE_H265_REQUIRED = Boolean.getBoolean("webrtc.test.hardwareH265Encoder");

	private static final boolean HARDWARE_VP9_REQUIRED = Boolean.getBoolean("webrtc.test.hardwareVp9Encoder");

	/** The property that turns on hardware VP9 encoding, which is off by default. */
	private static final String VP9_ENCODER_PROPERTY = "webrtc.hardware.vp9Encoder";

	/** The property that turns on VA-API's codecs beyond H.264, off by default. */
	private static final String VAAPI_EXPERIMENTAL_PROPERTY = "webrtc.hardware.vaapiExperimental";

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

	private static final Predicate<RTCRtpCodecCapability> H265 = codec -> "H265".equalsIgnoreCase(codec.getName());

	private static final Predicate<RTCRtpCodecCapability> VP9 = codec ->
			"VP9".equalsIgnoreCase(codec.getName())
					&& "0".equals(codec.getSDPFmtp().getOrDefault("profile-id", "0"));


	@Test
	void hardwareKeepsCodecs() {
		// Hardware encoding must not change which codec is preferred: the
		// codecs of the default encoders come first, in their order. After
		// them comes H.265 where the GPU encodes it, which has no software
		// encoder.
		List<VideoCodecInfo> software = new DefaultVideoEncoderFactory().getSupportedCodecs();
		List<VideoCodecInfo> hardware = new HardwareVideoEncoderFactory().getSupportedCodecs();

		assertTrue(hardware.size() >= software.size(), hardware.toString());
		assertEquals(software, hardware.subList(0, software.size()));

		for (VideoCodecInfo added : hardware.subList(software.size(), hardware.size())) {
			assertEquals("H265", added.getName(), hardware.toString());
		}
	}

	@Test
	void softwareOffersNoH265() {
		// H.265 is encoded and decoded in hardware only, never in software.
		assertFalse(offersH265(new DefaultVideoEncoderFactory().getSupportedCodecs()));
		assertFalse(offersH265(new DefaultVideoDecoderFactory().getSupportedCodecs()));
	}

	@Test
	void hardwareEncodesH265() throws Exception {
		assumeH265();

		PeerConnectionFactory hardware = hardwareCallFactory();

		// 640x360: hardware H.265 encoders have a minimum size, and AMD's
		// Media Foundation encoder refuses widths up to 352. WebRTC then
		// switches to the next codec, there being no software H.265.
		try (Run run = new Run(hardware, H265, 640, 360)) {
			run.awaitFrames(10);

			assertH265(run);
		}
		finally {
			hardware.dispose();
		}
	}

	@Test
	void h265EncodesOddSize() throws Exception {
		assumeH265();

		PeerConnectionFactory hardware = hardwareCallFactory();

		// 4:2:0 needs even dimensions, which the encoder asks for, rather than
		// refusing the size and ending the H.265 stream.
		try (Run run = new Run(hardware, H265, 641, 361)) {
			run.awaitFrames(10);

			assertH265(run);
			assertEquals(0, run.width.get() % 2, "width " + run.width.get());
		}
		finally {
			hardware.dispose();
		}
	}

	@Test
	void h265FollowsResolutionChange() throws Exception {
		assumeH265();

		PeerConnectionFactory hardware = hardwareCallFactory();

		try (Run run = new Run(hardware, H265, 640, 360)) {
			run.awaitFrames(10);

			// A new size starts the encoder over, with a new session, and the
			// decoder with new parameter sets.
			run.call.setVideoSize(960, 540);
			run.awaitWidth(960);

			run.call.setVideoSize(640, 360);
			run.awaitWidth(640);

			assertH265(run);
		}
		finally {
			hardware.dispose();
		}
	}

	@Test
	void h265FollowsBitrateCap() throws Exception {
		assumeH265();

		PeerConnectionFactory hardware = hardwareCallFactory();

		try (Run run = new Run(hardware, H265, 640, 360)) {
			run.awaitFrames(10);

			// The encoder is reconfigured in place, without a new session or
			// a lost stream.
			run.setMaxBitrate(150_000);
			run.awaitTargetBitrate(150_000);
			run.awaitMoreFrames(10);

			run.setMaxBitrate(2_000_000);
			run.awaitMoreFrames(10);

			assertH265(run);
		}
		finally {
			hardware.dispose();
		}
	}

	@Test
	void h265RestartsCleanly() throws Exception {
		assumeH265();

		PeerConnectionFactory hardware = hardwareCallFactory();

		try {
			// A session left behind by each call would run the hardware out of
			// sessions, and the later calls would not be in H.265.
			for (int i = 0; i < 10; i++) {
				try (Run run = new Run(hardware, H265, 640, 360)) {
					run.awaitFrames(5);

					assertH265(run);
				}
			}
		}
		finally {
			hardware.dispose();
		}
	}

	/**
	 * Skips the test where the hardware does not both encode and decode H.265,
	 * which a call in it needs at either end, there being no software codec;
	 * fails it where that is required.
	 */
	private static void assumeH265() {
		boolean available = offersH265(new HardwareVideoEncoderFactory().getSupportedCodecs())
				&& offersH265(new HardwareVideoDecoderFactory().getSupportedCodecs());

		if (HARDWARE_H265_REQUIRED) {
			assertTrue(available, "no hardware H.265 encoder and decoder");
		}
		else {
			assumeTrue(available, "no hardware H.265 encoder and decoder");
		}
	}

	/**
	 * Checks that the call is still in H.265, which it would not be after an
	 * encoder failure, and is encoded in hardware.
	 */
	private static void assertH265(Run run) throws InterruptedException {
		assertEquals("video/H265", run.codec());
		assertHardware(run.implementation(), true);
	}

	/** A factory that encodes as well as decodes in hardware. */
	private PeerConnectionFactory hardwareCallFactory() {
		return PeerConnectionFactory.builder()
				.setAudioDeviceModule(audioDevModule)
				.setVideoEncoderFactory(new HardwareVideoEncoderFactory())
				.setVideoDecoderFactory(new HardwareVideoDecoderFactory())
				.build();
	}

	@Test
	void vp9HardwareEncodingIsOptIn() throws Exception {
		assumeTrue(OS.contains("win") || OS.contains("linux"),
				"hardware encoders are implemented on Windows and Linux only");
		assumeTrue(System.getProperty(VP9_ENCODER_PROPERTY) == null, VP9_ENCODER_PROPERTY + " is set");

		PeerConnectionFactory hardware = hardwareFactory();

		try {
			// Without the property, VP9 stays with libvpx on every GPU.
			String implementation = encoderImplementation(hardware, VP9);

			assertTrue(implementation.contains("libvpx"), implementation);
		}
		finally {
			hardware.dispose();
		}
	}

	@Test
	void hardwareEncodesVp9WhenEnabled() throws Exception {
		assumeTrue(OS.contains("win"), "hardware VP9 encoding is implemented on Windows only");

		String previous = System.setProperty(VP9_ENCODER_PROPERTY, "true");

		try {
			PeerConnectionFactory hardware = hardwareFactory();

			try {
				// Only Intel GPUs encode VP9; elsewhere libvpx does, and the
				// test is skipped unless hardware is required.
				assertHardware(encoderImplementation(hardware, VP9), HARDWARE_VP9_REQUIRED);
			}
			finally {
				hardware.dispose();
			}
		}
		finally {
			if (previous == null) {
				System.clearProperty(VP9_ENCODER_PROPERTY);
			}
			else {
				System.setProperty(VP9_ENCODER_PROPERTY, previous);
			}
		}
	}

	@Test
	void hardwareH265EncoderSendsWholeAccessUnits() throws Exception {
		assumeTrue(OS.contains("win") || OS.contains("linux"),
				"hardware encoders are implemented on Windows and Linux only");

		// On Linux, VA-API's H.265 encoder is there only when turned on.
		String previous = System.setProperty(VAAPI_EXPERIMENTAL_PROPERTY, "true");

		try {
			// The receiver needs no H.265 decoder of its own: a Java decoder
			// checks what arrives, so that a GPU that encodes but does not
			// decode H.265 is tested as well.
			boolean available = offersH265(new HardwareVideoEncoderFactory().getSupportedCodecs());

			if (HARDWARE_H265_REQUIRED) {
				assertTrue(available, "no hardware H.265 encoder");
			}
			else {
				assumeTrue(available, "no hardware H.265 encoder");
			}

			H265CheckingDecoderFactory decoders = new H265CheckingDecoderFactory();
			PeerConnectionFactory hardware = PeerConnectionFactory.builder()
					.setAudioDeviceModule(audioDevModule)
					.setVideoEncoderFactory(new HardwareVideoEncoderFactory())
					.setVideoDecoderFactory(decoders)
					.build();

			// 640x360, which hardware H.265 encoders take (see above).
			try (Run run = new Run(hardware, H265, 640, 360)) {
				run.awaitFrames(10);

				assertHardware(run.implementation(), true);
			}
			finally {
				hardware.dispose();
			}

			assertNull(decoders.problem.get(), decoders.problem.get());
			assertTrue(decoders.keyFrames.get() > 0, "no key frame arrived");
		}
		finally {
			if (previous == null) {
				System.clearProperty(VAAPI_EXPERIMENTAL_PROPERTY);
			}
			else {
				System.setProperty(VAAPI_EXPERIMENTAL_PROPERTY, previous);
			}
		}
	}

	private static boolean offersH265(List<VideoCodecInfo> codecs) {
		return codecs.stream().anyMatch(codec -> "H265".equalsIgnoreCase(codec.getName()));
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

		// The hardware factory has nothing of its own for H.264 there, and
		// hands over to the default encoders.
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
				|| implementation.contains("MediaFoundation")
				|| implementation.contains("VideoToolbox");

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

		String codec() throws InterruptedException {
			String[] codec = new String[1];

			await(() -> {
				codec[0] = call.getSentVideoCodec();

				return codec[0] != null;
			}, "no codec in the statistics");

			return codec[0];
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



	/**
	 * Receives H.265 without decoding it: checks that every key frame is a
	 * whole access unit, with its parameter sets (VPS, SPS, PPS) and an IRAP
	 * picture, and hands back a blank frame of the encoded size for each
	 * frame, so that the call goes on. Every other codec goes to the default
	 * decoders.
	 */
	private static final class H265CheckingDecoderFactory implements VideoDecoderFactory {

		final DefaultVideoDecoderFactory builtIn = new DefaultVideoDecoderFactory();
		final AtomicInteger keyFrames = new AtomicInteger();
		final AtomicReference<String> problem = new AtomicReference<>();


		@Override
		public List<VideoCodecInfo> getSupportedCodecs() {
			Map<String, String> parameters = new HashMap<>();
			parameters.put("profile-id", "1");
			parameters.put("tier-flag", "0");
			parameters.put("level-id", "93");
			parameters.put("tx-mode", "SRST");

			List<VideoCodecInfo> codecs = new ArrayList<>(builtIn.getSupportedCodecs());
			codecs.add(new VideoCodecInfo("H265", parameters));
			return codecs;
		}

		@Override
		public VideoDecoder createDecoder(VideoCodecInfo info) {
			if (!"H265".equalsIgnoreCase(info.getName())) {
				return builtIn.createDecoder(info);
			}

			return new VideoDecoder() {

				private Callback callback;
				private int width = 640;
				private int height = 360;


				@Override
				public VideoCodecStatus initDecode(Settings settings, Callback callback) {
					this.callback = callback;
					return VideoCodecStatus.OK;
				}

				@Override
				public VideoCodecStatus release() {
					return VideoCodecStatus.OK;
				}

				@Override
				public VideoCodecStatus decode(EncodedImage image) {
					Set<Integer> types = nalUnitTypes(image.getBuffer());

					if (image.getFrameType() == EncodedImage.FrameType.KEY) {
						boolean parameterSets = types.contains(32) && types.contains(33) && types.contains(34);
						boolean irap = types.stream().anyMatch(type -> type >= 16 && type <= 23);

						if (parameterSets && irap) {
							keyFrames.incrementAndGet();
						}
						else {
							problem.compareAndSet(null, "a key frame with the NAL unit types " + types);
						}
					}
					else if (types.isEmpty()) {
						problem.compareAndSet(null, "a frame without NAL units");
					}

					if (image.getEncodedWidth() > 0 && image.getEncodedHeight() > 0) {
						width = image.getEncodedWidth();
						height = image.getEncodedHeight();
					}

					VideoFrame frame = new VideoFrame(NativeI420Buffer.allocate(width, height),
							image.getCaptureTimeNs());
					callback.onDecodedFrame(frame, null, null);
					frame.release();

					return VideoCodecStatus.OK;
				}
			};
		}

		/** The types of the NAL units of an access unit in Annex B form. */
		private static Set<Integer> nalUnitTypes(ByteBuffer accessUnit) {
			Set<Integer> types = new TreeSet<>();
			ByteBuffer data = accessUnit.duplicate();
			int limit = data.limit();

			for (int i = data.position(); i + 3 < limit; i++) {
				if (data.get(i) == 0 && data.get(i + 1) == 0 && data.get(i + 2) == 1) {
					types.add((data.get(i + 3) >> 1) & 0x3F);
					i += 2;
				}
			}

			return types;
		}

	}

}
