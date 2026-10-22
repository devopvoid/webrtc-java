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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Closes runtimes at each point where it can meet another operation. The
 * runtimes run their control tasks, or their events, on a {@link
 * ManualExecutor}, so that each test decides the order rather than the
 * scheduler.
 */
class RuntimeCloseTests extends ClientTestBase {

	@Test
	void closeBeforeRegister() throws Exception {
		// createPeer checked the runtime, then the runtime began to close and
		// found no peer to wait for.
		ManualExecutor control = new ManualExecutor();
		WebRtc runtime = runtime(control);
		PeerImpl peer = new PeerImpl(runtime, manualOptions(), "late");

		CompletableFuture<Void> closed = runtime.closeAsync();

		assertThrows(PeerClosedException.class, () -> runtime.register(peer));
		assertFalse(runtime.peers().contains(peer));

		// Nothing of the peer reached the control executor, so no connection
		// was created, and the runtime closes without it.
		assertEquals(0, control.queued());
		await(closed);
	}

	@Test
	void closeBeforeOpen() throws Exception {
		// The peer is registered, and the runtime closes before the connection
		// is created.
		ManualExecutor control = new ManualExecutor();
		WebRtc runtime = runtime(control);
		PeerImpl peer = runtime.register(new PeerImpl(runtime, manualOptions(), "registered"));

		CompletableFuture<Void> closed = runtime.closeAsync();

		// The runtime found the peer and queued its teardown, and waits for it
		// before it frees the factory.
		control.awaitQueued(TIMEOUT_SECONDS);
		assertFalse(closed.isDone());

		// Whether the creation joins the teardown's turn or takes one of its
		// own, the teardown runs first.
		CompletableFuture<PeerImpl> opened = CompletableFuture.supplyAsync(() -> runtime.open(peer));
		control.runUntil(opened::isDone, TIMEOUT_SECONDS);

		ExecutionException e = assertThrows(ExecutionException.class, () -> await(opened));
		assertInstanceOf(PeerClosedException.class, e.getCause());

		control.runUntil(closed::isDone, TIMEOUT_SECONDS);
		await(closed);
		assertEquals(PeerState.CLOSED, peer.state());
		assertTrue(runtime.peers().isEmpty());
	}

	@Test
	void closeWhileOpening() throws Exception {
		// The task that creates the connection is queued when the runtime
		// begins to close.
		ManualExecutor control = new ManualExecutor();
		WebRtc runtime = runtime(control);

		CompletableFuture<Peer> created = CompletableFuture.supplyAsync(() -> runtime.createPeer(manualOptions()));
		control.awaitQueued(TIMEOUT_SECONDS);

		CompletableFuture<Void> closed = runtime.closeAsync();
		assertFalse(closed.isDone());

		control.runUntil(closed::isDone, TIMEOUT_SECONDS);
		await(closed);

		// Created first, so createPeer returns it, then closed with the runtime.
		Peer peer = await(created);
		assertEquals(PeerState.CLOSED, peer.state());
		await(peer.whenClosed());
	}

	@Test
	void closeTimeoutReported() throws Exception {
		// The peer's teardown never runs, so neither close finishes.
		ManualExecutor control = new ManualExecutor();
		WebRtc runtime = WebRtc.builder()
				.audio(AudioMode.HEADLESS)
				.errorHandler(errors::add)
				.controlPool(control)
				.closeTimeout(Duration.ofMillis(100))
				.build();
		CompletableFuture<Peer> created = CompletableFuture.supplyAsync(() -> runtime.createPeer(manualOptions()));
		control.runUntil(created::isDone, TIMEOUT_SECONDS);
		await(created);

		try {
			runtime.close();

			// The runtime cannot tell whether WebRTC still uses the factory,
			// so it leaves it. That outlasts close(), whose wait ran out first.
			ExecutionException e = assertThrows(ExecutionException.class,
					() -> runtime.closeAsync().get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

			assertEquals(2, errors.size(), () -> "Errors: " + errors);
			assertTrue(errors.stream().anyMatch(error -> error.getMessage().contains("did not close within")),
					() -> "Errors: " + errors);
			assertTrue(errors.contains(e.getCause()), () -> "Errors: " + errors);
			assertTrue(e.getCause().getMessage().contains("left undisposed"), e.getCause()::getMessage);
		}
		finally {
			// Lets the teardown close the connection.
			control.runAll();
			errors.clear();
		}
	}

	@Test
	void closeOnEventThread() throws Exception {
		// close() does not wait for the events of the peers it closes, which
		// would wait for the very thread it runs on.
		Peer peer = rtc.createPeer(manualOptions());
		CompletableFuture<PeerState> closedState = new CompletableFuture<>();

		peer.onStateChange(state -> {
			if (state == PeerState.NEW) {
				rtc.close();
				closedState.complete(peer.state());
			}
		});

		assertEquals(PeerState.CLOSED, await(closedState));
		await(peer.whenClosed());
		// No "did not close within" either; the base class checks.
	}

	@Test
	void rejectedCloseEvents() throws Exception {
		// The event executor refuses the events that tell of the close. The
		// peer's futures settle all the same, on the thread that found them
		// refused.
		ManualExecutor events = new ManualExecutor();
		WebRtc runtime = WebRtc.builder()
				.audio(AudioMode.HEADLESS)
				.errorHandler(errors::add)
				.eventExecutor(events)
				.build();

		try {
			Peer peer = runtime.createPeer(manualOptions());
			CompletableFuture<Peer> connected = peer.whenConnected();

			events.runAll();
			events.rejecting(true);
			peer.close();

			await(peer.whenClosed());
			ExecutionException e = assertThrows(ExecutionException.class, () -> await(connected));
			assertInstanceOf(PeerClosedException.class, e.getCause());

			assertEquals(1, errors.size(), () -> "Errors: " + errors);
			assertInstanceOf(RejectedExecutionException.class, errors.get(0).getCause());
		}
		finally {
			events.rejecting(false);
			runtime.close();
			events.runAll();
			errors.clear();
		}
	}

	@Test
	void closeFailureReportedOnce() throws Exception {
		// What closing fails with reaches the error handler once, whether the
		// application waits through close() or closeAsync().
		ManualExecutor control = new ManualExecutor();
		WebRtc runtime = WebRtc.builder()
				.audio(AudioMode.HEADLESS)
				.errorHandler(errors::add)
				.controlPool(control)
				.closeTimeout(Duration.ofMillis(100))
				.build();
		CompletableFuture<Peer> created = CompletableFuture.supplyAsync(() -> runtime.createPeer(manualOptions()));
		control.runUntil(created::isDone, TIMEOUT_SECONDS);
		await(created);

		try {
			ExecutionException e = assertThrows(ExecutionException.class,
					() -> runtime.closeAsync().get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

			runtime.close();
			runtime.close();

			assertInstanceOf(WebRtcException.class, e.getCause());
			assertTrue(e.getCause().getMessage().contains("left undisposed"), e.getCause()::getMessage);
			assertEquals(1, errors.size(), () -> "Errors: " + errors);
			assertSame(e.getCause(), errors.get(0));
		}
		finally {
			control.runAll();
			errors.clear();
		}
	}

	private WebRtc runtime(ManualExecutor control) {
		return WebRtc.builder()
				.audio(AudioMode.HEADLESS)
				.errorHandler(errors::add)
				.controlPool(control)
				.build();
	}

	private static PeerOptions manualOptions() {
		return PeerOptions.builder().negotiation(Negotiation.MANUAL).build();
	}

}
