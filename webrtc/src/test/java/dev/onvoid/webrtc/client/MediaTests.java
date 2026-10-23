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

package dev.onvoid.webrtc.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import dev.onvoid.webrtc.RTCStats;
import dev.onvoid.webrtc.RTCStatsType;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MediaTests extends ClientTestBase {

	private ScheduledExecutorService feeder;


	@BeforeEach
	void createFeeder() {
		feeder = Executors.newSingleThreadScheduledExecutor();
	}

	@AfterEach
	void stopFeeder() throws InterruptedException {
		feeder.shutdownNow();
		feeder.awaitTermination(5, TimeUnit.SECONDS);
	}

	@Test
	void pushedAudioArrives() throws Exception {
		Peer[] peers = pair();
		CustomAudio voice = rtc.media().customAudio("voice");
		CompletableFuture<AudioChunk> heard = new CompletableFuture<>();

		peers[1].onTrack(track -> track.asAudio().onAudio(heard::complete));
		peers[0].addTrack(voice);

		// One second in one go; the track sends it in 10 ms chunks.
		voice.push(tone(48000, 1, 1000), PcmFormat.of(48000, 1));
		assertTrue(voice.queuedMillis() > 500);

		AudioChunk chunk = await(heard);

		assertTrue(chunk.frames() > 0);
		assertEquals(chunk.frames() * chunk.channels() * 2, chunk.samples().length);
	}

	@Test
	void pushedVideoArrives() throws Exception {
		Peer[] peers = pair();
		CustomVideo video = rtc.media().customVideo("video");
		CompletableFuture<int[]> seen = new CompletableFuture<>();
		CompletableFuture<int[]> preview = new CompletableFuture<>();

		peers[1].onTrack(track -> track.asVideo().onFrame(frame ->
				seen.complete(new int[] { frame.buffer.getWidth(), frame.buffer.getHeight() })));
		video.onFrame(frame -> preview.complete(new int[] { frame.buffer.getWidth(), frame.buffer.getHeight() }));
		peers[0].addTrack(video);

		feedVideo(video, 320, 240);

		assertEquals(320, await(seen)[0]);
		assertEquals(240, await(preview)[1]);
	}

	@Test
	void slowFrameHandlerGetsNewest() throws Exception {
		Peer[] peers = pair();
		CustomVideo video = rtc.media().customVideo("video");
		AtomicInteger handled = new AtomicInteger();
		CompletableFuture<Void> enough = new CompletableFuture<>();

		peers[1].onTrack(track -> track.asVideo().onFrame(frame -> {
			try {
				// Much slower than the 30 frames per second sent.
				Thread.sleep(200);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			if (handled.incrementAndGet() == 3) {
				enough.complete(null);
			}
		}));
		peers[0].addTrack(video);

		feedVideo(video, 160, 120);

		await(enough);

		// The decoder keeps going while the handler is busy: within the same
		// time it gets well ahead of the handler, at whatever rate the encoder
		// has ramped up to on this machine.
		long decodedBefore = framesDecoded(await(peers[1].stats()));
		int handledBefore = handled.get();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
		long decodedMeanwhile;
		int handledMeanwhile;

		do {
			Thread.sleep(100);
			decodedMeanwhile = framesDecoded(await(peers[1].stats())) - decodedBefore;
			handledMeanwhile = handled.get() - handledBefore;
		}
		while ((decodedMeanwhile < 10 || decodedMeanwhile < handledMeanwhile + 5) && System.nanoTime() < deadline);

		long decoded = decodedMeanwhile;
		int handledFrames = handledMeanwhile;

		assertTrue(decoded >= 10 && decoded >= handledFrames + 5,
				() -> "Meanwhile decoded " + decoded + " frames and handled " + handledFrames);
	}

	private static long framesDecoded(PeerStats stats) {
		long frames = 0;

		for (RTCStats entry : stats.report().getStats().values()) {
			Object decoded = entry.getAttributes().get("framesDecoded");

			if (entry.getType() == RTCStatsType.INBOUND_RTP && decoded instanceof Number) {
				frames += ((Number) decoded).longValue();
			}
		}

		return frames;
	}

	@Test
	void removedSenderEndsRemoteTrack() throws Exception {
		Peer[] peers = pair();
		CustomVideo video = rtc.media().customVideo("video");
		CompletableFuture<RemoteTrack> remote = new CompletableFuture<>();
		CompletableFuture<Void> ended = new CompletableFuture<>();

		peers[1].onTrack(track -> {
			remote.complete(track);
			track.onEnded(() -> ended.complete(null));
		});

		Sender sender = peers[0].addTrack(video);
		RemoteTrack track = await(remote);

		assertEquals(MediaKind.VIDEO, track.kind());
		assertEquals(1, track.streamIds().size());

		await(sender.remove());
		await(ended);

		assertTrue(track.isEnded());
		assertTrue(peers[0].senders().isEmpty());
		assertTrue(peers[1].remoteTracks().isEmpty());
		// The track was only removed, not closed.
		assertTrue(!video.isEnded());
	}

	@Test
	void closedTrackLeavesEveryPeer() throws Exception {
		Peer[] first = pair();
		Peer[] second = pair();
		CustomAudio voice = rtc.media().customAudio("voice");
		CompletableFuture<Void> ended = new CompletableFuture<>();

		Sender one = first[0].addTrack(voice);
		Sender two = second[0].addTrack(voice);
		voice.onEnded(() -> ended.complete(null));
		await(first[0].whenConnected());
		await(second[0].whenConnected());

		voice.close();
		await(ended);

		assertNull(one.track());
		assertNull(two.track());
		assertTrue(voice.isEnded());

		// The senders stay and may send another track.
		CustomAudio other = rtc.media().customAudio("other");
		await(one.replaceTrack(other));
		assertEquals(other, one.track());
	}

	@Test
	void sameTrackRegisteredOnce() throws Exception {
		Peer[] peers = pair();
		LocalTrackImpl voice = (LocalTrackImpl) rtc.media().customAudio("voice");
		Sender sender = peers[0].addTrack(voice);

		for (int i = 0; i < 3; i++) {
			await(sender.replaceTrack(voice));
		}

		assertEquals(voice, sender.track());
		assertEquals(Collections.singletonList(sender), voice.registeredSenders());

		await(sender.remove());

		// A removed sender, and its peer, are not kept by the track.
		assertTrue(voice.registeredSenders().isEmpty());
	}

	@Test
	void senderConfigured() throws Exception {
		Peer[] peers = pair();
		Sender sender = peers[0].addTrack(rtc.media().customVideo("video"));
		await(peers[0].whenConnected());

		await(sender.configure(Encoding.builder().maxBitrate(300_000).maxFrameRate(15).build()));

		assertThrows(IllegalArgumentException.class, () -> sender.replaceTrack(rtc.media().customAudio("audio")));
	}

	@Test
	void deliveredFramesReleased() throws Exception {
		Path status = Paths.get("/proc/self/status");
		assumeTrue(Files.isReadable(status), "Needs /proc to read the resident memory");

		CustomVideo video = rtc.media().customVideo("video");
		int count = 300;
		CountDownLatch delivered = new CountDownLatch(count);

		// Queues every frame, so that each of them reaches the handler.
		Subscription subscription = video.onFrame(frame -> delivered.countDown());
		long before = residentBytes(status);

		NativeI420Buffer buffer = NativeI420Buffer.allocate(1280, 720);
		for (int i = 0; i < count; i++) {
			VideoFrame frame = new VideoFrame(buffer, System.nanoTime());
			video.push(frame);
			Thread.sleep(2);
		}
		buffer.release();

		delivered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		subscription.close();

		// Each delivered frame is a copy of about 1.4 MB; keeping them all would hold over 400 MB.
		long grown = residentBytes(status) - before;
		assertTrue(grown < 150L * 1024 * 1024, () -> "Resident memory grew by " + grown / (1024 * 1024) + " MB");
	}

	private static long residentBytes(Path status) throws Exception {
		for (String line : Files.readAllLines(status)) {
			if (line.startsWith("VmRSS:")) {
				return Long.parseLong(line.replaceAll("[^0-9]", "")) * 1024;
			}
		}
		throw new IllegalStateException("No VmRSS in " + status);
	}

	@Test
	void tonesSent() throws Exception {
		Peer[] peers = pair();
		CustomAudio voice = rtc.media().customAudio("voice");
		Sender audioSender = peers[0].addTrack(voice);
		Sender videoSender = peers[0].addTrack(rtc.media().customVideo("video"));
		List<String> tones = Collections.synchronizedList(new ArrayList<>());

		audioSender.onTone(tones::add);
		await(peers[0].whenConnected());

		// Replaced before it sounds: its future is cancelled.
		CompletableFuture<Void> replaced = audioSender.sendTones("999999", Duration.ofMillis(500), Duration.ofMillis(100));
		await(audioSender.sendTones("1#", Duration.ofMillis(100), Duration.ofMillis(70)));

		assertTrue(replaced.isCancelled() || replaced.isCompletedExceptionally());
		// The last event is the empty tone, which the handler gets before the future completes.
		assertEquals("", tones.get(tones.size() - 1));
		assertTrue(String.join("", tones).endsWith("1#"), () -> "Tones: " + tones);

		ExecutionException refused = assertThrows(ExecutionException.class,
				() -> videoSender.sendTones("1").get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
		assertInstanceOf(IllegalStateException.class, refused.getCause());
	}

	@Test
	void tracksOverOwnSources() throws Exception {
		Peer[] peers = pair();
		CustomVideoSource videoSource = new CustomVideoSource();
		CustomAudioSource audioSource = new CustomAudioSource();

		try {
			LocalVideo video = rtc.media().videoTrack("own-video", videoSource);
			LocalAudio audio = rtc.media().audioTrack("own-audio", audioSource);
			CompletableFuture<Integer> width = new CompletableFuture<>();
			CompletableFuture<Void> ended = new CompletableFuture<>();

			peers[1].onTrack(track -> {
				if (track.isVideo()) {
					track.asVideo().onFrame(frame -> width.complete(frame.buffer.getWidth()));
				}
			});
			peers[0].addTrack(video);
			peers[0].addTrack(audio);

			feeder.scheduleAtFixedRate(() -> {
				VideoFrame frame = new VideoFrame(NativeI420Buffer.allocate(160, 120), System.nanoTime());
				videoSource.pushFrame(frame);
				frame.release();
			}, 0, 33, TimeUnit.MILLISECONDS);

			assertEquals(160, (int) await(width));

			video.onEnded(() -> ended.complete(null));
			video.close();
			await(ended);

			// The source is still the test's, and still takes frames.
			VideoFrame frame = new VideoFrame(NativeI420Buffer.allocate(160, 120), System.nanoTime());
			videoSource.pushFrame(frame);
			frame.release();

			// Once released, the source may go before the runtime does.
			await(audio.closeAsync());
			assertTrue(audio.isEnded());
		}
		finally {
			feeder.shutdownNow();
			feeder.awaitTermination(5, TimeUnit.SECONDS);

			// Sources outlive the tracks over them.
			rtc.close();
			videoSource.dispose();
			audioSource.dispose();
		}
	}

	@Test
	void audioModeChecked() {
		assertThrows(ConfigurationException.class, () -> rtc.media().microphone());
	}

	private void feedVideo(CustomVideo video, int width, int height) {
		AtomicInteger shade = new AtomicInteger();

		feeder.scheduleAtFixedRate(() -> {
			NativeI420Buffer buffer = NativeI420Buffer.allocate(width, height);
			ByteBuffer y = buffer.getDataY();
			int offset = shade.addAndGet(3);

			for (int i = 0; i < y.capacity(); i++) {
				y.put(i, (byte) (i + offset));
			}

			VideoFrame frame = new VideoFrame(buffer, System.nanoTime());
			video.push(frame);
			frame.release();
		}, 0, 33, TimeUnit.MILLISECONDS);
	}

	private static byte[] tone(int sampleRate, int channels, int millis) {
		int frames = sampleRate * millis / 1000;
		byte[] pcm = new byte[frames * channels * 2];

		for (int i = 0; i < frames; i++) {
			short sample = (short) (Math.sin(2 * Math.PI * 440 * i / sampleRate) * 8000);

			for (int c = 0; c < channels; c++) {
				int index = (i * channels + c) * 2;
				pcm[index] = (byte) sample;
				pcm[index + 1] = (byte) (sample >> 8);
			}
		}

		return pcm;
	}

}
