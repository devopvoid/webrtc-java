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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.onvoid.webrtc.logging.Logging;
import dev.onvoid.webrtc.media.MediaType;
import dev.onvoid.webrtc.media.video.I420Buffer;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoFrameBuffer;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;
import dev.onvoid.webrtc.media.video.codec.DefaultVideoDecoderFactory;
import dev.onvoid.webrtc.media.video.codec.DefaultVideoEncoderFactory;
import dev.onvoid.webrtc.media.video.codec.EncodedImage;
import dev.onvoid.webrtc.media.video.codec.HardwareVideoDecoderFactory;
import dev.onvoid.webrtc.media.video.codec.HardwareVideoEncoderFactory;
import dev.onvoid.webrtc.media.video.codec.NativeVideoEncoder;
import dev.onvoid.webrtc.media.video.codec.VideoCodecInfo;
import dev.onvoid.webrtc.media.video.codec.VideoCodecStatus;
import dev.onvoid.webrtc.media.video.codec.VideoDecoder;
import dev.onvoid.webrtc.media.video.codec.VideoDecoderFactory;
import dev.onvoid.webrtc.media.video.codec.VideoEncoder;
import dev.onvoid.webrtc.media.video.codec.VideoEncoderFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests video codec factories, built-in and implemented in Java, on calls
 * between two local peer connections.
 */
@Execution(ExecutionMode.SAME_THREAD)
class VideoCodecFactoryTests extends TestBase {

	private static final long TIMEOUT_SECONDS = 10;

	/** The codec of {@link TestEncoder} and {@link TestDecoder}. */
	private static final String TEST_CODEC = "X-TEST";

	/**
	 * H.265 as WebRTC offers it, which {@link TestEncoder} and {@link
	 * TestDecoder} speak as well, framing their payload in H.265 NAL units.
	 */
	private static final VideoCodecInfo H265 = new VideoCodecInfo("H265", h265Parameters());

	/** What precedes the payload in the slice of an H.265 test access unit. */
	private static final String H265_MARKER = "WRTC:";


	@Test
	void defaultFactoriesListBuiltInCodecs() {
		List<String> encoders = names(new DefaultVideoEncoderFactory().getSupportedCodecs());
		List<String> decoders = names(new DefaultVideoDecoderFactory().getSupportedCodecs());

		assertTrue(encoders.contains("VP8"), encoders.toString());
		assertTrue(encoders.contains("VP9"), encoders.toString());
		assertTrue(decoders.contains("VP8"), decoders.toString());
		assertTrue(decoders.contains("VP9"), decoders.toString());
	}

	@Test
	void hardwareFactoriesListHardwareCodecs() {
		HardwareVideoEncoderFactory encoders = new HardwareVideoEncoderFactory();
		HardwareVideoDecoderFactory decoders = new HardwareVideoDecoderFactory();

		assertHardwareCodecs(new DefaultVideoEncoderFactory().getSupportedCodecs(),
				encoders.getSupportedCodecs(), encoders.getHardwareCodecs());
		assertHardwareCodecs(new DefaultVideoDecoderFactory().getSupportedCodecs(),
				decoders.getSupportedCodecs(), decoders.getHardwareCodecs());
	}

	@Test
	void hardwareDecodesNoH264High444() {
		// GPU decoders take 4:2:0 only, so H.264 High 4:4:4 Predictive (profile
		// 0xf4) stays with the software decoder.
		for (VideoCodecInfo codec : new HardwareVideoDecoderFactory().getHardwareCodecs()) {
			String profileLevelId = codec.getParameters().getOrDefault("profile-level-id", "");

			assertFalse("H264".equalsIgnoreCase(codec.getName()) && profileLevelId.startsWith("f4"),
					codec.toString());
		}
	}

