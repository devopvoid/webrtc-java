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

import java.nio.ByteBuffer;
import java.util.Objects;

import dev.onvoid.webrtc.internal.DisposableNativeObject;

/**
 * Sends video frames that are already encoded on an {@link RTCRtpSender},
 * in place of frames from a track: WebRTC packetizes and sends them as they
 * are, without encoding anything itself.
 * <p>
 * The frames must be in the codec the sender negotiated, which its
 * {@link RTCRtpSender#getParameters() parameters} name, and the sender sends
 * them with that codec's payload type. The injector takes the place of the
 * sender's track: it can only be created for a sender without one, and
 * {@link RTCRtpSender#replaceTrack(dev.onvoid.webrtc.media.MediaStreamTrack)
 * giving the sender a track} ends the injection.
 * <p>
 * Frames can be injected from any thread.
 *
 * @author Alex Andres
 *
 * @see RTCRtpSender#createEncodedVideoFrameInjector(RTCEncodedVideoFrameInjectorObserver)
 */
public class RTCEncodedVideoFrameInjector extends DisposableNativeObject {

	/*
	 * The native observer set through this object, owned by it and freed when
	 * disposed.
	 */
	@SuppressWarnings("unused")
	private long observerHandle;


	/**
	 * Constructor to be used by the native api.
	 */
	private RTCEncodedVideoFrameInjector() {

	}

	/**
	 * Sends an encoded frame. The bytes between the position and the limit of
	 * the buffer are copied before this method returns, so the buffer may be
	 * reused right away; its position and limit stay as they are.
	 *
	 * @param data         The encoded frame.
	 * @param rtpTimestamp The RTP timestamp of the frame, on the 90 kHz video
	 *                     clock, without the sender's random offset.
	 * @param keyFrame     Whether the frame is a key frame.
	 * @param width        The width of the frame in pixels.
	 * @param height       The height of the frame in pixels.
	 */
	public void injectFrame(ByteBuffer data, long rtpTimestamp, boolean keyFrame, int width, int height) {
		inject(data, rtpTimestamp, keyFrame, width, height, 0, false);
	}

	/**
	 * Sends an encoded frame, along with the time it was captured. The bytes
	 * between the position and the limit of the buffer are copied before this
	 * method returns, so the buffer may be reused right away; its position and
	 * limit stay as they are.
	 *
	 * @param data          The encoded frame.
	 * @param rtpTimestamp  The RTP timestamp of the frame, on the 90 kHz video
	 *                      clock, without the sender's random offset.
	 * @param keyFrame      Whether the frame is a key frame.
	 * @param width         The width of the frame in pixels.
	 * @param height        The height of the frame in pixels.
	 * @param captureTimeMs The time the frame was captured, in milliseconds.
	 */
	public void injectFrame(ByteBuffer data, long rtpTimestamp, boolean keyFrame, int width, int height,
			long captureTimeMs) {
		inject(data, rtpTimestamp, keyFrame, width, height, captureTimeMs, true);
	}

	/**
	 * Releases the native injector. The sender keeps sending nothing until it
	 * gets a track again, and the observer is not called any more once this
	 * returns.
	 */
	@Override
	public native void dispose();

	private void inject(ByteBuffer data, long rtpTimestamp, boolean keyFrame, int width, int height,
			long captureTimeMs, boolean hasCaptureTime) {
		Objects.requireNonNull(data, "data");

		if (rtpTimestamp < 0 || rtpTimestamp > 0xFFFFFFFFL) {
			throw new IllegalArgumentException("RTP timestamp out of the 32-bit range: " + rtpTimestamp);
		}
		if (width < 1 || width > 0xFFFF || height < 1 || height > 0xFFFF) {
			throw new IllegalArgumentException("Frame size out of range: " + width + "x" + height);
		}

		if (data.isDirect()) {
			injectDirectBuffer(data, data.position(), data.remaining(), rtpTimestamp, keyFrame,
					width, height, captureTimeMs, hasCaptureTime);
		}
		else {
			injectByteArray(RTCDataChannel.copyWindow(data), rtpTimestamp, keyFrame,
					width, height, captureTimeMs, hasCaptureTime);
		}
	}

	private native void injectDirectBuffer(ByteBuffer data, int position, int length, long rtpTimestamp,
			boolean keyFrame, int width, int height, long captureTimeMs, boolean hasCaptureTime);

	private native void injectByteArray(byte[] data, long rtpTimestamp, boolean keyFrame,
			int width, int height, long captureTimeMs, boolean hasCaptureTime);

}
