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

package dev.onvoid.webrtc.livekit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import dev.onvoid.webrtc.client.AudioMode;
import dev.onvoid.webrtc.client.CustomVideo;
import dev.onvoid.webrtc.client.MediaKind;
import dev.onvoid.webrtc.client.RemoteTrack;
import dev.onvoid.webrtc.client.WebRtc;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;

/**
 * Runs against a LiveKit server, such as {@code livekit-server --dev}, whose
 * URL the system property livekit.url names ({@code -Dlivekit.url=ws://localhost:7880}).
 * Without it the tests are skipped.
 */
@EnabledIfSystemProperty(named = "livekit.url", matches = ".+")
class RoomIntegrationTest {

	private static final long TIMEOUT_SECONDS = 20;

	private WebRtc rtc;

	private ScheduledExecutorService frames;

	private String roomName;


	@BeforeEach
	void setUp() {
		rtc = WebRtc.builder().audio(AudioMode.HEADLESS).build();
		frames = Executors.newSingleThreadScheduledExecutor();
		roomName = "test-" + UUID.randomUUID();
	}

	@AfterEach
	void tearDown() {
		frames.shutdownNow();
		rtc.close();
	}

	@Test
	void participantsSeeEachOther() throws Exception {
		Room alice = join("alice");
		CompletableFuture<Participant> joined = new CompletableFuture<>();
		CompletableFuture<Participant> left = new CompletableFuture<>();
		alice.onParticipantJoined(joined::complete);
		alice.onParticipantLeft(left::complete);

		Room bob = join("bob");

		assertEquals(roomName, alice.name());
		assertEquals(RoomState.CONNECTED, bob.state());
		assertEquals("bob", await(joined).identity());
		assertEquals("alice", bob.remoteParticipants().get(0).identity());

		bob.closeAsync().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

		assertEquals(RoomState.DISCONNECTED, bob.state());
		assertEquals("bob", await(left).identity());

		alice.close();
	}

	@Test
	void subscriberReceivesPublishedVideo() throws Exception {
		Room alice = join("alice");
		Room bob = join("bob");

		CompletableFuture<Participant> publisherOfTrack = new CompletableFuture<>();
		CompletableFuture<RemoteTrack> received = new CompletableFuture<>();
		bob.onTrackSubscribed((participant, track) -> {
			publisherOfTrack.complete(participant);
			received.complete(track);
		});

		CustomVideo video = rtc.media().customVideo("camera-" + UUID.randomUUID());
		frames.scheduleAtFixedRate(() -> push(video), 0, 66, TimeUnit.MILLISECONDS);

		Publication publication = await(alice.publish(video, TrackSource.CAMERA));

		assertTrue(publication.sid().startsWith("TR_"), publication::toString);
		assertEquals(TrackSource.CAMERA, publication.source());

		RemoteTrack track = await(received);
		assertEquals("alice", await(publisherOfTrack).identity());
		assertEquals(MediaKind.VIDEO, track.kind());
		assertEquals(publication.sid(), track.id());

		CompletableFuture<int[]> frameSize = new CompletableFuture<>();
		track.asVideo().onFrame(frame -> frameSize.complete(
				new int[] { frame.buffer.getWidth(), frame.buffer.getHeight() }));
		assertArrayEquals(new int[] { 320, 240 }, await(frameSize));

		bob.close();
		alice.close();
		video.close();
	}

	@Test
	void dataReachesOtherParticipant() throws Exception {
		Room alice = join("alice");
		Room bob = join("bob");

		CompletableFuture<DataMessage> received = new CompletableFuture<>();
		bob.onData(received::complete);

		byte[] payload = "slide 7".getBytes(StandardCharsets.UTF_8);
		await(alice.publishData(payload, "slides", true));

		DataMessage message = await(received);
		assertEquals("alice", message.senderIdentity());
		assertEquals("slides", message.topic().orElseThrow());
		assertTrue(message.isReliable());
		assertTrue(Arrays.equals(payload, message.payload()));

		bob.close();
		alice.close();
	}

	@Test
	void badTokenFailsJoin() {
		CompletableFuture<Room> room = LiveKit.room(rtc, url(), "not-a-token").connect();

		try {
			room.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			fail("Joined with a bad token");
		}
		catch (ExecutionException e) {
			assertTrue(e.getCause() instanceof LiveKitException, e::toString);
			assertTrue(e.getCause().getMessage().contains("HTTP 401"), e.getCause()::getMessage);
		}
		catch (Exception e) {
			fail(e);
		}

		assertFalse(rtc.peers().stream().anyMatch(peer -> peer.name().startsWith("livekit")));
	}

	private Room join(String identity) throws Exception {
		return await(LiveKit.room(rtc, url(), TestTokens.token(identity, roomName)).connect());
	}

	private static URI url() {
		return URI.create(System.getProperty("livekit.url"));
	}

	private static <T> T await(CompletableFuture<T> future) throws Exception {
		return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	private static void push(CustomVideo video) {
		NativeI420Buffer buffer = NativeI420Buffer.allocate(320, 240);
		VideoFrame frame = new VideoFrame(buffer, System.nanoTime());

		try {
			video.push(frame);
		}
		finally {
			frame.release();
		}
	}

}
