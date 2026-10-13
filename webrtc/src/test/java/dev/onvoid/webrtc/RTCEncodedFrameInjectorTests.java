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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import dev.onvoid.webrtc.media.MediaType;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests sending encoded frames through injectors. The frames are taken from a
 * regular call first, so that they are real encoder output.
 */
@Execution(ExecutionMode.SAME_THREAD)
class RTCEncodedFrameInjectorTests extends TestBase {

	private static final long TIMEOUT_SECONDS = 10;


	@Test
	void injectedVideoIsDecoded() throws Exception {
		List<CapturedFrame> frames = captureVideoFrames(30);

		TestPeerConnection caller = new TestPeerConnection(factory);
		TestPeerConnection callee = new TestPeerConnection(factory);
		AtomicInteger bitrate = new AtomicInteger();
		AtomicReference<String> size = new AtomicReference<>();
		CountDownLatch decoded = new CountDownLatch(10);

		try {
			RTCRtpTransceiver transceiver = addSendOnlyTransceiver(caller, MediaType.VIDEO, "VP8");
			RTCRtpSender sender = transceiver.getSender();

			RTCEncodedVideoFrameInjector injector = sender.createEncodedVideoFrameInjector(
					new RTCEncodedVideoFrameInjectorObserver() {
						@Override
						public void onKeyFrameRequest() { }

						@Override
						public void onBitrateChange(int allocatedBitrate, int availableOutgoingBitrate) {
							bitrate.set(allocatedBitrate);
						}
					});

			negotiate(caller, callee);

			RTCRtpReceiver receiver = getReceiver(callee);
			VideoTrack track = (VideoTrack) receiver.getTrack();
			VideoTrackSink sink = frame -> {
				size.set(frame.buffer.getWidth() + "x" + frame.buffer.getHeight());
				frame.release();
				decoded.countDown();
			};
			track.addSink(sink);

			// The frames are sent over and over, each round starting with the
			// key frame they begin with, at 30 frames per second.
			long rtpTimestamp = 0;
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

			while (decoded.getCount() > 0 && System.nanoTime() < deadline) {
				for (CapturedFrame frame : frames) {
					injector.injectFrame(ByteBuffer.wrap(frame.data), rtpTimestamp, frame.keyFrame,
							frame.width, frame.height);
					rtpTimestamp += 3000;
					Thread.sleep(33);
				}
			}

			assertEquals(0, decoded.getCount(), "too few injected frames decoded");
			assertEquals(frames.get(0).width + "x" + frames.get(0).height, size.get());
			assertTrue(bitrate.get() > 0, "no bitrate reported");

			track.removeSink(sink);
			receiver.dispose();
			injector.dispose();
			sender.dispose();
			transceiver.dispose();
		}
		finally {
			caller.close();
			callee.close();
		}
	}

	@Test
	void injectedAudioIsReceived() throws Exception {
		List<CapturedFrame> frames = captureAudioFrames(50);

		TestPeerConnection caller = new TestPeerConnection(factory);
		TestPeerConnection callee = new TestPeerConnection(factory);
		AtomicReference<byte[]> firstReceived = new AtomicReference<>();
		CountDownLatch received = new CountDownLatch(10);

		try {
			RTCRtpTransceiver transceiver = addSendOnlyTransceiver(caller, MediaType.AUDIO, "opus");
			RTCRtpSender sender = transceiver.getSender();
			RTCEncodedAudioFrameInjector injector = sender.createEncodedAudioFrameInjector(null);

			negotiate(caller, callee);

			int payloadType = -1;

			for (RTCRtpCodecParameters codec : sender.getParameters().codecs) {
				if ("opus".equalsIgnoreCase(codec.codecName)) {
					payloadType = codec.payloadType;
				}
			}

			assertTrue(payloadType >= 0, "opus was not negotiated");

			RTCRtpReceiver receiver = getReceiver(callee);
			receiver.setTransform(frame -> {
				ByteBuffer data = frame.getData();
				byte[] copy = new byte[data.remaining()];
				data.get(copy);
				firstReceived.compareAndSet(null, copy);
				received.countDown();
			});

			// Opus frames of 20 ms on its 48 kHz clock.
			long rtpTimestamp = 0;
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

			while (received.getCount() > 0 && System.nanoTime() < deadline) {
				for (CapturedFrame frame : frames) {
					ByteBuffer data = ByteBuffer.allocateDirect(frame.data.length);
					data.put(frame.data).flip();

					injector.injectFrame(data, payloadType, rtpTimestamp);
					rtpTimestamp += 960;
					Thread.sleep(20);
				}
			}

			assertEquals(0, received.getCount(), "too few injected frames received");
			assertNotNull(firstReceived.get());
			assertTrue(frames.stream().anyMatch(frame -> Arrays.equals(frame.data, firstReceived.get())),
					"the received frame is not one that was injected");

			receiver.setTransform(null);
			receiver.dispose();
			injector.dispose();
			sender.dispose();
			transceiver.dispose();
		}
		finally {
			caller.close();
			callee.close();
		}
	}

	@Test
	void injectorNeedsTracklessSender() throws Exception {
		try (TestMediaCall call = new TestMediaCall(factory, true, false)) {
			RTCRtpSender sender = call.getVideoSender();

			// The sender has a track.
			assertThrows(IllegalStateException.class, () -> sender.createEncodedVideoFrameInjector(null));
			// The sender sends video.
			assertThrows(IllegalStateException.class, () -> sender.createEncodedAudioFrameInjector(null));
		}
	}