	@Test
	void defaultFactoryCreatesNativeCodecs() {
		DefaultVideoEncoderFactory factory = new DefaultVideoEncoderFactory();

		VideoEncoder encoder = factory.createEncoder(new VideoCodecInfo("VP8"));

		assertInstanceOf(NativeVideoEncoder.class, encoder);
		assertEquals("VP8", ((NativeVideoEncoder) encoder).getCodecInfo().getName());
		assertThrows(UnsupportedOperationException.class, encoder::release);

		assertNull(factory.createEncoder(new VideoCodecInfo(TEST_CODEC)));
	}

	@Test
	void builtFactoryUsesDefaultCodecs() {
		PeerConnectionFactory built = PeerConnectionFactory.builder()
				.setAudioDeviceModule(audioDevModule)
				.setVideoEncoderFactory(new DefaultVideoEncoderFactory())
				.setVideoDecoderFactory(new DefaultVideoDecoderFactory())
				.build();

		try {
			assertEquals(
					names(factory.getRtpSenderCapabilities(MediaType.VIDEO).getCodecs()),
					names(built.getRtpSenderCapabilities(MediaType.VIDEO).getCodecs()));
		}
		finally {
			built.dispose();
		}
	}

	@Test
	void customCodecIsAdvertised() {
		PeerConnectionFactory built = testCodecFactory(new TestEncoderFactory(), new TestDecoderFactory());

		try {
			List<String> send = names(built.getRtpSenderCapabilities(MediaType.VIDEO).getCodecs());
			List<String> receive = names(built.getRtpReceiverCapabilities(MediaType.VIDEO).getCodecs());

			assertTrue(send.contains(TEST_CODEC), send.toString());
			assertTrue(send.contains("VP8"), send.toString());
			assertTrue(receive.contains(TEST_CODEC), receive.toString());
		}
		finally {
			built.dispose();
		}
	}

	@Test
	void javaCodecRoundTrips() throws Exception {
		assertRoundTrip(new VideoCodecInfo(TEST_CODEC), false);
	}

	@Test
	void javaFrameBufferRoundTrips() throws Exception {
		// The decoder hands back frames whose buffer is no NativeI420Buffer,
		// which WebRTC has to copy.
		assertRoundTrip(new VideoCodecInfo(TEST_CODEC), true);
	}

	@Test
	void h265CallRoundTrips() throws Exception {
		// WebRTC has no H.265 codec of its own, so this is H.265 as a codec
		// factory brings it: negotiated, packetized into RTP and depacketized
		// as H.265, which WebRTC does only when built with it.
		assertRoundTrip(H265, false);
	}

	/**
	 * Sends a call through the test codec and checks that every frame
	 * received is one that was sent, and how WebRTC used the codecs.
	 */
	private void assertRoundTrip(VideoCodecInfo codec, boolean javaBuffers) throws Exception {
		TestEncoderFactory encoderFactory = new TestEncoderFactory(codec);
		TestDecoderFactory decoderFactory = new TestDecoderFactory(codec);
		decoderFactory.javaBuffers = javaBuffers;
		PeerConnectionFactory built = testCodecFactory(encoderFactory, decoderFactory);

		Set<String> encodedSizes = ConcurrentHashMap.newKeySet();
		Set<Integer> encodedLuma = ConcurrentHashMap.newKeySet();
		AtomicReference<String> mismatch = new AtomicReference<>();
		CountDownLatch received = new CountDownLatch(10);

		encoderFactory.onEncode = (frame, luma) -> {
			encodedSizes.add(frame.buffer.getWidth() + "x" + frame.buffer.getHeight());
			encodedLuma.add(luma);
		};

		try (TestMediaCall call = new TestMediaCall(built, true, false, codec.getName())) {
			call.negotiate();

			RTCRtpReceiver receiver = call.getReceiver("video");
			VideoTrack track = (VideoTrack) receiver.getTrack();
			VideoTrackSink sink = frame -> {
				String size = frame.buffer.getWidth() + "x" + frame.buffer.getHeight();
				int luma = frame.buffer.toI420().getDataY().get(0) & 0xFF;

				if (!encodedSizes.contains(size) || !encodedLuma.contains(luma)) {
					mismatch.compareAndSet(null, size + ", luma " + luma + " not in " + encodedSizes
							+ " " + encodedLuma + ", encoders " + encoderFactory.created.get()
							+ ", decoders " + decoderFactory.created.get());
				}

				frame.release();
				received.countDown();
			};
			track.addSink(sink);

			call.awaitConnected();
			call.startMedia();

			assertTrue(received.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
					"too few frames received: encoders " + encoderFactory.created.get()
							+ ", decoders " + decoderFactory.created.get());

			track.removeSink(sink);
			receiver.dispose();
		}
		finally {
			built.dispose();
		}

		assertNull(mismatch.get(), "a received frame was never sent: " + mismatch.get());

		TestEncoder encoder = encoderFactory.last.get();

		assertEquals(320, encoder.settings.width);
		assertEquals(240, encoder.settings.height);
		assertTrue(encoder.rates.get() > 0, "rates were never set");
		assertTrue(encoder.released.get(), "encoder was never released");
		assertTrue(decoderFactory.last.get().released.get(), "decoder was never released");
	}

