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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests that a CustomVideoSource adapts what it delivers to what the encoder
 * asks for, the way a camera or a desktop source does. The encoder lowers the
 * resolution when the bitrate cannot carry the frames it is given; a source
 * that ignores that leaves the encoder dropping most frames instead, which
 * shows up as a video that plays at a few frames a second.
 */
@Execution(ExecutionMode.SAME_THREAD)
class CustomVideoSourceAdaptationTests extends TestBase {

	private static final int WIDTH = 1280;
	private static final int HEIGHT = 720;


	@Test
	void lowBitrateScalesDown() throws Exception {
		CustomVideoSource source = new CustomVideoSource();
		VideoTrack track = factory.createVideoTrack("video", source);

		TestPeerConnection caller = new TestPeerConnection(factory);
		TestPeerConnection callee = new TestPeerConnection(factory);

		RTCRtpSender sender = caller.getPeerConnection()
				.addTrack(track, Collections.singletonList("stream"));

		caller.setRemotePeerConnection(callee);
		callee.setRemotePeerConnection(caller);

		callee.setRemoteDescription(caller.createOffer());
		caller.setRemoteDescription(callee.createAnswer());

		caller.waitUntilConnected();
		callee.waitUntilConnected();

		// Far too little for 720p of noise, so the encoder has to give way.
		RTCRtpSendParameters parameters = sender.getParameters();
		parameters.encodings.get(0).maxBitrate = 150_000;
		sender.setParameters(parameters);

		Random random = new Random(1);
		byte[] noise = new byte[WIDTH * HEIGHT];
		long frameUs = 1_000_000 / 30;
		// Generous, for runners slow enough to take a while over encoding
		// 720p of noise; a source that adapts gets there within a second or
		// two everywhere else.
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		long nextStatsNs = 0;
		long sentWidth = WIDTH;
		int pushed = 0;
		Map<String, Object> outbound = Collections.emptyMap();

		try {
			while (System.nanoTime() < deadline && sentWidth >= WIDTH) {
				NativeI420Buffer buffer = NativeI420Buffer.allocate(WIDTH, HEIGHT);

				// Noise, because a flat picture costs next to nothing to encode
				// and would never make the encoder ask for less.
				random.nextBytes(noise);
				ByteBuffer y = buffer.getDataY();
				y.put(noise, 0, y.remaining());

				VideoFrame frame = new VideoFrame(buffer, 0);
				source.pushFrame(frame);
				frame.release();
				pushed++;

				if (System.nanoTime() > nextStatsNs) {
					nextStatsNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
					outbound = outboundVideoStats(caller.getPeerConnection());

					Object width = outbound.get("frameWidth");

					if (width instanceof Number) {
						sentWidth = ((Number) width).longValue();
					}
				}

				Thread.sleep(frameUs / 1000);
			}

			// What the sender reported last says whether it encoded at all and
			// what held it back, which is what a failure on a platform that
			// cannot be run locally has to go on.
			assertTrue(sentWidth < WIDTH, String.format(
					"the encoder kept receiving %dx%d frames; %d frames pushed, "
							+ "last outbound stats: framesEncoded=%s, framesSent=%s, "
							+ "framesPerSecond=%s, frameWidth=%s, targetBitrate=%s, "
							+ "qualityLimitationReason=%s, "
							+ "qualityLimitationResolutionChanges=%s, encoderImplementation=%s",
					WIDTH, HEIGHT, pushed, outbound.get("framesEncoded"),
					outbound.get("framesSent"), outbound.get("framesPerSecond"),
					outbound.get("frameWidth"), outbound.get("targetBitrate"),
					outbound.get("qualityLimitationReason"),
					outbound.get("qualityLimitationResolutionChanges"),
					outbound.get("encoderImplementation")));
		}
		finally {
			sender.dispose();
			caller.close();
			callee.close();
			track.dispose();
			source.dispose();
		}
	}

	/**
	 * Returns the sender's outbound video stats, or nothing while it has not
	 * reported any yet.
	 */
	private static Map<String, Object> outboundVideoStats(RTCPeerConnection peerConnection)
			throws Exception {
		CompletableFuture<RTCStatsReport> future = new CompletableFuture<>();
		peerConnection.getStats(future::complete);

		for (RTCStats stats : future.get(2, TimeUnit.SECONDS).getStats().values()) {
			if (stats.getType() == RTCStatsType.OUTBOUND_RTP
					&& "video".equals(stats.getAttributes().get("kind"))) {
				return stats.getAttributes();
			}
		}

		return Collections.emptyMap();
	}
}
