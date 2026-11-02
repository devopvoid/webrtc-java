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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.onvoid.webrtc.media.audio.AudioConverter;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.VideoTrack;

import java.util.AbstractList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Disposing and closing native objects: once only, from any thread, also
 * while another thread is still in a method of the object.
 */
class NativeDisposeTests extends TestBase {

	private final PeerConnectionObserver observer = candidate -> { };

	private ExecutorService executor;


	@BeforeEach
	void createExecutor() {
		executor = Executors.newCachedThreadPool();
	}

	@AfterEach
	void shutdownExecutor() {
		executor.shutdownNow();
	}

	@Test
	void closeTwiceDoesNothing() {
		RTCPeerConnection peerConnection = factory.createPeerConnection(new RTCConfiguration(), observer);

		peerConnection.close();

		assertDoesNotThrow(peerConnection::close);
	}

	@Test
	void callAfterCloseThrows() {
		RTCPeerConnection peerConnection = factory.createPeerConnection(new RTCConfiguration(), observer);

		peerConnection.close();

		assertDisposed("RTCPeerConnection is closed", peerConnection::getSenders);
		assertDisposed("RTCPeerConnection is closed", () -> peerConnection.createDataChannel("data", new RTCDataChannelInit()));

		// The state getters report a closed connection instead.
		assertEquals(RTCPeerConnectionState.CLOSED, peerConnection.getConnectionState());
	}

	@Test
	void disposeTwiceDoesNothing() {
		RTCPeerConnection peerConnection = factory.createPeerConnection(new RTCConfiguration(), observer);
		CustomVideoSource source = new CustomVideoSource();
		VideoTrack track = factory.createVideoTrack("video", source);

		RTCRtpTransceiver transceiver = peerConnection.addTransceiver(track, new RTCRtpTransceiverInit());
		RTCRtpSender sender = transceiver.getSender();
		RTCRtpReceiver receiver = transceiver.getReceiver();
		RTCDataChannel channel = peerConnection.createDataChannel("data", new RTCDataChannelInit());
		AudioConverter converter = new AudioConverter(48000, 1, 48000, 1);

		disposeTwice(channel::dispose);
		disposeTwice(receiver::dispose);
		disposeTwice(sender::dispose);
		disposeTwice(transceiver::dispose);
		disposeTwice(peerConnection::close);
		disposeTwice(track::dispose);
		disposeTwice(source::dispose);
		disposeTwice(converter::dispose);

		assertDisposed("RTCDataChannel is disposed", channel::getLabel);
		assertDisposed("RTCRtpReceiver is disposed", receiver::getId);
		assertDisposed("RTCRtpSender is disposed", sender::getId);
		assertDisposed("RTCRtpTransceiver is disposed", transceiver::getMid);
		assertDisposed("VideoTrack is disposed", track::getId);
		assertDisposed("CustomVideoSource is disposed", source::getState);
		assertDisposed("AudioConverter is disposed", () -> converter.convert(new byte[960], new byte[960]));
	}

	@Test
	void disposeFactoryTwice() {
		AudioDeviceModule audioModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
		PeerConnectionFactory disposable = new PeerConnectionFactory(audioModule);

		disposeTwice(disposable::dispose);
		disposeTwice(audioModule::dispose);

		assertDisposed("PeerConnectionFactory is disposed",
				() -> disposable.createPeerConnection(new RTCConfiguration(), observer));
	}

	@Test
	void closeDuringCall() throws Exception {
		RTCPeerConnection peerConnection = factory.createPeerConnection(new RTCConfiguration(), observer);
		CustomVideoSource source = new CustomVideoSource();
		VideoTrack track = factory.createVideoTrack("video", source);
		HeldList streamIds = new HeldList();

		// addTrack reads the stream IDs while it uses the native connection.
		Future<RTCRtpSender> added = executor.submit(() -> peerConnection.addTrack(track, streamIds));

		streamIds.reached.await();

		// Returns although addTrack still uses the connection, which must
		// not be freed under it.
		peerConnection.close();

		streamIds.release.countDown();

		RTCRtpSender sender = added.get();

		assertNotNull(sender);
		assertDisposed("RTCPeerConnection is closed", peerConnection::getSenders);

		sender.dispose();
		track.dispose();
		source.dispose();
	}

	@Test
	void disposeTrackDuringCall() throws Exception {
		RTCPeerConnection peerConnection = factory.createPeerConnection(new RTCConfiguration(), observer);
		CustomVideoSource source = new CustomVideoSource();
		VideoTrack track = factory.createVideoTrack("video", source);
		HeldList streamIds = new HeldList();

		Future<RTCRtpSender> added = executor.submit(() -> peerConnection.addTrack(track, streamIds));

		streamIds.reached.await();

		// The track is the only owner of its native track, which addTrack
		// still uses.
		track.dispose();

		streamIds.release.countDown();

		RTCRtpSender sender = added.get();

		assertNotNull(sender);
		assertDisposed("VideoTrack is disposed", track::getId);

		sender.dispose();
		peerConnection.close();
		source.dispose();
	}

	@Test
	void concurrentCloseDuringCall() throws Exception {
		RTCPeerConnection peerConnection = factory.createPeerConnection(new RTCConfiguration(), observer);
		CustomVideoSource source = new CustomVideoSource();
		VideoTrack track = factory.createVideoTrack("video", source);
		HeldList streamIds = new HeldList();

		Future<RTCRtpSender> added = executor.submit(() -> peerConnection.addTrack(track, streamIds));

		streamIds.reached.await();

		// Two threads close at once; only one may get the connection, whose
		// close then waits for addTrack.
		CountDownLatch start = new CountDownLatch(1);
		Future<?> first = executor.submit(() -> awaitAndRun(start, peerConnection::close));
		Future<?> second = executor.submit(() -> awaitAndRun(start, peerConnection::close));

		start.countDown();
		first.get();
		second.get();

		streamIds.release.countDown();

		RTCRtpSender sender = added.get();

		assertNotNull(sender);
		assertDisposed("RTCPeerConnection is closed", peerConnection::getSenders);

		sender.dispose();
		track.dispose();
		source.dispose();
	}

	private static void disposeTwice(Runnable dispose) {
		dispose.run();

		assertDoesNotThrow(dispose::run);
	}

	private static void assertDisposed(String message, Executable executable) {
		IllegalStateException e = assertThrows(IllegalStateException.class, executable);

		assertEquals(message, e.getMessage());
	}

	private static void awaitAndRun(CountDownLatch start, Runnable runnable) {
		try {
			start.await();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		}

		runnable.run();
	}


	/**
	 * A list of one stream ID whose element is handed out only once the test
	 * lets it: the native method reading it waits meanwhile.
	 */
	private static class HeldList extends AbstractList<String> {

		final CountDownLatch reached = new CountDownLatch(1);

		final CountDownLatch release = new CountDownLatch(1);


		@Override
		public String get(int index) {
			reached.countDown();

			try {
				release.await();
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}

			return "stream";
		}

		@Override
		public int size() {
			return 1;
		}
	}

}