	@Test
	void javaEncoderDroppingFramesKeepsSending() throws Exception {
		// A Java encoder drops a frame by producing nothing for it. WebRTC is
		// told about the frames dropped once a later one comes out, and the
		// frames encoded still arrive.
		TestEncoderFactory encoderFactory = new TestEncoderFactory();
		encoderFactory.dropEveryOtherFrame = true;

		PeerConnectionFactory built = testCodecFactory(encoderFactory, new TestDecoderFactory());

		try {
			assertFramesReceived(built, TEST_CODEC);
		}
		finally {
			built.dispose();
		}

		assertTrue(encoderFactory.dropped.get() > 0, "the encoder dropped no frames");
	}

	@Test
	void factoryDelegatesToBuiltInCodec() throws Exception {
		AtomicInteger encoders = new AtomicInteger();
		AtomicInteger decoders = new AtomicInteger();
		DefaultVideoEncoderFactory builtInEncoders = new DefaultVideoEncoderFactory();
		DefaultVideoDecoderFactory builtInDecoders = new DefaultVideoDecoderFactory();

		PeerConnectionFactory built = PeerConnectionFactory.builder()
				.setAudioDeviceModule(audioDevModule)
				.setVideoEncoderFactory(new VideoEncoderFactory() {

					@Override
					public List<VideoCodecInfo> getSupportedCodecs() {
						return builtInEncoders.getSupportedCodecs();
					}

					@Override
					public VideoEncoder createEncoder(VideoCodecInfo info) {
						encoders.incrementAndGet();
						return builtInEncoders.createEncoder(info);
					}
				})
				.setVideoDecoderFactory(new VideoDecoderFactory() {

					@Override
					public List<VideoCodecInfo> getSupportedCodecs() {
						return builtInDecoders.getSupportedCodecs();
					}

					@Override
					public VideoDecoder createDecoder(VideoCodecInfo info) {
						decoders.incrementAndGet();
						return builtInDecoders.createDecoder(info);
					}
				})
				.build();

		try {
			assertFramesReceived(built, "VP8");
		}
		finally {
			built.dispose();
		}

		assertTrue(encoders.get() > 0);
		assertTrue(decoders.get() > 0);
	}

	@Test
	void failingEncoderFactoryDoesNotCrash() throws Exception {
		TestEncoderFactory encoderFactory = new TestEncoderFactory() {

			@Override
			public VideoEncoder createEncoder(VideoCodecInfo info) {
				super.createEncoder(info);
				throw new IllegalStateException("createEncoder fails on purpose");
			}
		};
		// A Java log sink receives the warning about the failure, which is
		// logged while WebRTC holds its logging lock. This once deadlocked
		// the call as it closed: the warning went out while the Java
		// exception was still pending.
		Logging.addLogSink(Logging.Severity.WARNING, (severity, message) -> { });

		PeerConnectionFactory built = testCodecFactory(encoderFactory, new TestDecoderFactory());

		try (TestMediaCall call = new TestMediaCall(built, true, false, TEST_CODEC)) {
			call.negotiate();
			call.awaitConnected();
			call.startMedia();

			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

			while (encoderFactory.created.get() == 0 && System.nanoTime() < deadline) {
				Thread.sleep(20);
			}

			assertTrue(encoderFactory.created.get() > 0, "the encoder factory was never asked");
		}
		finally {
			built.dispose();
		}
	}

