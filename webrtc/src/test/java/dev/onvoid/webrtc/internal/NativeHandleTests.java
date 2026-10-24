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

package dev.onvoid.webrtc.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.TestBase;
import dev.onvoid.webrtc.media.SyncClock;
import dev.onvoid.webrtc.media.audio.AudioConverter;
import dev.onvoid.webrtc.media.audio.AudioDeviceModuleBase;
import dev.onvoid.webrtc.media.audio.AudioResampler;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.I420Buffer;
import dev.onvoid.webrtc.media.video.VideoDeviceSource;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoFrameBuffer;
import dev.onvoid.webrtc.media.video.VideoProcessor;
import dev.onvoid.webrtc.media.video.VideoTrackSink;
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer;

import org.junit.jupiter.api.Test;

/**
 * Native code binds its objects to the fields that the library's classes
 * declare. The subclasses here declare fields of the same names, initialized
 * after the native object was bound. Were a handle looked up on the dynamic
 * class, native code would write to these fields and then read this value
 * back as a pointer.
 */
class NativeHandleTests extends TestBase {

	private static final long SHADOW = 7;


	@Test
	void resamplerSubclass() {
		ShadowedResampler resampler = new ShadowedResampler();

		assertNotEquals(0, resampler.getNativeHandle());
		assertEquals(160, resampler.resample(new byte[960], 480, new byte[320], 160, 1));

		resampler.dispose();

		assertEquals(0, resampler.getNativeHandle());
		assertEquals(SHADOW, resampler.nativeHandle);
	}

	@Test
	void converterSubclass() {
		ShadowedConverter converter = new ShadowedConverter();

		assertNotEquals(0, converter.getNativeHandle());
		converter.convert(new byte[960], new byte[converter.getTargetBufferSize()]);

		converter.dispose();

		assertEquals(0, converter.getNativeHandle());
		assertEquals(SHADOW, converter.nativeHandle);
	}

	@Test
	void clockSubclass() {
		ShadowedClock clock = new ShadowedClock();

		try {
			assertTrue(clock.getTimestampUs() > 0);

			// The clock is read as a parameter of another native call.
			CustomAudioSource source = new CustomAudioSource(clock);
			source.dispose();
		}
		finally {
			clock.dispose();
		}

		assertEquals(0, clock.getNativeHandle());
		assertEquals(SHADOW, clock.nativeHandle);
	}

	@Test
	void factorySubclass() {
		ShadowedFactory factory = new ShadowedFactory(audioDevModule);

		assertNotEquals(0, factory.getNativeHandle());

		// Reads the thread and audio module handles.
		factory.dispose();

		assertEquals(0, factory.getNativeHandle());
		assertEquals(SHADOW, factory.nativeHandle);
		assertEquals(SHADOW, factory.networkThreadHandle);
		assertEquals(SHADOW, factory.signalingThreadHandle);
		assertEquals(SHADOW, factory.audioModuleHandle);
	}

	@Test
	void videoSourceSubclass() {
		ShadowedVideoSource source = new ShadowedVideoSource();
		TestProcessor processor = new TestProcessor();

		try {
			source.setVideoProcessor(processor);

			assertNotNull(processor.sink, "setSink was not called");

			source.setVideoProcessor(null);
		}
		finally {
			source.dispose();
		}

		assertEquals(SHADOW, source.nativeHandle);
		assertEquals(SHADOW, source.processorHostHandle);
	}

	@Test
	void capturerSubclass() {
		ShadowedCapturer capturer = new ShadowedCapturer();

		assertNotEquals(0, capturer.getNativeHandle());

		// Reads the callback handle, which no capture has set.
		capturer.dispose();

		assertEquals(0, capturer.getNativeHandle());
		assertEquals(SHADOW, capturer.nativeHandle);
		assertEquals(SHADOW, capturer.callbackHandle);
	}

	@Test
	void foreignBufferRejected() {
		// A buffer implemented in Java holds no native buffer, whatever its fields are called.
		CustomVideoSource source = new CustomVideoSource();
		VideoFrame frame = new VideoFrame(new ForeignBuffer(), 0);

		try {
			assertThrows(IllegalArgumentException.class, () -> source.pushFrame(frame));
			assertThrows(IllegalArgumentException.class, () -> source.pushFrame(frame, 1000));
		}
		finally {
			source.dispose();
		}
	}



	private static class ShadowedResampler extends AudioResampler {

		private long nativeHandle = SHADOW;


		ShadowedResampler() {
			super(48000, 16000, 1);
		}
	}



	private static class ShadowedConverter extends AudioConverter {

		private long nativeHandle = SHADOW;


		ShadowedConverter() {
			super(48000, 1, 16000, 1);
		}
	}



	private static class ShadowedClock extends SyncClock {

		private long nativeHandle = SHADOW;

	}



	private static class ShadowedFactory extends PeerConnectionFactory {

		private long nativeHandle = SHADOW;

		private long networkThreadHandle = SHADOW;

		private long signalingThreadHandle = SHADOW;

		private long audioModuleHandle = SHADOW;


		ShadowedFactory(AudioDeviceModuleBase audioModule) {
			super(audioModule);
		}
	}



	private static class ShadowedVideoSource extends VideoDeviceSource {

		private long nativeHandle = SHADOW;

		private long processorHostHandle = SHADOW;

	}



	private static class ShadowedCapturer extends ScreenCapturer {

		private long nativeHandle = SHADOW;

		private long callbackHandle = SHADOW;

	}



	private static class TestProcessor implements VideoProcessor {

		VideoTrackSink sink;


		@Override
		public void setSink(VideoTrackSink sink) {
			this.sink = sink;
		}

		@Override
		public void onFrameCaptured(VideoFrame frame) {
			sink.onVideoFrame(frame);
		}
	}



	private static class ForeignBuffer implements I420Buffer {

		@SuppressWarnings("unused")
		private long nativeHandle = SHADOW;

		private final ByteBuffer data = ByteBuffer.allocateDirect(16 * 16);


		@Override
		public ByteBuffer getDataY() {
			return data;
		}

		@Override
		public ByteBuffer getDataU() {
			return data;
		}

		@Override
		public ByteBuffer getDataV() {
			return data;
		}

		@Override
		public int getStrideY() {
			return 16;
		}

		@Override
		public int getStrideU() {
			return 8;
		}

		@Override
		public int getStrideV() {
			return 8;
		}

		@Override
		public int getWidth() {
			return 16;
		}

		@Override
		public int getHeight() {
			return 16;
		}

		@Override
		public I420Buffer toI420() {
			return this;
		}

		@Override
		public VideoFrameBuffer cropAndScale(int cropX, int cropY, int cropWidth,
				int cropHeight, int scaleWidth, int scaleHeight) {
			return this;
		}

		@Override
		public void retain() {
		}

		@Override
		public void release() {
		}
	}
}
