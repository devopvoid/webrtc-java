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
 * Sends audio frames that are already encoded on an {@link RTCRtpSender},
 * in place of audio from a track: WebRTC packetizes and sends them as they
 * are, without encoding anything itself.
 * <p>
 * The frames must be in a codec the sender negotiated, named by the payload
 * type each frame is injected with; the sender's
 * {@link RTCRtpSender#getParameters() parameters} list them. The injector takes
 * the place of the sender's track: it can only be created for a sender without
 * one, and {@link RTCRtpSender#replaceTrack(dev.onvoid.webrtc.media.MediaStreamTrack)
 * giving the sender a track} ends the injection.
 * <p>
 * Frames can be injected from any thread.
 *
 * @author Alex Andres
 *
 * @see RTCRtpSender#createEncodedAudioFrameInjector(RTCEncodedAudioFrameInjectorObserver)
 */
public class RTCEncodedAudioFrameInjector extends DisposableNativeObject {

	/*
	 * The native observer set through this object, owned by it and freed when
	 * disposed.
	 */
	@SuppressWarnings("unused")
	private long observerHandle;


	/**
	 * Constructor to be used by the native api.
	 */
	private RTCEncodedAudioFrameInjector() {

	}

	/**
	 * Sends an encoded frame. The bytes between the position and the limit of
	 * the buffer are copied before this method returns, so the buffer may be
	 * reused right away; its position and limit stay as they are.
	 *
	 * @param data         The encoded frame.
	 * @param payloadType  The payload type of the frame's codec.
	 * @param rtpTimestamp The RTP timestamp of the frame, on the codec's
	 *                     clock, without the sender's random offset.
	 */
	public void injectFrame(ByteBuffer data, int payloadType, long rtpTimestamp) {
		injectFrame(data, payloadType, rtpTimestamp, null, null);
	}

	/**
	 * Sends an encoded frame, along with its audio level and capture time.
	 * The bytes between the position and the limit of the buffer are copied
	 * before this method returns, so the buffer may be reused right away; its
	 * position and limit stay as they are.
	 *
	 * @param data          The encoded frame.
	 * @param payloadType   The payload type of the frame's codec.
	 * @param rtpTimestamp  The RTP timestamp of the frame, on the codec's
	 *                      clock, without the sender's random offset.
	 * @param audioLevel    The audio level in -dBov, from 0 (loudest) to 127
	 *                      (silence), or null to send none.
	 * @param captureTimeMs The absolute time the frame was captured, in
	 *                      milliseconds, or null to send none.
	 */
	public void injectFrame(ByteBuffer data, int payloadType, long rtpTimestamp, Integer audioLevel,
			Long captureTimeMs) {
		Objects.requireNonNull(data, "data");

		if (payloadType < 0 || payloadType > 127) {
			throw new IllegalArgumentException("Payload type out of range: " + payloadType);
		}
		if (rtpTimestamp < 0 || rtpTimestamp > 0xFFFFFFFFL) {
			throw new IllegalArgumentException("RTP timestamp out of the 32-bit range: " + rtpTimestamp);
		}
		if (audioLevel != null && (audioLevel < 0 || audioLevel > 127)) {
			throw new IllegalArgumentException("Audio level out of range: " + audioLevel);
		}

		int level = audioLevel != null ? audioLevel : 0;
		long captureTime = captureTimeMs != null ? captureTimeMs : 0;

		if (data.isDirect()) {
			injectDirectBuffer(data, data.position(), data.remaining(), payloadType, rtpTimestamp,
					level, audioLevel != null, captureTime, captureTimeMs != null);
		}
		else {
			injectByteArray(RTCDataChannel.copyWindow(data), payloadType, rtpTimestamp,
					level, audioLevel != null, captureTime, captureTimeMs != null);
		}
	}

	/**
	 * Releases the native injector. The sender keeps sending nothing until it
	 * gets a track again, and the observer is not called any more once this
	 * returns.
	 */
	@Override
	public native void dispose();

	private native void injectDirectBuffer(ByteBuffer data, int position, int length, int payloadType,
			long rtpTimestamp, int audioLevel, boolean hasAudioLevel, long captureTimeMs, boolean hasCaptureTime);

	private native void injectByteArray(byte[] data, int payloadType, long rtpTimestamp,
			int audioLevel, boolean hasAudioLevel, long captureTimeMs, boolean hasCaptureTime);

}