	@Test
	void failingSupportedCodecsFailsBuild() {
		VideoEncoderFactory broken = new TestEncoderFactory() {

			@Override
			public List<VideoCodecInfo> getSupportedCodecs() {
				throw new IllegalStateException("getSupportedCodecs fails on purpose");
			}
		};

		assertThrows(Throwable.class, () -> PeerConnectionFactory.builder()
				.setAudioDeviceModule(audioDevModule)
				.setVideoEncoderFactory(broken)
				.build());
	}

	@Test
	void encodedImageBuilderValidates() {
		assertThrows(NullPointerException.class, () -> EncodedImage.builder().build());
		assertThrows(IllegalArgumentException.class, () -> EncodedImage.builder()
				.setBuffer(ByteBuffer.allocate(1))
				.setRotation(45)
				.build());

		EncodedImage image = EncodedImage.builder()
				.setBuffer(ByteBuffer.allocate(3))
				.setEncodedWidth(2)
				.setEncodedHeight(4)
				.setFrameType(EncodedImage.FrameType.KEY)
				.setQp(7)
				.build();

		assertEquals(3, image.getBuffer().remaining());
		assertEquals(EncodedImage.FrameType.KEY, image.getFrameType());
		assertEquals(7, (int) image.getQp());
	}

	private void assertFramesReceived(PeerConnectionFactory built, String codec) throws Exception {
		CountDownLatch received = new CountDownLatch(10);

		try (TestMediaCall call = new TestMediaCall(built, true, false, codec)) {
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

			track.removeSink(sink);
			receiver.dispose();
		}
	}

	private PeerConnectionFactory testCodecFactory(VideoEncoderFactory encoderFactory,
			VideoDecoderFactory decoderFactory) {
		return PeerConnectionFactory.builder()
				.setAudioDeviceModule(audioDevModule)
				.setVideoEncoderFactory(encoderFactory)
				.setVideoDecoderFactory(decoderFactory)
				.build();
	}

	/**
	 * Checks the hardware codecs of a hardware factory against its supported
	 * codecs and those of the matching default factory. Which codecs the
	 * hardware takes depends on the machine; that the codecs without a
	 * software implementation are among them does not.
	 */
	private static void assertHardwareCodecs(List<VideoCodecInfo> software,
			List<VideoCodecInfo> supported, List<VideoCodecInfo> hardware) {
		// Supported codecs, in the same order.
		int next = 0;

		for (VideoCodecInfo codec : hardware) {
			int index = supported.subList(next, supported.size()).indexOf(codec);

			assertTrue(index >= 0, codec + " is not supported or out of order: " + supported);

			next += index + 1;
		}

		for (VideoCodecInfo codec : supported) {
			if (!software.contains(codec)) {
				assertTrue(hardware.contains(codec), codec + " has no software codec: " + hardware);
			}
		}

		assertThrows(UnsupportedOperationException.class, () -> hardware.add(new VideoCodecInfo(TEST_CODEC)));
	}

	private static List<String> names(List<?> codecs) {
		return codecs.stream()
				.map(codec -> codec instanceof VideoCodecInfo
						? ((VideoCodecInfo) codec).getName()
						: ((RTCRtpCodecCapability) codec).getName())
				.collect(Collectors.toList());
	}

	/** The SDP parameters of H.265 Main profile, main tier, level 3.1. */
	private static Map<String, String> h265Parameters() {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("profile-id", "1");
		parameters.put("tier-flag", "0");
		parameters.put("level-id", "93");
		parameters.put("tx-mode", "SRST");
		return parameters;
	}

