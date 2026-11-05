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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * How the future of tones ends, on a connected sender whose control executor
 * and timer the tests drive. A tone this long leaves WebRTC's own end event
 * out of the way; the tests send the events they need.
 */
class SenderTonesTests extends ClientTestBase {

	private static final Duration LONG_TONE = Duration.ofSeconds(6);

	private static final Duration GAP = Duration.ofMillis(30);

	/** When one long tone is overdue. */
	private static final Duration DEADLINE = LONG_TONE.plus(GAP).plus(SenderImpl.TONE_GRACE);

	private final ManualExecutor control = new ManualExecutor();

	private final ManualTimer timer = new ManualTimer();

	/** Written on the test's thread, which runs the handlers. */
	private final List<String> heard = new ArrayList<>();

	private WebRtc runtime;

	private SenderImpl sender;


	@BeforeEach
	void connect() throws Exception {
		runtime = WebRtc.builder()
				.audio(AudioMode.HEADLESS)
				.errorHandler(errors::add)
				.controlPool(control)
				.eventExecutor(Runnable::run)
				.timer(timer)
				.build();

		Peer caller = drive(CompletableFuture.supplyAsync(() -> {
			SignalingPair link = Signaling.loopback();
			Peer peer = runtime.createPeer(PeerOptions.builder().name("caller").signaling(link.first()).build());

			runtime.createPeer(PeerOptions.builder().name("callee").signaling(link.second()).polite(true).build());
			sender = (SenderImpl) peer.addTrack(runtime.media().customAudio("voice"));

			return peer;
		}));

		sender.onTone(heard::add);
		drive(caller.whenConnected());
	}

	@AfterEach
	void closeManualRuntime() throws Exception {
		drive(runtime.closeAsync());
	}

	@Test
	void lateEndKeepsNewTones() throws Exception {
		CompletableFuture<Void> first = sender.sendTones("1", LONG_TONE, GAP);
		control.runAll();

		CompletableFuture<Void> second = sender.sendTones("2", LONG_TONE, GAP);

		// WebRTC ends the first tones while the second wait on the control
		// executor, so that the event runs after them.
		sender.toneChanged("");
		control.runAll();

		assertTrue(first.isCompletedExceptionally());
		assertFalse(second.isDone());
		assertFalse(heard.contains(""), () -> "Heard: " + heard);
		assertEquals(1, timer.scheduled(DEADLINE));

		sender.toneChanged("");
		control.runAll();

		await(second);
		assertEquals("", heard.get(heard.size() - 1));
		assertEquals(0, timer.scheduled(DEADLINE));
	}

	@Test
	void stoppedTonesFail() throws Exception {
		CompletableFuture<Void> sent = sender.sendTones("123", Duration.ofMillis(40), GAP);

		// A paused stream refuses the next tone, and WebRTC gives up without
		// an event. A sender without a track would go on sending tones.
		drive(sender.configure(Encoding.builder().active(false).build()));

		assertEquals(1, timer.fire(Duration.ofMillis(3 * 70).plus(SenderImpl.TONE_GRACE)));
		control.runAll();

		ExecutionException failed = assertThrows(ExecutionException.class, () -> await(sent));

		assertInstanceOf(IllegalStateException.class, failed.getCause());
		assertFalse(heard.contains(""), () -> "Heard: " + heard);
	}

	@Test
	void playedTonesComplete() throws Exception {
		CompletableFuture<Void> sent = sender.sendTones("1", LONG_TONE, GAP);

		control.runUntil(() -> heard.contains("1"), TIMEOUT_SECONDS);

		// The last tone left WebRTC's queue, and only its end event is missing.
		assertEquals(1, timer.fire(DEADLINE));
		control.runAll();

		await(sent);
		assertEquals(Arrays.asList("1", ""), heard);
	}

	private <T> T drive(CompletableFuture<T> future) throws Exception {
		control.runUntil(future::isDone, TIMEOUT_SECONDS);

		return await(future);
	}

}
