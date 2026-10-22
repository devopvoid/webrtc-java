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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;

import dev.onvoid.webrtc.client.internal.EncodedFrames;
import dev.onvoid.webrtc.internal.NativeApi;

import org.junit.jupiter.api.Test;

/**
 * Hands out the encoded frames of senders and remote tracks, as the media
 * module's recorder takes them: the futures settle whatever the runtime
 * does, and each source's reference is handed over or dropped once.
 */
class EncodedFramesTests extends ClientTestBase {

	@Test
	void sourceHandedOverOnce() throws Exception {
		Peer peer = rtc.createPeer(manualOptions());
		Sender sender = peer.addTrack(rtc.media().customVideo("video"));

		EncodedFrames.Source taken = await(EncodedFrames.of(sender));
		long frames = taken.take();

		assertNotEquals(0, frames);
		assertThrows(IllegalStateException.class, taken::take);

		// The reference went with the handle, so this drops nothing.
		taken.close();
		NativeApi.releaseEncodedFrames(frames);

		EncodedFrames.Source closed = await(EncodedFrames.of(sender));
		closed.close();
		closed.close();

		assertThrows(IllegalStateException.class, closed::take);
	}

	@Test
	void senderAfterRuntimeClosed() throws Exception {
		Peer peer = rtc.createPeer(manualOptions());
		Sender sender = peer.addTrack(rtc.media().customVideo("video"));
		await(EncodedFrames.of(sender)).close();

		rtc.close();

		assertClosed(EncodedFrames.of(sender));
	}

	@Test
	void remoteTrackAfterRuntimeClosed() throws Exception {
		Peer[] peers = pair();
		CompletableFuture<RemoteTrack> received = new CompletableFuture<>();

		peers[1].onTrack(received::complete);
		peers[0].addTrack(rtc.media().customVideo("video"));

		RemoteTrack track = await(received);
		await(EncodedFrames.of(track)).close();

		rtc.close();

		assertClosed(EncodedFrames.of(track));
	}

	@Test
	void rejectedTaskFailsFuture() throws Exception {
		// The control pool is full, not shut down, so nothing but the task's
		// own fallback fails the future.
		ManualExecutor control = new ManualExecutor();
		WebRtc runtime = runtime(control);

		try {
			CompletableFuture<Peer> created = CompletableFuture.supplyAsync(() -> runtime.createPeer(manualOptions()));
			control.runUntil(created::isDone, TIMEOUT_SECONDS);
			Sender sender = await(created).addTrack(runtime.media().customVideo("video"));
			control.runAll();

			control.rejecting(true);
			CompletableFuture<EncodedFrames.Source> frames = EncodedFrames.of(sender);

			assertClosed(frames);
			assertEquals(1, errors.size(), () -> "Errors: " + errors);
			assertInstanceOf(RejectedExecutionException.class, errors.get(0).getCause());
		}
		finally {
			control.rejecting(false);
			errors.clear();
			close(runtime, control);
		}
	}

	@Test
	void cancelledBeforeTaken() throws Exception {
		// The recorder stopped waiting before the peer got to it, so no
		// source is taken for nobody.
		ManualExecutor control = new ManualExecutor();
		WebRtc runtime = runtime(control);

		try {
			CompletableFuture<Peer> created = CompletableFuture.supplyAsync(() -> runtime.createPeer(manualOptions()));
			control.runUntil(created::isDone, TIMEOUT_SECONDS);
			Sender sender = await(created).addTrack(runtime.media().customVideo("video"));
			control.runAll();

			CompletableFuture<EncodedFrames.Source> frames = EncodedFrames.of(sender);
			assertEquals(1, control.queued());

			frames.cancel(false);
			control.runAll();

			assertTrue(frames.isCancelled());
		}
		finally {
			close(runtime, control);
		}
	}

	private WebRtc runtime(ManualExecutor control) {
		return WebRtc.builder()
				.audio(AudioMode.HEADLESS)
				.errorHandler(errors::add)
				.controlPool(control)
				.build();
	}

	private static void close(WebRtc runtime, ManualExecutor control) throws Exception {
		CompletableFuture<Void> closed = runtime.closeAsync();
		control.runUntil(closed::isDone, TIMEOUT_SECONDS);
		await(closed);
	}

	private static void assertClosed(CompletableFuture<EncodedFrames.Source> frames) {
		// Settled at once, not after a caller's timeout.
		assertTrue(frames.isDone());

		ExecutionException e = assertThrows(ExecutionException.class, frames::get);
		assertInstanceOf(PeerClosedException.class, e.getCause());
	}

	private static PeerOptions manualOptions() {
		return PeerOptions.builder().negotiation(Negotiation.MANUAL).build();
	}

}
