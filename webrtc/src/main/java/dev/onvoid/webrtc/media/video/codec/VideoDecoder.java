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

package dev.onvoid.webrtc.media.video.codec;

import dev.onvoid.webrtc.media.video.VideoFrame;

/**
 * A video decoder implemented in Java, which a {@link VideoDecoderFactory}
 * hands to WebRTC.
 * <p>
 * WebRTC calls {@link #initDecode initDecode}, then {@link #decode decode}
 * for each encoded frame, and finally {@link #release release}. It may
 * initialize a released decoder again. These calls come from one WebRTC thread
 * at a time, never concurrently. The decoder hands each decoded frame to the
 * {@link Callback} it was initialized with, either from within {@code decode}
 * or later from a thread of its own.
 * <p>
 * Whatever a method throws is logged and treated as {@link
 * VideoCodecStatus#ERROR}.
 *
 * @author Alex Andres
 *
 * @see VideoDecoderFactory
 */
public interface VideoDecoder {

	/**
	 * The settings a decoder is initialized with.
	 */
	class Settings {

		/** The number of CPU cores the decoder may use. */
		public final int numberOfCores;

		/** The largest frame width to expect, 0 if not known. */
		public final int width;

		/** The largest frame height to expect, 0 if not known. */
		public final int height;


		/**
		 * Creates decoder settings.
		 *
		 * @param numberOfCores The number of CPU cores to use.
		 * @param width         The largest frame width to expect.
		 * @param height        The largest frame height to expect.
		 */
		public Settings(int numberOfCores, int width, int height) {
			this.numberOfCores = numberOfCores;
			this.width = width;
			this.height = height;
		}

		@Override
		public String toString() {
			return String.format("%s [cores=%d, width=%d, height=%d]",
					Settings.class.getSimpleName(), numberOfCores, width, height);
		}

	}



	/**
	 * Receives what a decoder produces.
	 */
	interface Callback {

		/**
		 * Hands a decoded frame to WebRTC. The frame's timestamp must be the
		 * {@link EncodedImage#getCaptureTimeNs() capture time} of the image it
		 * was decoded from. WebRTC takes a reference of its own, so the caller
		 * still releases the frame it created. May be called from any thread,
		 * but frames have to be handed over in decoding order. Frames handed
		 * over after the decoder was released are ignored.
		 *
		 * @param frame        The decoded frame.
		 * @param decodeTimeMs How long decoding took in milliseconds, or
		 *                     {@code null} if not known.
		 * @param qp           The quantizer of the frame, or {@code null} if
		 *                     not known.
		 */
		void onDecodedFrame(VideoFrame frame, Integer decodeTimeMs, Integer qp);

	}


	/**
	 * Initializes the decoder, or initializes it again after a {@link
	 * #release()}.
	 *
	 * @param settings The decoder settings.
	 * @param callback Receives the decoded frames until the decoder is
	 *                 released.
	 *
	 * @return {@link VideoCodecStatus#OK} on success.
	 */
	VideoCodecStatus initDecode(Settings settings, Callback callback);

	/**
	 * Releases the decoder. It may be initialized again afterwards.
	 *
	 * @return {@link VideoCodecStatus#OK} on success.
	 */
	VideoCodecStatus release();

	/**
	 * Decodes an encoded frame. The image and its buffer are valid only until
	 * this method returns.
	 *
	 * @param image The encoded frame.
	 *
	 * @return {@link VideoCodecStatus#OK} if the frame was taken, also if the
	 *         decoded frame is delivered later.
	 */
	VideoCodecStatus decode(EncodedImage image);

	/**
	 * Returns the name of the implementation, which shows up in statistics.
	 *
	 * @return The simple class name unless overridden.
	 */
	default String getImplementationName() {
		return getClass().getSimpleName();
	}

	/**
	 * Returns whether the decoder runs on dedicated hardware.
	 *
	 * @return {@code false} unless overridden.
	 */
	default boolean isHardwareDecoder() {
		return false;
	}

}