	private static boolean isH265(VideoCodecInfo codec) {
		return H265.getName().equalsIgnoreCase(codec.getName());
	}

	/**
	 * Frames a payload as an H.265 access unit, in Annex B: the parameter sets
	 * WebRTC wants with every key frame (VPS, SPS, PPS), then an IDR slice that
	 * starts a picture and carries the payload. The payload goes in as hex
	 * text after a marker, so that no byte of it is zero and nothing in it
	 * reads as a start code. WebRTC looks at the NAL unit types only; the
	 * parameter sets need not make sense.
	 */
	static ByteBuffer toH265AccessUnit(ByteBuffer payload) {
		StringBuilder text = new StringBuilder(H265_MARKER);

		while (payload.hasRemaining()) {
			text.append(String.format("%02X", payload.get() & 0xFF));
		}

		byte[] slice = text.toString().getBytes(StandardCharsets.US_ASCII);
		byte[][] nalUnits = {
			// The NAL unit header: the type in bits 1 to 6 of the first byte,
			// then layer 0 and temporal ID 1.
			{ 0x40, 0x01, 0x0C, 0x01, (byte) 0xFF },	// VPS (32)
			{ 0x42, 0x01, 0x01, 0x01, (byte) 0xFF },	// SPS (33)
			{ 0x44, 0x01, (byte) 0xC1, 0x72 },			// PPS (34)
			// IDR_W_RADL (19); the first bit of the slice header says that it
			// is the first slice of its picture.
			concat(new byte[] { 0x26, 0x01, (byte) 0x80 }, slice)
		};

		int size = 0;

		for (byte[] nalUnit : nalUnits) {
			size += 4 + nalUnit.length;
		}

		ByteBuffer accessUnit = ByteBuffer.allocateDirect(size);

		for (byte[] nalUnit : nalUnits) {
			accessUnit.put(new byte[] { 0, 0, 0, 1 }).put(nalUnit);
		}

		accessUnit.flip();

		return accessUnit;
	}

	/**
	 * The payload an access unit of {@link #toH265AccessUnit} carries, or
	 * null if there is none.
	 */
	static ByteBuffer fromH265AccessUnit(ByteBuffer accessUnit) {
		byte[] bytes = new byte[accessUnit.remaining()];
		accessUnit.duplicate().get(bytes);

		String text = new String(bytes, StandardCharsets.ISO_8859_1);
		int start = text.indexOf(H265_MARKER);

		if (start < 0) {
			return null;
		}

		String hex = text.substring(start + H265_MARKER.length());
		ByteBuffer payload = ByteBuffer.allocate(hex.length() / 2);

		for (int i = 0; i + 1 < hex.length(); i += 2) {
			payload.put((byte) Integer.parseInt(hex.substring(i, i + 2), 16));
		}

		payload.flip();

		return payload;
	}

	private static byte[] concat(byte[] first, byte[] second) {
		byte[] result = Arrays.copyOf(first, first.length + second.length);
		System.arraycopy(second, 0, result, first.length, second.length);
		return result;
	}



	interface EncodeListener {

		void onEncode(VideoFrame frame, int luma);

	}



	/**
	 * Offers the test codec next to the built-in ones.
	 */
	static class TestEncoderFactory implements VideoEncoderFactory {

		final DefaultVideoEncoderFactory builtIn = new DefaultVideoEncoderFactory();
		final AtomicInteger created = new AtomicInteger();
		final AtomicReference<TestEncoder> last = new AtomicReference<>();
		final AtomicInteger dropped = new AtomicInteger();
		final VideoCodecInfo codec;

		volatile EncodeListener onEncode;

		/** Makes the encoders produce nothing for every other frame. */
		volatile boolean dropEveryOtherFrame;


		TestEncoderFactory() {
			this(new VideoCodecInfo(TEST_CODEC));
		}

		TestEncoderFactory(VideoCodecInfo codec) {
			this.codec = codec;
		}

