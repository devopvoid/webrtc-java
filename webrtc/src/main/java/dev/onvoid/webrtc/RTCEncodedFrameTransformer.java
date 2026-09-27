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

/**
 * Transforms the encoded frames of an {@link RTCRtpSender} or an {@link
 * RTCRtpReceiver}, the way WebRTC Encoded Transforms (insertable streams) do
 * in a browser. A sender's transform sees every frame after the encoder and
 * before the packetizer, a receiver's after the depacketizer and before the
 * decoder. This is where end-to-end encryption, frame metadata or custom
 * frame analysis go.
 * <p>
 * A frame the transform returns from is sent on, with whatever changes were
 * made to it, unless the transform {@link RTCEncodedFrame#drop() dropped} it.
 * If the transform throws, the frame is dropped: sending it on unchanged
 * could send in the clear what an encryption transform was meant to protect.
 * <p>
 * The transform runs on a thread of its own, one per sender or receiver, and
 * never on a thread that carries media, so a slow transform delays only its
 * own frames and may safely call back into the peer connection. Frames arrive
 * in order, one at a time. A transform that falls several seconds behind has
 * frames dropped until it catches up.
 * <p>
 * Example, a (deliberately trivial) encryption of the payload:
 * <pre>{@code
 * sender.setTransform(frame -> {
 *     ByteBuffer data = frame.getData();
 *
 *     for (int i = 0; i < data.limit(); i++) {
 *         data.put(i, (byte) (data.get(i) ^ 0x5A));
 *     }
 *
 *     frame.setData(data);
 * });
 * }</pre>
 *
 * @author Alex Andres
 *
 * @see RTCRtpSender#setTransform(RTCEncodedFrameTransformer)
 * @see RTCRtpReceiver#setTransform(RTCEncodedFrameTransformer)
 */
@FunctionalInterface
public interface RTCEncodedFrameTransformer {

	/**
	 * Transforms one encoded frame in place. The frame, and any buffer it
	 * returned, is valid only until this method returns, and only on the
	 * thread that called it.
	 *
	 * @param frame The frame to transform, an {@link RTCEncodedVideoFrame} or
	 *              an {@link RTCEncodedAudioFrame}.
	 */
	void transform(RTCEncodedFrame frame);

}
