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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import dev.onvoid.webrtc.client.AudioMode;
import dev.onvoid.webrtc.client.CustomAudio;
import dev.onvoid.webrtc.client.CustomVideo;
import dev.onvoid.webrtc.client.MediaKind;
import dev.onvoid.webrtc.client.PcmFormat;
import dev.onvoid.webrtc.client.RemoteTrack;
import dev.onvoid.webrtc.client.Subscription;
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
	void subscriberReceivesPublishedAudio() throws Exception {
		Room alice = join("alice");
		Room bob = join("bob");

		CompletableFuture<RemoteTrack> received = new CompletableFuture<>();
		bob.onTrackSubscribed((participant, track) -> received.complete(track));

		// A second of silence, pushed every second.
		PcmFormat format = PcmFormat.of(48000, 1);
		CustomAudio audio = rtc.media().customAudio("microphone-" + UUID.randomUUID());
		byte[] second = new byte[format.sampleRate() * 2];
		frames.scheduleAtFixedRate(() -> audio.push(second, format), 0, 1, TimeUnit.SECONDS);

		Publication publication = await(alice.publish(audio, TrackSource.MICROPHONE));
		assertEquals(TrackSource.MICROPHONE, publication.source());

		RemoteTrack track = await(received);
		assertEquals(MediaKind.AUDIO, track.kind());
		assertEquals(publication.sid(), track.id());

		CompletableFuture<Integer> chunk = new CompletableFuture<>();
		track.asAudio().onAudio(c -> chunk.complete(c.frames()));
		assertTrue(await(chunk) > 0);

		bob.close();
		alice.close();
		audio.close();
	}

	@Test
	void lateHandlerGetsEarlierTracks() throws Exception {
		Room alice = join("alice");

		CustomVideo video = rtc.media().customVideo("camera-" + UUID.randomUUID());
		frames.scheduleAtFixedRate(() -> push(video), 0, 66, TimeUnit.MILLISECONDS);
		Publication publication = await(alice.publish(video, TrackSource.CAMERA));

		// Bob joins after the track was published, and receives it before
		// he adds a handler.
		Room bob = join("bob");
		CompletableFuture<RemoteTrack> first = new CompletableFuture<>();
		bob.onTrackSubscribed((participant, track) -> first.complete(track));
		assertEquals(publication.sid(), await(first).id());

		CompletableFuture<RemoteTrack> late = new CompletableFuture<>();
		bob.onTrackSubscribed((participant, track) -> late.complete(track));
		assertSame(await(first), await(late));

		bob.close();
		alice.close();
		video.close();
	}

	@Test
	void simulcastFollowsSubscribers() throws Exception {
		Room alice = join("alice");
		Room bob = join("bob");

		CompletableFuture<RemoteTrack> received = new CompletableFuture<>();
		bob.onTrackSubscribed((participant, track) -> received.complete(track));

		CustomVideo video = rtc.media().customVideo("camera-" + UUID.randomUUID());
		frames.scheduleAtFixedRate(() -> push(video, 1280, 720), 0, 66, TimeUnit.MILLISECONDS);

		Publication publication = await(alice.publish(video, PublishOptions.builder()
				.source(TrackSource.CAMERA)
				.videoSize(1280, 720)
				.simulcast(true)
				.build()));
		assertEquals(List.of("q", "h", "f"), publication.rids());

		RemoteTrack track = await(received);

		// Bob wants the smallest layer: the server forwards it, and, as
		// nobody else watches, has Alice pause the larger ones.
		CompletableFuture<Map<VideoQuality, Boolean>> paused = layersOnce(alice, layers -> !layers.get(VideoQuality.HIGH));
		CompletableFuture<Integer> small = frameWidth(track, width -> width == 320);
		bob.requestVideoQuality(track.id(), VideoQuality.LOW);

		assertEquals(320, await(small));
		Map<VideoQuality, Boolean> layers = await(paused);
		assertTrue(layers.get(VideoQuality.LOW), layers::toString);
		assertFalse(layers.get(VideoQuality.HIGH), layers::toString);

		// And back up: the larger layers resume, and Bob gets more than the
		// smallest once the bandwidth estimate allows it.
		CompletableFuture<Map<VideoQuality, Boolean>> resumed = layersOnce(alice, l -> l.get(VideoQuality.HIGH));
		CompletableFuture<Integer> large = frameWidth(track, width -> width >= 640);
		bob.requestVideoQuality(track.id(), VideoQuality.HIGH);

		assertTrue(await(resumed).get(VideoQuality.HIGH));
		assertTrue(await(large) >= 640);

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

	private static CompletableFuture<Map<VideoQuality, Boolean>> layersOnce(Room room,
			Predicate<Map<VideoQuality, Boolean>> condition) {
		CompletableFuture<Map<VideoQuality, Boolean>> result = new CompletableFuture<>();
		List<BiConsumer<Publication, Map<VideoQuality, Boolean>>> listeners = ((RoomImpl) room).layerListeners;
		BiConsumer<Publication, Map<VideoQuality, Boolean>> listener = (publication, layers) -> {
			if (layers.containsKey(VideoQuality.HIGH) && condition.test(layers)) {
				result.complete(layers);
			}
		};
		listeners.add(listener);
		result.whenComplete((r, e) -> listeners.remove(listener));
		return result;
	}

	private static CompletableFuture<Integer> frameWidth(RemoteTrack track, IntPredicate condition) {
		CompletableFuture<Integer> result = new CompletableFuture<>();
		Subscription subscription = track.asVideo().onFrame(frame -> {
			int width = frame.buffer.getWidth();
			if (condition.test(width)) {
				result.complete(width);
			}
		});
		result.whenComplete((r, e) -> subscription.close());
		return result;
	}

	private static void push(CustomVideo video) {
		push(video, 320, 240);
	}

	private static void push(CustomVideo video, int width, int height) {
		NativeI420Buffer buffer = NativeI420Buffer.allocate(width, height);
		VideoFrame frame = new VideoFrame(buffer, System.nanoTime());

		try {
			video.push(frame);
		}
		finally {
			frame.release();
		}
	}

}
