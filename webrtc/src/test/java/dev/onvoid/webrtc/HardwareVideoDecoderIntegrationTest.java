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
import dev.onvoid.webrtc.media.video.codec.VideoDecoderFactory;
import dev.onvoid.webrtc.media.video.codec.VideoEncoder;
import dev.onvoid.webrtc.media.video.codec.VideoEncoderFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
 * and {@code webrtc.test.hardwareAv1Decoder} for a GPU that decodes AV1 (on
 * macOS, Apple M3 and newer),
 * {@code webrtc.test.hardwareVp9Decoder} for one that decodes VP9 on Windows or Linux,
 * and {@code webrtc.test.hardwareH265Decoder} for one that decodes and encodes
 * H.265, which a call in H.265 needs at both ends, having no software codec.
 * On macOS the VP9 tests follow {@code webrtc.test.hardwareDecoder}.
 */
@Execution(ExecutionMode.SAME_THREAD)
class HardwareVideoDecoderIntegrationTest extends TestBase {

	private static final long TIMEOUT_SECONDS = 10;

	private static final boolean HARDWARE_REQUIRED = Boolean.getBoolean("webrtc.test.hardwareDecoder");

	private static final boolean HARDWARE_AV1_REQUIRED = Boolean.getBoolean("webrtc.test.hardwareAv1Decoder");

	private static final boolean HARDWARE_VP9_REQUIRED = Boolean.getBoolean("webrtc.test.hardwareVp9Decoder");

	private static final boolean HARDWARE_H265_REQUIRED = Boolean.getBoolean("webrtc.test.hardwareH265Decoder");

	private static final String OS = System.getProperty("os.name").toLowerCase(Locale.ROOT);

	private static final Predicate<RTCRtpCodecCapability> H264 = codec ->
			"H264".equalsIgnoreCase(codec.getName())
					&& "1".equals(codec.getSDPFmtp().get("packetization-mode"))
					&& codec.getSDPFmtp().getOrDefault("profile-level-id", "").startsWith("42e0");

	private static final Predicate<RTCRtpCodecCapability> AV1 = codec -> "AV1".equalsIgnoreCase(codec.getName());

	private static final Predicate<RTCRtpCodecCapability> VP9 = codec ->
			"VP9".equalsIgnoreCase(codec.getName())
					&& "0".equals(codec.getSDPFmtp().getOrDefault("profile-id", "0"));

	private static final Predicate<RTCRtpCodecCapability> H265 = codec -> "H265".equalsIgnoreCase(codec.getName());


	@Test
	void hardwareKeepsCodecs() {
		// Hardware decoding must not change what is negotiated otherwise: the
		// codecs of the default decoders come first, in their order. After
		// them comes H.265 where the GPU decodes it, which has no software
		// decoder.
		List<VideoCodecInfo> software = new DefaultVideoDecoderFactory().getSupportedCodecs();
		List<VideoCodecInfo> hardware = new HardwareVideoDecoderFactory().getSupportedCodecs();

		assertTrue(hardware.size() >= software.size(), hardware.toString());
		assertEquals(software, hardware.subList(0, software.size()));

		for (VideoCodecInfo added : hardware.subList(software.size(), hardware.size())) {
			assertEquals("H265", added.getName(), hardware.toString());
		}
	}

	@Test
	void hardwareDecodesH265() throws Exception {
		assumeH265();

		PeerConnectionFactory hardware = hardwareCallFactory();
		String implementation;

		try {
			// 640x360: hardware H.265 encoders have a minimum size, and AMD's
			// Media Foundation encoder refuses widths up to 352. WebRTC then
			// switches to the next codec, there being no software H.265.
			implementation = decoderImplementation(hardware, H265, 640, 360, "video/H265");
		}
		finally {
			hardware.dispose();
		}

		assertTrue(isHardware(implementation), implementation);
	}

	@Test
	void hardwareCropsPaddedH265() throws Exception {
		// HEVC codes in blocks of up to 64, so 360 rows come in a larger
		// surface. The encoder is a hardware one too, there being no software
		// H.265, and AMD's pads the coded picture below the 360 rows with
		// neutral chroma itself; its loop filters pull the last chroma row
		// towards that, by up to about 20. That row is left out of the check:
		// a crop at the wrong offset would show across the whole plane.
		assumeH265();

		assertCropsPaddedFrames(hardwareCallFactory(), H265, "video/H265", 1,
				implementation -> assertTrue(isHardware(implementation), implementation));
	}

