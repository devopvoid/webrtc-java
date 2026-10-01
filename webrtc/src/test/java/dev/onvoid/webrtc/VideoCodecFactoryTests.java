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
import dev.onvoid.webrtc.media.video.codec.NativeVideoEncoder;
import dev.onvoid.webrtc.media.video.codec.VideoCodecInfo;
import dev.onvoid.webrtc.media.video.codec.VideoCodecStatus;
import dev.onvoid.webrtc.media.video.codec.VideoDecoder;
import dev.onvoid.webrtc.media.video.codec.VideoDecoderFactory;
import dev.onvoid.webrtc.media.video.codec.VideoEncoder;
import dev.onvoid.webrtc.media.video.codec.VideoEncoderFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
		assertRoundTrip(false);
	}

	@Test
	void javaFrameBufferRoundTrips() throws Exception {
		// The decoder hands back frames whose buffer is no NativeI420Buffer,
		// which WebRTC has to copy.
		assertRoundTrip(true);
	}

	/**
	 * Sends a call through the test codec and checks that every frame
	 * received is one that was sent, and how WebRTC used the codecs.
	 */
	private void assertRoundTrip(boolean javaBuffers) throws Exception {
		TestEncoderFactory encoderFactory = new TestEncoderFactory();
		TestDecoderFactory decoderFactory = new TestDecoderFactory();
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

		try (TestMediaCall call = new TestMediaCall(built, true, false, TEST_CODEC)) {
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

	private static List<String> names(List<?> codecs) {
		return codecs.stream()
				.map(codec -> codec instanceof VideoCodecInfo
						? ((VideoCodecInfo) codec).getName()
						: ((RTCRtpCodecCapability) codec).getName())
				.collect(Collectors.toList());
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

		volatile EncodeListener onEncode;


		@Override
		public List<VideoCodecInfo> getSupportedCodecs() {
			List<VideoCodecInfo> codecs = new ArrayList<>();
			codecs.add(new VideoCodecInfo(TEST_CODEC));
			codecs.addAll(builtIn.getSupportedCodecs());
			return codecs;
		}

		@Override
		public VideoEncoder createEncoder(VideoCodecInfo info) {
			created.incrementAndGet();

			if (!TEST_CODEC.equalsIgnoreCase(info.getName())) {
				return builtIn.createEncoder(info);
			}

			TestEncoder encoder = new TestEncoder(this);
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

		volatile boolean javaBuffers;


		@Override
		public List<VideoCodecInfo> getSupportedCodecs() {
			List<VideoCodecInfo> codecs = new ArrayList<>();
			codecs.add(new VideoCodecInfo(TEST_CODEC, Collections.emptyMap()));
			codecs.addAll(builtIn.getSupportedCodecs());
			return codecs;
		}

		@Override
		public VideoDecoder createDecoder(VideoCodecInfo info) {
			created.incrementAndGet();

			if (!TEST_CODEC.equalsIgnoreCase(info.getName())) {
				return builtIn.createDecoder(info);
			}

			TestDecoder decoder = new TestDecoder(javaBuffers);
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

		final AtomicInteger rates = new AtomicInteger();
		final AtomicReference<Boolean> released = new AtomicReference<>(false);

		volatile Settings settings;

		private Callback callback;


		TestEncoder(TestEncoderFactory factory) {
			this.factory = factory;
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

		private Callback callback;


		TestDecoder(boolean javaBuffers) {
			this.javaBuffers = javaBuffers;
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
			ByteBuffer payload = image.getBuffer();

			if (payload.remaining() != 9) {
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
