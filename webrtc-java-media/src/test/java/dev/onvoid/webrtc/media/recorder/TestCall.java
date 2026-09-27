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

package dev.onvoid.webrtc.media.recorder;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCRtpCodecCapability;
import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.RTCRtpSender;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.MediaType;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrack;

/**
 * A call between two local peer connections that sends generated video
 * (320x240 at 30 frames a second) and audio (a tone) from the caller to the
 * callee, for recording.
 */
class TestCall implements AutoCloseable {

	static final int WIDTH = 320;
	static final int HEIGHT = 240;

	private final CustomVideoSource videoSource = new CustomVideoSource();
	private final CustomAudioSource audioSource = new CustomAudioSource();
	private final VideoTrack videoTrack;
	private final AudioTrack audioTrack;

	private final Peer caller;
	private final Peer callee;

	private final RTCRtpSender videoSender;
	private final RTCRtpSender audioSender;

	private volatile boolean feeding;
	private Thread feeder;


	TestCall(PeerConnectionFactory factory) throws Exception {
		caller = new Peer(factory);
		callee = new Peer(factory);

		caller.remote = callee;
		callee.remote = caller;

		videoTrack = factory.createVideoTrack("video", videoSource);
		audioTrack = factory.createAudioTrack("audio", audioSource);

		videoSender = caller.connection.addTrack(videoTrack, Collections.singletonList("stream"));
		audioSender = caller.connection.addTrack(audioTrack, Collections.singletonList("stream"));

		preferVp8(factory, caller.connection, videoSender);

		RTCSessionDescription offer = caller.createOffer();
		callee.setRemoteDescription(offer);
		RTCSessionDescription answer = callee.createAnswer();
		caller.setRemoteDescription(answer);

		if (!caller.connected.await(10, TimeUnit.SECONDS) || !callee.connected.await(10, TimeUnit.SECONDS)) {
			throw new IllegalStateException("The call did not connect");
		}

		feeding = true;
		feeder = new Thread(this::feed, "TestCall-feeder");
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
	 * Returns the callee's receiver of the video (0) or audio (1) transceiver,
	 * which follow the order of the offer. The instance is the caller's to
	 * dispose.
	 */
	RTCRtpReceiver getReceiver(int index) {
		RTCRtpTransceiver[] transceivers = callee.connection.getTransceivers();
		RTCRtpReceiver receiver = transceivers[index].getReceiver();

		for (RTCRtpTransceiver transceiver : transceivers) {
			transceiver.dispose();
		}

		return receiver;
	}

	@Override
	public void close() throws InterruptedException {
		feeding = false;
		feeder.join();

		videoSender.dispose();
		audioSender.dispose();

		caller.connection.close();
		callee.connection.close();

		videoTrack.dispose();
		audioTrack.dispose();
		videoSource.dispose();
		audioSource.dispose();
	}

	/**
	 * Makes VP8 the preferred video codec. Platforms prefer different codecs by
	 * default, macOS H.264 through VideoToolbox, and the tests should not
	 * depend on which: VP8 is available everywhere, and it is what the
	 * recordings are checked for.
	 */
	private static void preferVp8(PeerConnectionFactory factory, RTCPeerConnection connection,
			RTCRtpSender sender) {
		List<RTCRtpCodecCapability> codecs = new ArrayList<>(
				factory.getRtpSenderCapabilities(MediaType.VIDEO).getCodecs());

		codecs.sort(Comparator.comparing(codec -> !"VP8".equalsIgnoreCase(codec.getName())));

		for (RTCRtpTransceiver transceiver : connection.getTransceivers()) {
			RTCRtpSender transceiverSender = transceiver.getSender();

			if (transceiverSender.equals(sender)) {
				transceiver.setCodecPreferences(codecs);
			}

			transceiverSender.dispose();
			transceiver.dispose();
		}
	}

	private void feed() {
		byte[] audio = new byte[480 * 2];
		long startNs = System.nanoTime();
		long chunks = 0;
		long frames = 0;
		int shade = 0;

		while (feeding) {
			long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

			while (chunks * 10 <= elapsedMs) {
				for (int i = 0; i < 480; i++) {
					short sample = (short) (Math.sin(2 * Math.PI * 440 * (chunks * 480 + i) / 48000) * 8000);
					audio[2 * i] = (byte) sample;
					audio[2 * i + 1] = (byte) (sample >> 8);
				}

				audioSource.pushAudio(audio, 16, 48000, 1, 480);
				chunks++;
			}

			if (frames * 1000 / 30 <= elapsedMs) {
				NativeI420Buffer buffer = NativeI420Buffer.allocate(WIDTH, HEIGHT);
				ByteBuffer y = buffer.getDataY();

				for (int i = 0; i < y.capacity(); i++) {
					y.put(i, (byte) (i + shade));
				}
				shade += 3;

				VideoFrame frame = new VideoFrame(buffer, 0);
				videoSource.pushFrame(frame);
				frame.release();
				frames++;
			}

			try {
				Thread.sleep(2);
			}
			catch (InterruptedException e) {
				return;
			}
		}
	}


	private static class Peer implements PeerConnectionObserver {

		final RTCPeerConnection connection;

		final CountDownLatch connected = new CountDownLatch(1);

		volatile Peer remote;


		Peer(PeerConnectionFactory factory) {
			connection = factory.createPeerConnection(new RTCConfiguration(), this);
		}

		@Override
		public void onIceCandidate(RTCIceCandidate candidate) {
			remote.connection.addIceCandidate(candidate);
		}

		@Override
		public void onConnectionChange(RTCPeerConnectionState state) {
			if (state == RTCPeerConnectionState.CONNECTED) {
				connected.countDown();
			}
		}

		RTCSessionDescription createOffer() throws Exception {
			CompletableFuture<RTCSessionDescription> created = new CompletableFuture<>();
			connection.createOffer(new RTCOfferOptions(), created(created));

			return setLocalDescription(created.get(10, TimeUnit.SECONDS));
		}

		RTCSessionDescription createAnswer() throws Exception {
			CompletableFuture<RTCSessionDescription> created = new CompletableFuture<>();
			connection.createAnswer(new RTCAnswerOptions(), created(created));

			return setLocalDescription(created.get(10, TimeUnit.SECONDS));
		}

		void setRemoteDescription(RTCSessionDescription description) throws Exception {
			CompletableFuture<Void> set = new CompletableFuture<>();
			connection.setRemoteDescription(description, set(set));
			set.get(10, TimeUnit.SECONDS);
		}

		private RTCSessionDescription setLocalDescription(RTCSessionDescription description)
				throws Exception {
			CompletableFuture<Void> set = new CompletableFuture<>();
			connection.setLocalDescription(description, set(set));
			set.get(10, TimeUnit.SECONDS);

			return description;
		}

		private static CreateSessionDescriptionObserver created(
				CompletableFuture<RTCSessionDescription> future) {
			return new CreateSessionDescriptionObserver() {

				@Override
				public void onSuccess(RTCSessionDescription description) {
					future.complete(description);
				}

				@Override
				public void onFailure(String error) {
					future.completeExceptionally(new IllegalStateException(error));
				}
			};
		}

		private static SetSessionDescriptionObserver set(CompletableFuture<Void> future) {
			return new SetSessionDescriptionObserver() {

				@Override
				public void onSuccess() {
					future.complete(null);
				}

				@Override
				public void onFailure(String error) {
					future.completeExceptionally(new IllegalStateException(error));
				}
			};
		}
	}

}