	@Test
	void injectorRejectsInvalidFrames() throws Exception {
		TestPeerConnection caller = new TestPeerConnection(factory);

		try {
			RTCRtpTransceiver transceiver = addSendOnlyTransceiver(caller, MediaType.VIDEO, "VP8");
			RTCRtpSender sender = transceiver.getSender();
			RTCEncodedVideoFrameInjector injector = sender.createEncodedVideoFrameInjector(null);
			ByteBuffer data = ByteBuffer.allocate(1);

			// A sender takes one injector only.
			assertThrows(IllegalStateException.class, () -> sender.createEncodedVideoFrameInjector(null));

			assertThrows(NullPointerException.class, () -> injector.injectFrame(null, 0, true, 320, 240));
			assertThrows(IllegalArgumentException.class, () -> injector.injectFrame(data, -1, true, 320, 240));
			assertThrows(IllegalArgumentException.class, () -> injector.injectFrame(data, 0, true, 0, 240));
			assertThrows(IllegalArgumentException.class, () -> injector.injectFrame(data, 0, true, 320, 70000));

			injector.dispose();
			sender.dispose();
			transceiver.dispose();
		}
		finally {
			caller.close();
		}
	}

	/**
	 * Takes the given number of encoded video frames from a call, starting
	 * with the key frame a stream begins with.
	 */
	private List<CapturedFrame> captureVideoFrames(int count) throws Exception {
		List<CapturedFrame> frames = Collections.synchronizedList(new ArrayList<>());
		CountDownLatch captured = new CountDownLatch(count);

		try (TestMediaCall call = new TestMediaCall(factory, true, false)) {
			call.getVideoSender().setTransform(frame -> {
				RTCEncodedVideoFrame videoFrame = (RTCEncodedVideoFrame) frame;

				if (captured.getCount() > 0 && (!frames.isEmpty() || videoFrame.isKeyFrame())) {
					frames.add(new CapturedFrame(frame, videoFrame.isKeyFrame(),
							videoFrame.getWidth(), videoFrame.getHeight()));
					captured.countDown();
				}
			});

			call.negotiate();
			call.awaitConnected();
			call.startMedia();

			assertTrue(captured.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "too few video frames encoded");
		}

		return new ArrayList<>(frames);
	}

	/**
	 * Takes the given number of encoded audio frames from a call.
	 */
	private List<CapturedFrame> captureAudioFrames(int count) throws Exception {
		List<CapturedFrame> frames = Collections.synchronizedList(new ArrayList<>());
		CountDownLatch captured = new CountDownLatch(count);

		try (TestMediaCall call = new TestMediaCall(factory, false, true)) {
			call.getAudioSender().setTransform(frame -> {
				if (captured.getCount() > 0 && frame.getSize() > 0) {
					frames.add(new CapturedFrame(frame, false, 0, 0));
					captured.countDown();
				}
			});

			call.negotiate();
			call.awaitConnected();
			call.startMedia();

			assertTrue(captured.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "too few audio frames encoded");
		}

		return new ArrayList<>(frames);
	}

	/**
	 * Adds a transceiver that sends without a track, preferring the named codec.
	 */
	private RTCRtpTransceiver addSendOnlyTransceiver(TestPeerConnection peer, MediaType mediaType, String codec) {
		RTCRtpTransceiverInit init = new RTCRtpTransceiverInit();
		init.direction = RTCRtpTransceiverDirection.SEND_ONLY;

		RTCRtpTransceiver transceiver = peer.getPeerConnection().addTransceiver(mediaType, init);

		List<RTCRtpCodecCapability> codecs = new ArrayList<>(
				factory.getRtpSenderCapabilities(mediaType).getCodecs());
		codecs.sort(Comparator.comparing(capability -> !codec.equalsIgnoreCase(capability.getName())));
		transceiver.setCodecPreferences(codecs);

		return transceiver;
	}

	private static void negotiate(TestPeerConnection caller, TestPeerConnection callee) throws Exception {
		caller.setRemotePeerConnection(callee);
		callee.setRemotePeerConnection(caller);

		callee.setRemoteDescription(caller.createOffer());
		caller.setRemoteDescription(callee.createAnswer());

		caller.waitUntilConnected();
		callee.waitUntilConnected();
	}

	/**
	 * Returns the receiver of the callee's only transceiver, the caller's to
	 * dispose.
	 */
	private static RTCRtpReceiver getReceiver(TestPeerConnection callee) {
		RTCRtpTransceiver[] transceivers = callee.getPeerConnection().getTransceivers();
		RTCRtpReceiver receiver = transceivers[0].getReceiver();

		for (RTCRtpTransceiver transceiver : transceivers) {
			transceiver.dispose();
		}

		return receiver;
	}



	private static class CapturedFrame {

		final byte[] data;
		final boolean keyFrame;
		final int width;
		final int height;


		CapturedFrame(RTCEncodedFrame frame, boolean keyFrame, int width, int height) {
			ByteBuffer payload = frame.getData();

			this.data = new byte[payload.remaining()];
			this.keyFrame = keyFrame;
			this.width = width;
			this.height = height;

			payload.get(data);
		}
	}

}
