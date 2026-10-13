/*
 * Copyright 2019 Alex Andres
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

package dev.onvoid.webrtc.media.audio;

import dev.onvoid.webrtc.RTCRtpPacketInfo;

public interface AudioTrackSink {

	void onData(byte[] data, int bitsPerSample, int sampleRate, int channels, int frames);

	/**
	 * Receives audio along with the RTP packets it was assembled from, which
	 * tell who sent and contributed to it and how loud each was. A sink that
	 * implements this method is called with it instead of the one without
	 * packets; the packets are only collected for such a sink.
	 * <p>
	 * Audio of a local track has no packets, so the array is empty for it.
	 *
	 * @param data          The audio samples.
	 * @param bitsPerSample The size of a sample in bits.
	 * @param sampleRate    The sample rate in Hz.
	 * @param channels      The number of channels.
	 * @param frames        The number of frames, samples per channel.
	 * @param packetInfos   The packets the audio was assembled from.
	 */
	default void onData(byte[] data, int bitsPerSample, int sampleRate, int channels, int frames,
			RTCRtpPacketInfo[] packetInfos) {
		onData(data, bitsPerSample, sampleRate, channels, frames);
	}

}
