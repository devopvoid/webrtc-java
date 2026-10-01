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
 * Creates the video decoders of a {@link dev.onvoid.webrtc.PeerConnectionFactory
 * PeerConnectionFactory}, which decide what video codecs it can receive.
 * <p>
 * A factory may return its own {@link VideoDecoder}s as well as the built-in
 * ones of a {@link DefaultVideoDecoderFactory}, the same way a {@link
 * VideoEncoderFactory} does.
 * <p>
 * {@link #createDecoder(VideoCodecInfo)} is called from a WebRTC thread.
 *
 * @author Alex Andres
 *
 * @see dev.onvoid.webrtc.PeerConnectionFactory.Builder#setVideoDecoderFactory(VideoDecoderFactory)
 */
public interface VideoDecoderFactory {

	/**
	 * Returns the codecs this factory can create decoders for, in order of
	 * preference. Asked for once, when the peer connection factory is
	 * created.
	 *
	 * @return The supported codecs.
	 */
	List<VideoCodecInfo> getSupportedCodecs();

	/**
	 * Creates a decoder for one of the supported codecs. Each call has to
	 * return a new decoder, since WebRTC uses one per stream.
	 *
	 * @param info The codec to decode, as negotiated.
	 *
	 * @return A new decoder, or {@code null} if the codec is not supported.
	 */
	VideoDecoder createDecoder(VideoCodecInfo info);

}