	/**
	 * Skips the test where the GPU does not both encode and decode H.265, which
	 * a call in it needs at either end, there being no software codec; fails
	 * it where that is required.
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

	private static boolean offersH265(List<VideoCodecInfo> codecs) {
		return codecs.stream().anyMatch(codec -> "H265".equalsIgnoreCase(codec.getName()));
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
	void hardwareDecodesH264() throws Exception {
		assertHardwareDecodes(H264, HARDWARE_REQUIRED);
	}

	@Test
	void hardwareDecodesAv1() throws Exception {
		assertHardwareDecodes(AV1, HARDWARE_AV1_REQUIRED);
	}

	@Test
	void hardwareCropsPaddedH264() throws Exception {
		assertCropsPaddedFrames(H264, implementation -> expectHardware(implementation, HARDWARE_REQUIRED));
	}

	@Test
	void hardwareCropsPaddedAv1() throws Exception {
		assertCropsPaddedFrames(AV1, implementation -> expectHardware(implementation, HARDWARE_AV1_REQUIRED));
	}

	@Test
	void hardwareCropsPaddedVp9() throws Exception {
		assumeVp9Platform();

		assertCropsPaddedFrames(VP9, HardwareVideoDecoderIntegrationTest::expectVp9Hardware);
	}

	/**
	 * Sends 640x360, a height that is not a multiple of 16 or 64, so the
	 * decoder puts the picture into a larger surface, and checks that what
	 * comes out is the picture: the size that was sent (or a scaled-down one
	 * of the same shape), the luma gradient in the first and the last row,
	 * and the flat chroma of the call where it should be. Chroma read from the
	 * wrong offset of the surface would be luma, or padding.
	 */
	private void assertCropsPaddedFrames(Predicate<RTCRtpCodecCapability> codec, Consumer<String> expectHardware)
			throws Exception {
		assertCropsPaddedFrames(hardwareFactory(), codec, null, 0, expectHardware);
	}

	/**
	 * Does what {@link #assertCropsPaddedFrames(Predicate, Consumer)} does, on
	 * a call of the given factory, which this disposes of, leaving the given
	 * number of chroma rows at the bottom of the picture out of the check.
	 * With a MIME type, checks that the call sent that codec.
	 */
	private void assertCropsPaddedFrames(PeerConnectionFactory hardware, Predicate<RTCRtpCodecCapability> codec,
			String mimeType, int exemptChromaRows, Consumer<String> expectHardware) throws Exception {
		final int width = 640;
		final int height = 360;

		CountDownLatch received = new CountDownLatch(30);
		AtomicReference<String> wrong = new AtomicReference<>();
		AtomicInteger padded = new AtomicInteger();
		String implementation;

		try (TestMediaCall call = new TestMediaCall(hardware, true, false, codec)) {
			call.setVideoSize(width, height);
			call.negotiate();

			RTCRtpReceiver receiver = call.getReceiver("video");
			VideoTrack track = (VideoTrack) receiver.getTrack();
			VideoTrackSink sink = frame -> {
				int frameWidth = frame.buffer.getWidth();
				int frameHeight = frame.buffer.getHeight();

				if (frameWidth * height != frameHeight * width) {
					// Not the shape that was sent: padding came along.
					wrong.compareAndSet(null, "a frame of " + frameWidth + "x" + frameHeight);
				}
				else {
					String error = checkPicture(frame.buffer.toI420(), exemptChromaRows);

					if (error != null) {
						wrong.compareAndSet(null, "a " + frameWidth + "x" + frameHeight + " frame with " + error);
					}
					if (frameHeight % 16 != 0) {
						padded.incrementAndGet();
					}
				}

				frame.release();
				received.countDown();
			};
			track.addSink(sink);

			call.awaitConnected();
			call.startMedia();

			assertTrue(received.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "too few frames received");

			implementation = decoderImplementationOf(call);

			if (mimeType != null) {
				// A call goes on in another codec when the encoder fails.
				assertEquals(mimeType, call.getSentVideoCodec());
			}

			track.removeSink(sink);
			receiver.dispose();
		}
		finally {
			hardware.dispose();
		}

		expectHardware.accept(implementation);

		assertNull(wrong.get(), implementation + " decoded " + wrong.get());
		assertTrue(padded.get() > 0, "no frame needed padding");
	}