		@Override
		public List<VideoCodecInfo> getSupportedCodecs() {
			List<VideoCodecInfo> codecs = new ArrayList<>();
			codecs.add(codec);
			codecs.addAll(builtIn.getSupportedCodecs());
			return codecs;
		}

		@Override
		public VideoEncoder createEncoder(VideoCodecInfo info) {
			created.incrementAndGet();

			if (!codec.getName().equalsIgnoreCase(info.getName())) {
				return builtIn.createEncoder(info);
			}

			TestEncoder encoder = new TestEncoder(this, isH265(codec));
			last.set(encoder);
			return encoder;
		}

	}



	/**
	 * Offers the test codec next to the built-in ones.
	 */
	static class TestDecoderFactory implements VideoDecoderFactory {

		final DefaultVideoDecoderFactory builtIn = new DefaultVideoDecoderFactory();
		final AtomicInteger created = new AtomicInteger();
		final AtomicReference<TestDecoder> last = new AtomicReference<>();
		final VideoCodecInfo codec;

		volatile boolean javaBuffers;


		TestDecoderFactory() {
			this(new VideoCodecInfo(TEST_CODEC, Collections.emptyMap()));
		}

		TestDecoderFactory(VideoCodecInfo codec) {
			this.codec = codec;
		}

		@Override
		public List<VideoCodecInfo> getSupportedCodecs() {
			List<VideoCodecInfo> codecs = new ArrayList<>();
			codecs.add(codec);
			codecs.addAll(builtIn.getSupportedCodecs());
			return codecs;
		}

		@Override
		public VideoDecoder createDecoder(VideoCodecInfo info) {
			created.incrementAndGet();

			if (!codec.getName().equalsIgnoreCase(info.getName())) {
				return builtIn.createDecoder(info);
			}

			TestDecoder decoder = new TestDecoder(javaBuffers, isH265(codec));
			last.set(decoder);
			return decoder;
		}

	}



	/**
	 * "Encodes" a frame into its size and its first luma sample, every frame
	 * a key frame, so that the receiver can tell which frame it got.
	 */
	static class TestEncoder implements VideoEncoder {

		private final TestEncoderFactory factory;

		private final boolean h265;

		final AtomicInteger rates = new AtomicInteger();
		final AtomicReference<Boolean> released = new AtomicReference<>(false);

		volatile Settings settings;

		private Callback callback;

		private int frames;


		TestEncoder(TestEncoderFactory factory, boolean h265) {
			this.factory = factory;
			this.h265 = h265;
		}

		@Override
		public VideoCodecStatus initEncode(Settings settings, Callback callback) {
			this.settings = settings;
			this.callback = callback;
			return VideoCodecStatus.OK;
		}

		@Override
		public VideoCodecStatus release() {
			released.set(true);
			return VideoCodecStatus.OK;
		}

		@Override
		public VideoCodecStatus encode(VideoFrame frame, EncodeInfo info) {
			if (factory.dropEveryOtherFrame && frames++ % 2 == 1) {
				// Dropped: no output, as a rate controlled encoder would do.
				factory.dropped.incrementAndGet();
				return VideoCodecStatus.OK;
			}

			int width = frame.buffer.getWidth();
			int height = frame.buffer.getHeight();
			int luma = frame.buffer.toI420().getDataY().get(0) & 0xFF;

			EncodeListener listener = factory.onEncode;

			if (listener != null) {
				listener.onEncode(frame, luma);
			}

			ByteBuffer payload = ByteBuffer.allocateDirect(9);
			payload.putInt(width).putInt(height).put((byte) luma);
			payload.flip();

			if (h265) {
				payload = toH265AccessUnit(payload);
			}

			callback.onEncodedFrame(EncodedImage.builder()
					.setBuffer(payload)
					.setEncodedWidth(width)
					.setEncodedHeight(height)
					.setCaptureTimeNs(frame.timestampNs)
					.setFrameType(EncodedImage.FrameType.KEY)
					.build());

			return VideoCodecStatus.OK;
		}

