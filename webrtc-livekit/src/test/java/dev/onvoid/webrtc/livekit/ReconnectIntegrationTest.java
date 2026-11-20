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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import dev.onvoid.webrtc.client.AudioMode;
import dev.onvoid.webrtc.client.CustomVideo;
import dev.onvoid.webrtc.client.RemoteTrack;
import dev.onvoid.webrtc.client.Subscription;
import dev.onvoid.webrtc.client.WebRtc;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitRtc.SimulateScenario;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;

/**
 * Loses and recovers the connection to a LiveKit server started with --dev,
 * whose URL the system property livekit.url names. Skipped without it.
 */
@EnabledIfSystemProperty(named = "livekit.url", matches = ".+")
class ReconnectIntegrationTest {

	private static final long TIMEOUT_SECONDS = 30;

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
	void resumesAfterSignalLoss() throws Exception {
		Room alice = join("alice");
		Room bob = join("bob");

		CompletableFuture<RemoteTrack> received = new CompletableFuture<>();
		bob.onTrackSubscribed((participant, track) -> received.complete(track));
		CompletableFuture<Participant> left = new CompletableFuture<>();
		bob.onParticipantLeft(left::complete);

		CustomVideo video = publishVideo(alice);
		RemoteTrack track = await(received);

		CompletableFuture<String> reconnecting = new CompletableFuture<>();
		CompletableFuture<Void> reconnected = new CompletableFuture<>();
		alice.onReconnecting(reconnecting::complete);
		alice.onReconnected(() -> reconnected.complete(null));
		String sid = alice.localParticipant().sid();

		CompletableFuture<DataMessage> data = new CompletableFuture<>();
		bob.onData(data::complete);

		((RoomImpl) alice).cutSignal();
		// Sent while the room may be reconnecting: it waits, or the data
		// channel, which a resume keeps, carries it.
		CompletableFuture<Void> sent = alice.publishData(bytes("during"), "test", true);

		await(reconnecting);
		await(reconnected);
		await(sent);

		// The same session: the same participant, its peers and channels.
		assertEquals(RoomState.CONNECTED, alice.state());
		assertEquals(sid, alice.localParticipant().sid());
		assertEquals("during", text(await(data)));

		// Media and data still flow, both ways.
		assertFrame(track);
		CompletableFuture<DataMessage> back = new CompletableFuture<>();
		alice.onData(back::complete);
		await(bob.publishData(bytes("back"), "test", true));
		assertEquals("back", text(await(back)));

		assertFalse(left.isDone(), "Bob saw Alice leave");

		bob.close();
		alice.close();
		video.close();
	}

	@Test
	void rejoinsAfterNodeFailure() throws Exception {
		Room alice = join("alice");
		Room bob = join("bob");

		List<RemoteTrack> tracks = new CopyOnWriteArrayList<>();
		CompletableFuture<RemoteTrack> first = new CompletableFuture<>();
		CompletableFuture<RemoteTrack> second = new CompletableFuture<>();
		bob.onTrackSubscribed((participant, track) -> {
			tracks.add(track);
			(tracks.size() == 1 ? first : second).complete(track);
		});
		CompletableFuture<Participant> left = new CompletableFuture<>();
		bob.onParticipantLeft(left::complete);
		CompletableFuture<Participant> rejoined = new CompletableFuture<>();
		bob.onParticipantJoined(rejoined::complete);

		CustomVideo video = rtc.media().customVideo("camera-" + UUID.randomUUID());
		frames.scheduleAtFixedRate(() -> push(video), 0, 66, TimeUnit.MILLISECONDS);
		Publication publication = await(alice.publish(video, TrackSource.CAMERA));
		String oldTrackSid = publication.sid();
		assertEquals(oldTrackSid, await(first).id());

		CompletableFuture<Void> reconnected = new CompletableFuture<>();
		alice.onReconnected(() -> reconnected.complete(null));
		String oldSid = alice.localParticipant().sid();

		// The server forgets the session, as when its node fails; LiveKit's Go
		// SDK tests full reconnects this way.
		((RoomImpl) alice).simulate(SimulateScenario.newBuilder().setNodeFailure(true).build());

		await(reconnected);

		// A new session: a new participant sid, and the track published again.
		assertEquals(RoomState.CONNECTED, alice.state());
		assertNotEquals(oldSid, alice.localParticipant().sid());
		assertNotEquals(oldTrackSid, publication.sid());

		assertEquals(oldSid, await(left).sid());
		assertEquals(alice.localParticipant().sid(), await(rejoined).sid());
		RemoteTrack again = await(second);
		assertEquals(publication.sid(), again.id());
		assertFrame(again);

		CompletableFuture<DataMessage> data = new CompletableFuture<>();
		bob.onData(data::complete);
		await(alice.publishData(bytes("after"), "test", true));
		assertEquals("after", text(await(data)));

		bob.close();
		alice.close();
		video.close();
	}

	private CustomVideo publishVideo(Room room) throws Exception {
		CustomVideo video = rtc.media().customVideo("camera-" + UUID.randomUUID());
		frames.scheduleAtFixedRate(() -> push(video), 0, 66, TimeUnit.MILLISECONDS);
		await(room.publish(video, TrackSource.CAMERA));
		return video;
	}

	private static void assertFrame(RemoteTrack track) throws Exception {
		CompletableFuture<Integer> frame = new CompletableFuture<>();
		Subscription subscription = track.asVideo().onFrame(f -> frame.complete(f.buffer.getWidth()));
		try {
			assertEquals(320, await(frame));
		}
		finally {
			subscription.close();
		}
	}

	private Room join(String identity) throws Exception {
		URI url = URI.create(System.getProperty("livekit.url"));
		return await(LiveKit.room(rtc, url, TestTokens.token(identity, roomName)).connect());
	}

	private static <T> T await(CompletableFuture<T> future) throws Exception {
		return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	private static String text(DataMessage message) {
		return new String(message.payload(), StandardCharsets.UTF_8);
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