	/**
	 * Returns what is wrong with the picture of a frame the call sent, or
	 * {@code null}. The given number of chroma rows at the bottom is not
	 * checked.
	 */
	private static String checkPicture(I420Buffer buffer, int exemptChromaRows) {
		ByteBuffer y = buffer.getDataY();
		int lastRow = (buffer.getHeight() - 1) * buffer.getStrideY();

		if (!isRamp(y, 0, buffer.getWidth())) {
			return "no gradient in the first row";
		}
		if (!isRamp(y, lastRow, buffer.getWidth())) {
			return "no gradient in the last row";
		}

		int chromaWidth = (buffer.getWidth() + 1) / 2;
		int chromaHeight = (buffer.getHeight() + 1) / 2 - exemptChromaRows;

		if (!isFlat(buffer.getDataU(), buffer.getStrideU(), chromaWidth, chromaHeight, TestMediaCall.CHROMA_U)) {
			return "U not at " + TestMediaCall.CHROMA_U;
		}
		if (!isFlat(buffer.getDataV(), buffer.getStrideV(), chromaWidth, chromaHeight, TestMediaCall.CHROMA_V)) {
			return "V not at " + TestMediaCall.CHROMA_V;
		}

		return null;
	}

	private static boolean isRamp(ByteBuffer plane, int offset, int length) {
		int min = 255;
		int max = 0;

		for (int x = 0; x < length; x++) {
			int value = plane.get(offset + x) & 0xff;

			min = Math.min(min, value);
			max = Math.max(max, value);
		}

		return max - min > 150;
	}

	private static boolean isFlat(ByteBuffer plane, int stride, int width, int height, int expected) {
		for (int row = 0; row < height; row++) {
			for (int x = 0; x < width; x++) {
				int value = plane.get(row * stride + x) & 0xff;

				// Coding leaves a flat plane close to, not exactly at, its value.
				if (Math.abs(value - expected) > 16) {
					return false;
				}
			}
		}

		return true;
	}

	private static void expectHardware(String implementation, boolean required) {
		if (required) {
			assertTrue(isHardware(implementation), implementation);
		}
		else {
			assumeTrue(isHardware(implementation), "no hardware decoder: " + implementation);
		}
	}

	private void assertHardwareDecodes(Predicate<RTCRtpCodecCapability> codec, boolean required) throws Exception {
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

		assertFollowsResolutionChange(VP9, HardwareVideoDecoderIntegrationTest::expectVp9Hardware);
	}

	@Test
	void hardwareFollowsAv1ResolutionChange() throws Exception {
		assertFollowsResolutionChange(AV1, implementation -> expectHardware(implementation, HARDWARE_AV1_REQUIRED));
	}

