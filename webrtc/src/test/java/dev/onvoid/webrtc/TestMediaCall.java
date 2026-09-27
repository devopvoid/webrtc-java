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
import java.util.Collections;

import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrack;

/**
 * A call between two local peer connections that sends generated video and
 * audio from the caller to the callee, for tests that need media flowing.
 * <p>
 * The call is set up in steps, so that a test can prepare the senders before
 * anything is negotiated and the receivers before media flows: construct it,
 * {@link #negotiate()}, {@link #awaitConnected()}, then {@link #startMedia()}.
 */
class TestMediaCall implements AutoCloseable {

	private static final int WIDTH = 320;
	private static final int HEIGHT = 240;

	private final CustomVideoSource videoSource;
	private final CustomAudioSource audioSource;
	private final VideoTrack videoTrack;
	private final AudioTrack audioTrack;

	private final TestPeerConnection caller;
	private final TestPeerConnection callee;

	private final RTCRtpSender videoSender;
	private final RTCRtpSender audioSender;

	private volatile boolean feeding;
	private Thread feeder;


	TestMediaCall(PeerConnectionFactory factory, boolean video, boolean audio) {
		caller = new TestPeerConnection(factory);
		callee = new TestPeerConnection(factory);

		caller.setRemotePeerConnection(callee);
		callee.setRemotePeerConnection(caller);

		if (video) {
			videoSource = new CustomVideoSource();
			videoTrack = factory.createVideoTrack("video", videoSource);
			videoSender = caller.getPeerConnection().addTrack(videoTrack,
					Collections.singletonList("stream"));
		}
		else {
			videoSource = null;
			videoTrack = null;
			videoSender = null;
		}

		if (audio) {
			audioSource = new CustomAudioSource();
			audioTrack = factory.createAudioTrack("audio", audioSource);
			audioSender = caller.getPeerConnection().addTrack(audioTrack,
					Collections.singletonList("stream"));
		}
		else {
			audioSource = null;
			audioTrack = null;
			audioSender = null;
		}
	}

	/**
	 * Negotiates the call. Once this returns, the callee has its receivers,
	 * though media only starts with {@link #startMedia()}.
	 */
	void negotiate() throws Exception {
		callee.setRemoteDescription(caller.createOffer());
		caller.setRemoteDescription(callee.createAnswer());
	}

	/**
	 * Waits for both ends to connect.
	 */
	void awaitConnected() throws InterruptedException {
		caller.waitUntilConnected();
		callee.waitUntilConnected();
	}

	/**
	 * Starts pushing video at 30 frames and audio in 10 ms chunks per second.
	 */
	void startMedia() {
		feeding = true;
		feeder = new Thread(this::feed, "TestMediaCall-feeder");
		feeder.setDaemon(true);
		feeder.start();
	}

	RTCRtpSender getVideoSender() {
		return videoSender;
	}

	RTCRtpSender getAudioSender() {
		return audioSender;
	}

	/**
	 * Returns new instances of all of the caller's senders, which are the
	 * caller's to dispose.
	 */
	RTCRtpSender[] getCallerSenders() {
		return caller.getPeerConnection().getSenders();
	}

	/**
	 * Returns the callee's receiver of the given kind. The instance is the
	 * caller's to dispose.
	 */
	RTCRtpReceiver getReceiver(String kind) {
		boolean video = MediaStreamTrack.VIDEO_TRACK_KIND.equals(kind);

		if (video ? videoSender == null : audioSender == null) {
			throw new IllegalStateException("The call has no " + kind);
		}

		// The callee's transceivers follow the order of the offer, which has
		// video first. A remote track belongs to its receiver, so its kind is
		// not asked for here: disposing of it again would not be allowed.
		int index = video || videoSender == null ? 0 : 1;

		RTCRtpTransceiver[] transceivers = callee.getPeerConnection().getTransceivers();
		RTCRtpReceiver receiver = transceivers[index].getReceiver();

		for (RTCRtpTransceiver transceiver : transceivers) {
			transceiver.dispose();
		}

		return receiver;
	}

	@Override
	public void close() throws InterruptedException {
		feeding = false;

		if (feeder != null) {
			feeder.join();
		}

		if (videoSender != null) {
			videoSender.dispose();
		}
		if (audioSender != null) {
			audioSender.dispose();
		}

		caller.close();
		callee.close();

		if (videoTrack != null) {
			videoTrack.dispose();
			videoSource.dispose();
		}
		if (audioTrack != null) {
			audioTrack.dispose();
			audioSource.dispose();
		}
	}

	private void feed() {
		byte[] audio = new byte[480 * 2];
		long startNs = System.nanoTime();
		long audioChunks = 0;
		long videoFrames = 0;
		int shade = 0;

		while (feeding) {
			long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

			while (audioSource != null && audioChunks * 10 <= elapsedMs) {
				// A tone, so that the encoder has something to encode.
				for (int i = 0; i < 480; i++) {
					short sample = (short) (Math.sin(2 * Math.PI * 440 * (audioChunks * 480 + i) / 48000) * 8000);
					audio[2 * i] = (byte) sample;
					audio[2 * i + 1] = (byte) (sample >> 8);
				}

				audioSource.pushAudio(audio, 16, 48000, 1, 480);
				audioChunks++;
			}

			if (videoSource != null && videoFrames * 1000 / 30 <= elapsedMs) {
				NativeI420Buffer buffer = NativeI420Buffer.allocate(WIDTH, HEIGHT);
				ByteBuffer y = buffer.getDataY();

				// A moving gradient, so that frames differ from each other.
				for (int i = 0; i < y.capacity(); i++) {
					y.put(i, (byte) (i + shade));
				}
				shade += 3;

				VideoFrame frame = new VideoFrame(buffer, 0);
				videoSource.pushFrame(frame);
				frame.release();
				videoFrames++;
			}

			try {
				Thread.sleep(2);
			}
			catch (InterruptedException e) {
				return;
			}
		}
	}

}
