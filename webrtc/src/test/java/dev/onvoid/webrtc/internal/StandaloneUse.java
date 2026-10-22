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

import java.nio.ByteBuffer;

import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.audio.AudioConverter;
import dev.onvoid.webrtc.media.audio.AudioResampler;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.I420Buffer;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import dev.onvoid.webrtc.media.video.VideoCapture;
import dev.onvoid.webrtc.media.video.VideoFrameBuffer;

/**
 * Uses one class that applications may use on their own, as the first and
 * only thing a fresh JVM does, so that nothing else has loaded the native
 * library before. Exits with 0 once the class worked.
 */
public final class StandaloneUse {

	static final String[] CLASSES = {
		"AudioConverter",
		"AudioResampler",
		"CustomAudioSource",
		"CustomVideoSource",
		"NativeI420Buffer",
		"VideoBufferConverter",
		"VideoCapture"
	};


	public static void main(String[] args) throws Exception {
		switch (args[0]) {
			case "AudioConverter":
				new AudioConverter(48000, 1, 16000, 1).dispose();
				break;
			case "AudioResampler":
				new AudioResampler(48000, 16000, 1).dispose();
				break;
			case "CustomAudioSource":
				new CustomAudioSource().dispose();
				break;
			case "CustomVideoSource":
				new CustomVideoSource().dispose();
				break;
			case "NativeI420Buffer":
				NativeI420Buffer.allocate(2, 2).release();
				break;
			case "VideoBufferConverter":
				// Over a buffer of Java's own, so that no other native class is used first.
				VideoBufferConverter.convertFromI420(new JavaI420Buffer(2, 2), new byte[16], FourCC.RGBA);
				break;
			case "VideoCapture":
				new VideoCapture().dispose();
				break;
			default:
				throw new IllegalArgumentException("Unknown class " + args[0]);
		}
	}



	private static final class JavaI420Buffer implements I420Buffer {

		private final int width;

		private final int height;

		private final ByteBuffer dataY;

		private final ByteBuffer dataU;

		private final ByteBuffer dataV;


		JavaI420Buffer(int width, int height) {
			this.width = width;
			this.height = height;
			this.dataY = ByteBuffer.allocateDirect(width * height);
			this.dataU = ByteBuffer.allocateDirect(getStrideU() * ((height + 1) / 2));
			this.dataV = ByteBuffer.allocateDirect(getStrideV() * ((height + 1) / 2));
		}

		@Override
		public ByteBuffer getDataY() {
			return dataY;
		}

		@Override
		public ByteBuffer getDataU() {
			return dataU;
		}

		@Override
		public ByteBuffer getDataV() {
			return dataV;
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
		public VideoFrameBuffer cropAndScale(int cropX, int cropY, int cropWidth, int cropHeight,
				int scaleWidth, int scaleHeight) {
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