	/**
	 * Receives video that halves its size during the call, and checks that the
	 * hardware decoder follows it, rather than handing over to the software
	 * decoder.
	 */
	private void assertFollowsResolutionChange(Predicate<RTCRtpCodecCapability> codec,
			Consumer<String> expectHardware) throws Exception {
		PeerConnectionFactory hardware = PeerConnectionFactory.builder()
				.setAudioDeviceModule(audioDevModule)
				.setVideoDecoderFactory(new HardwareVideoDecoderFactory())
				.build();

		CountDownLatch full = new CountDownLatch(10);
		CountDownLatch half = new CountDownLatch(10);

		try (TestMediaCall call = new TestMediaCall(hardware, true, false, codec)) {
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

			expectHardware.accept(implementation);

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

	/**
	 * Decodes a VP9 stream with a fixed interpolation filter (regular, as
	 * Intel's VA-API encoder codes it), whose numbering in the bitstream
	 * differs from VA-API's. Its frames zoom, so that a wrong filter drifts
	 * from frame to frame: the last frame must match libvpx's.
	 */
	@Test
	void hardwareDecodesVp9FixedInterpolationFilter() throws Exception {
		assumeVp9Platform();

		List<ByteBuffer> stream = readIvf("vp9-fixed-filter.ivf");

		byte[] hardware = lastVp9Frame(new HardwareVideoDecoderFactory(), stream,
				HardwareVideoDecoderIntegrationTest::expectVp9Hardware);
		byte[] software = lastVp9Frame(new DefaultVideoDecoderFactory(), stream, implementation -> {});

		double psnr = psnr(hardware, software);

		assertTrue(psnr > 45, "the last frame differs from libvpx's: " + String.format(Locale.ROOT, "%.1f dB", psnr));
	}

	/**
	 * Sends the recorded VP9 frames through a call whose receiver decodes
	 * with the given factory, and returns the luma of the last frame.
	 */
	private byte[] lastVp9Frame(VideoDecoderFactory decoders, List<ByteBuffer> stream,
			Consumer<String> expectHardware) throws Exception {
		final int width = 320;
		final int height = 180;

		PeerConnectionFactory factory = PeerConnectionFactory.builder()
				.setAudioDeviceModule(audioDevModule)
				.setVideoEncoderFactory(new ReplayEncoderFactory(stream, width, height))
				.setVideoDecoderFactory(decoders)
				.build();

		CountDownLatch received = new CountDownLatch(stream.size());
		AtomicReference<byte[]> last = new AtomicReference<>();
		String implementation;

		try (TestMediaCall call = new TestMediaCall(factory, true, false, VP9)) {
			call.setVideoSize(width, height);
			call.negotiate();

			RTCRtpReceiver receiver = call.getReceiver("video");
			VideoTrack track = (VideoTrack) receiver.getTrack();
			VideoTrackSink sink = frame -> {
				if (received.getCount() == 1) {
					I420Buffer buffer = frame.buffer.toI420();
					byte[] luma = new byte[width * height];

					for (int row = 0; row < height; row++) {
						ByteBuffer line = buffer.getDataY().duplicate();
						line.position(row * buffer.getStrideY());
						line.get(luma, row * width, width);
					}

					last.set(luma);
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
		finally {
			factory.dispose();
		}

		expectHardware.accept(implementation);

		return last.get();
	}

	private static List<ByteBuffer> readIvf(String name) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		try (InputStream input = HardwareVideoDecoderIntegrationTest.class.getResourceAsStream(name)) {
			byte[] chunk = new byte[8192];
			int read;

			while ((read = input.read(chunk)) > 0) {
				bytes.write(chunk, 0, read);
			}
		}

		byte[] file = bytes.toByteArray();

		ByteBuffer data = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
		List<ByteBuffer> frames = new ArrayList<>();
		int position = data.getShort(6);

		while (position + 12 <= file.length) {
			int size = data.getInt(position);
			frames.add(ByteBuffer.wrap(file, position + 12, size).slice());
			position += 12 + size;
		}

		return frames;
	}

	private static double psnr(byte[] a, byte[] b) {
		double error = 0;

		for (int i = 0; i < a.length; i++) {
			int difference = (a[i] & 0xff) - (b[i] & 0xff);
			error += difference * difference;
		}

		return error == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(255.0 * 255.0 * a.length / error);
	}

	/**
	 * Offers VP9 as the built-in factory does, and "encodes" each frame into
	 * the next one of a recorded stream; frames after the end produce nothing.
	 */
	private static final class ReplayEncoderFactory implements VideoEncoderFactory {

		private final DefaultVideoEncoderFactory builtIn = new DefaultVideoEncoderFactory();

		private final List<ByteBuffer> stream;

		private final int width;

		private final int height;


		ReplayEncoderFactory(List<ByteBuffer> stream, int width, int height) {
			this.stream = stream;
			this.width = width;
			this.height = height;
		}

		@Override
		public List<VideoCodecInfo> getSupportedCodecs() {
			return builtIn.getSupportedCodecs();
		}

		@Override
		public VideoEncoder createEncoder(VideoCodecInfo info) {
			if (!"VP9".equalsIgnoreCase(info.getName())) {
				return builtIn.createEncoder(info);
			}

			return new VideoEncoder() {

				private Callback callback;

				private int next;

				@Override
				public VideoCodecStatus initEncode(Settings settings, Callback callback) {
					this.callback = callback;
					return VideoCodecStatus.OK;
				}

				@Override
				public VideoCodecStatus release() {
					return VideoCodecStatus.OK;
				}

				@Override
				public VideoCodecStatus encode(VideoFrame frame, EncodeInfo info) {
					if (next < stream.size()) {
						ByteBuffer payload = ByteBuffer.allocateDirect(stream.get(next).remaining());
						payload.put(stream.get(next).duplicate()).flip();

						callback.onEncodedFrame(EncodedImage.builder()
								.setBuffer(payload)
								.setEncodedWidth(width)
								.setEncodedHeight(height)
								.setCaptureTimeNs(frame.timestampNs)
								.setFrameType(next == 0 ? EncodedImage.FrameType.KEY : EncodedImage.FrameType.DELTA)
								.build());
						next++;
					}

					return VideoCodecStatus.OK;
				}

				@Override
				public VideoCodecStatus setRates(RateControlParameters parameters) {
					return VideoCodecStatus.OK;
				}
			};
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

		if (OS.contains("linux") && result.decoder.startsWith("VA-API")) {
			// VA-API uses the layer sizes WebRTC supplies to decode the layers
			// in order, including references at a different resolution.
			assertTrue(result.pliCount <= 1, "key frames requested: " + result.pliCount);
		}
		else {
			assertFalse(isHardware(result.decoder), result.decoder);
		}
	}

	@Test
	void vaapiDecodesThreeSpatialLayers() throws Exception {
		assumeTrue(OS.contains("linux"), "VA-API is available on Linux only");
		for (String mode : new String[] { "L3T3", "L3T3_KEY" }) {
			CallResult result = receiveVideo(hardwareFactory(), VP9, 1280, 720, mode, 90);
			assertEquals(mode, result.scalability);
			expectVp9Hardware(result.decoder);
			assertTrue(result.decoder.startsWith("VA-API"), result.decoder);
			assertTrue(result.pliCount <= 1, "key frames requested: " + result.pliCount);
		}
	}

	@Test
	void av1NeedsNoKeyFrames() throws Exception {
		CallResult result = receiveVideo(hardwareFactory(), AV1, 320, 240, null, 60);

		expectHardware(result.decoder, HARDWARE_AV1_REQUIRED);

		// As with VP9: a stream that is decoded has the one key frame it
		// started with.
		assertTrue(result.pliCount <= 1, "key frames requested: " + result.pliCount);
		assertTrue(result.keyFrames <= 2, "key frames decoded: " + result.keyFrames);
	}

	@Test
	void av1TemporalLayers() throws Exception {
		// Temporal layers are one spatial layer, which the hardware decodes.
		CallResult result = receiveVideo(hardwareFactory(), AV1, 320, 240, "L1T3", 60);

		expectHardware(result.decoder, HARDWARE_AV1_REQUIRED);

		assertTrue(result.pliCount <= 1, "key frames requested: " + result.pliCount);
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
				|| implementation.contains("NVDEC") || implementation.contains("VA-API");
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
		return decoderImplementation(factory, codec, 320, 240, null);
	}

	/**
	 * Does what {@link #decoderImplementation(PeerConnectionFactory, Predicate)}
	 * does, with video of the given size; 320x240 is the call's default. With
	 * a MIME type, checks that the call sent that codec.
	 */
	private static String decoderImplementation(PeerConnectionFactory factory,
			Predicate<RTCRtpCodecCapability> codec, int sentWidth, int sentHeight, String mimeType) throws Exception {
		CountDownLatch received = new CountDownLatch(10);
		AtomicReference<String> wrongSize = new AtomicReference<>();
		AtomicReference<String> blank = new AtomicReference<>();
		String implementation;

		try (TestMediaCall call = new TestMediaCall(factory, true, false, codec)) {
			call.setVideoSize(sentWidth, sentHeight);
			call.negotiate();

			RTCRtpReceiver receiver = call.getReceiver("video");
			VideoTrack track = (VideoTrack) receiver.getTrack();
			VideoTrackSink sink = frame -> {
				// A decoder that returned the padded frame rather than the
				// picture would be larger than what was sent.
				int width = frame.buffer.getWidth();
				int height = frame.buffer.getHeight();

				if (width != sentWidth || height != sentHeight) {
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

			if (mimeType != null) {
				// A call goes on in another codec when the encoder fails.
				assertEquals(mimeType, call.getSentVideoCodec());
			}

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
		// The first row has the whole ramp, and a wrap of it.
		return isRamp(buffer.getDataY(), 0, buffer.getWidth());
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
