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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/**
 * Runs runtimes with limited pools. A handler that holds the only thread is
 * a latch the test opens, and the clock of the load statistics moves only
 * when the test moves it.
 */
class LoadTests extends ClientTestBase {

	private final AtomicLong now = new AtomicLong();


	@Test
	void limitsChecked() {
		WebRtc.Builder builder = WebRtc.builder();

		assertThrows(IllegalArgumentException.class, () -> builder.eventThreads(0));
		assertThrows(IllegalArgumentException.class, () -> builder.frameThreads(-1));
		assertThrows(IllegalArgumentException.class, () -> builder.audioBuffer(Duration.ZERO));

		// A limit of the runtime's own threads means nothing for the
		// application's executor.
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> builder.eventThreads(2).eventExecutor(Runnable::run).build());
		assertTrue(e.getMessage().contains("eventExecutor"), e::getMessage);
	}

	@Test
	void cancelledTimeoutsDequeued() {
		// As an operation that completes long before its timeout is due.
		for (ScheduledExecutorService scheduler : Arrays.asList(rtc.timer, rtc.audioPacer)) {
			ScheduledThreadPoolExecutor pool = assertInstanceOf(ScheduledThreadPoolExecutor.class, scheduler);
			int before = pool.getQueue().size();

			for (int i = 0; i < 100; i++) {
				pool.schedule(() -> { }, 1, TimeUnit.HOURS).cancel(false);
			}

			assertEquals(before, pool.getQueue().size());
		}
	}

	@Test
	void eventsWaitTheirTurn() throws Exception {
		replaceRuntime(WebRtc.builder().eventThreads(1));

		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch blocking = new CountDownLatch(1);
		CompletableFuture<String> second = new CompletableFuture<>();
		SerialExecutor first = events("first");
		SerialExecutor other = events("other");

		first.execute(() -> {
			blocking.countDown();
			await(release);
		});
		assertTrue(blocking.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

		other.execute(() -> second.complete(Thread.currentThread().getName()));
		now.addAndGet(TimeUnit.MILLISECONDS.toNanos(250));

		LoadStats load = rtc.loadStats();

		assertEquals(1, load.eventThreads());
		assertEquals(1, load.events().active());
		assertEquals(1, load.events().queued());
		assertEquals(Duration.ofMillis(250), load.events().oldestWaiting());
		assertFalse(second.isDone());

		release.countDown();

		// On the same, only thread, under the name of its own peer.
		assertEquals("other", await(second));
		assertEquals(0, rtc.loadStats().events().queued());
		assertEquals(1, rtc.loadStats().eventThreads());
	}

	@Test
	void closeFailsWaitingFutures() throws Exception {
		// The only event thread is held while the runtime closes. The peer's
		// future fails once the thread is free, after the events before it.
		replaceRuntime(WebRtc.builder().eventThreads(1));

		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch blocking = new CountDownLatch(1);
		Peer peer = rtc.createPeer(PeerOptions.builder().negotiation(Negotiation.MANUAL).build());

		events("blocker").execute(() -> {
			blocking.countDown();
			await(release);
		});
		assertTrue(blocking.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

		// Closing the peers needs no event thread; telling of it does.
		rtc.close();

		assertEquals(PeerState.CLOSED, peer.state());
		assertFalse(peer.whenConnected().isDone());

		release.countDown();

		ExecutionException e = assertThrows(ExecutionException.class, () -> await(peer.whenConnected()));
		assertInstanceOf(PeerClosedException.class, e.getCause());
		await(peer.whenClosed());
		await(rtc.closeAsync());
	}

	@Test
	void limitedPoolsCarryCall() throws Exception {
		replaceRuntime(WebRtc.builder().eventThreads(1).frameThreads(1).audioBuffer(Duration.ofMillis(200)));

		Peer[] peers = pair();
		CustomAudio voice = rtc.media().customAudio("voice");
		CompletableFuture<AudioChunk> heard = new CompletableFuture<>();
		CompletableFuture<AudioChunk> heardAgain = new CompletableFuture<>();

		peers[1].onTrack(track -> {
			track.asAudio().onAudio(heard::complete);
			track.asAudio().onAudio(Duration.ofMillis(20), heardAgain::complete);
		});
		peers[0].addTrack(voice);

		// One second of silence in one go.
		voice.push(new byte[48000 * 2], PcmFormat.of(48000, 1));

		assertTrue(await(heard).frames() > 0);
		assertTrue(await(heardAgain).frames() > 0);

		LoadStats load = rtc.loadStats();

		assertTrue(load.eventThreads() <= 1, load::toString);
		assertTrue(load.frameThreads() <= 1, load::toString);
	}

	/**
	 * Replaces the runtime of the base class with one built here, which the
	 * base class then closes and checks.
	 */
	private void replaceRuntime(WebRtc.Builder builder) {
		rtc.close();
		rtc = builder.audio(AudioMode.HEADLESS)
				.errorHandler(errors::add)
				.clock(now::get)
				.build();
	}

	/**
	 * @return an executor on the runtime's event pool, as a peer has.
	 */
	private SerialExecutor events(String name) {
		return new SerialExecutor(rtc.eventExecutor, rtc.eventWork, rtc::reportError, true, name);
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