		@Override
		public VideoCodecStatus setRates(RateControlParameters parameters) {
			if (parameters.bitrate.getSum() > 0) {
				rates.incrementAndGet();
			}
			return VideoCodecStatus.OK;
		}

		@Override
		public ScalingSettings getScalingSettings() {
			// The payload has no QP to scale by.
			return ScalingSettings.OFF;
		}

	}



	/**
	 * Decodes what {@link TestEncoder} produces into a frame of the encoded
	 * size, filled with the encoded luma sample.
	 */
	static class TestDecoder implements VideoDecoder {

		final AtomicReference<Boolean> released = new AtomicReference<>(false);

		private final boolean javaBuffers;

		private final boolean h265;

		private Callback callback;


		TestDecoder(boolean javaBuffers, boolean h265) {
			this.javaBuffers = javaBuffers;
			this.h265 = h265;
		}

		@Override
		public VideoCodecStatus initDecode(Settings settings, Callback callback) {
			this.callback = callback;
			return VideoCodecStatus.OK;
		}

		@Override
		public VideoCodecStatus release() {
			released.set(true);
			return VideoCodecStatus.OK;
		}

		@Override
		public VideoCodecStatus decode(EncodedImage image) {
			ByteBuffer payload = h265 ? fromH265AccessUnit(image.getBuffer()) : image.getBuffer();

			if (payload == null || payload.remaining() != 9) {
				return VideoCodecStatus.ERR_PARAMETER;
			}

			int width = payload.getInt();
			int height = payload.getInt();
			byte luma = payload.get();

			I420Buffer buffer = javaBuffers
					? new JavaI420Buffer(width, height)
					: NativeI420Buffer.allocate(width, height);
			ByteBuffer y = buffer.getDataY();
			ByteBuffer u = buffer.getDataU();
			ByteBuffer v = buffer.getDataV();

			for (int i = 0; i < y.capacity(); i++) {
				y.put(i, luma);
			}
			for (int i = 0; i < u.capacity(); i++) {
				u.put(i, (byte) 128);
				v.put(i, (byte) 128);
			}

			VideoFrame frame = new VideoFrame(buffer, image.getCaptureTimeNs());
			callback.onDecodedFrame(frame, null, null);
			frame.release();

			return VideoCodecStatus.OK;
		}

	}



	/**
	 * A frame buffer that lives in Java alone.
	 */
	static class JavaI420Buffer implements I420Buffer {

		private final int width;
		private final int height;
		private final ByteBuffer dataY;
		private final ByteBuffer dataU;
		private final ByteBuffer dataV;


		JavaI420Buffer(int width, int height) {
			int chromaWidth = (width + 1) / 2;
			int chromaHeight = (height + 1) / 2;

			this.width = width;
			this.height = height;
			this.dataY = ByteBuffer.allocateDirect(width * height);
			this.dataU = ByteBuffer.allocateDirect(chromaWidth * chromaHeight);
			this.dataV = ByteBuffer.allocateDirect(chromaWidth * chromaHeight);
		}

		@Override
		public ByteBuffer getDataY() {
			return dataY.slice();
		}

		@Override
		public ByteBuffer getDataU() {
			return dataU.slice();
		}

		@Override
		public ByteBuffer getDataV() {
			return dataV.slice();
		}

		@Override
		public int getStrideY() {
			return width;
		}

		@Override
		public int getStrideU() {
			return (width + 1) / 2;
		}

		@Override
		public int getStrideV() {
			return (width + 1) / 2;
		}

		@Override
		public int getWidth() {
			return width;
		}

		@Override
		public int getHeight() {
			return height;
		}

		@Override
		public I420Buffer toI420() {
			return this;
		}

		@Override
		public VideoFrameBuffer cropAndScale(int cropX, int cropY, int cropWidth,
				int cropHeight, int scaleWidth, int scaleHeight) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void retain() {
		}

		@Override
		public void release() {
		}

	}

}
