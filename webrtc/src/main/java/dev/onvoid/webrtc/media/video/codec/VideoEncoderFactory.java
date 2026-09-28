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

import java.util.List;

/**
 * Creates the video encoders of a {@link dev.onvoid.webrtc.PeerConnectionFactory
 * PeerConnectionFactory}, which decide what video codecs it can send.
 * <p>
 * A factory may return its own {@link VideoEncoder}s as well as the built-in
 * ones of a {@link DefaultVideoEncoderFactory}, for example to add a codec
 * while keeping the built-in ones:
 * <pre>{@code
 * class MyEncoderFactory implements VideoEncoderFactory {
 *
 *     private final DefaultVideoEncoderFactory builtIn = new DefaultVideoEncoderFactory();
 *
 *     public List<VideoCodecInfo> getSupportedCodecs() {
 *         List<VideoCodecInfo> codecs = new ArrayList<>(builtIn.getSupportedCodecs());
 *         codecs.add(new VideoCodecInfo("X-MYCODEC"));
 *         return codecs;
 *     }
 *
 *     public VideoEncoder createEncoder(VideoCodecInfo info) {
 *         if (info.getName().equals("X-MYCODEC")) {
 *             return new MyEncoder();
 *         }
 *         return builtIn.createEncoder(info);
 *     }
 * }
 * }</pre>
 * <p>
 * {@link #createEncoder(VideoCodecInfo)} is called from a WebRTC thread.
 *
 * @author Alex Andres
 *
 * @see dev.onvoid.webrtc.PeerConnectionFactory.Builder#setVideoEncoderFactory(VideoEncoderFactory)
 */
public interface VideoEncoderFactory {

	/**
	 * Returns the codecs this factory can create encoders for, in order of
	 * preference. Asked for once, when the peer connection factory is
	 * created.
	 *
	 * @return The supported codecs.
	 */
	List<VideoCodecInfo> getSupportedCodecs();

	/**
	 * Creates an encoder for one of the supported codecs. Each call has to
	 * return a new encoder, since WebRTC uses one per stream.
	 *
	 * @param info The codec to encode, as negotiated.
	 *
	 * @return A new encoder, or {@code null} if the codec is not supported.
	 */
	VideoEncoder createEncoder(VideoCodecInfo info);

}
