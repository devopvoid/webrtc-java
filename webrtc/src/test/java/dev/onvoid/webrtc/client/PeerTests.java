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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import dev.onvoid.webrtc.RTCSessionDescription;

import org.junit.jupiter.api.Test;

class PeerTests extends ClientTestBase {

	@Test
	void channelRoundTrip() throws Exception {
		Peer[] peers = pair();
		CompletableFuture<String> reply = new CompletableFuture<>();

		peers[1].onChannel(channel -> channel.onMessage(message -> channel.send("pong:" + message.text())));

		DataChannel channel = peers[0].createChannel("chat");
		channel.onMessage(message -> reply.complete(message.text()));
		channel.send("ping");

		assertEquals("pong:ping", await(reply));
		assertEquals(PeerState.CONNECTED, await(peers[0].whenConnected()).state());
		assertEquals(PeerState.CONNECTED, await(peers[1].whenConnected()).state());
		assertTrue(errors.isEmpty(), () -> "Errors: " + errors);
	}

	@Test
	void stateHandlerGetsCurrentState() throws Exception {
		Peer[] peers = pair();
		peers[0].createChannel("chat");
		await(peers[0].whenConnected());

		// Registered after the fact, the handler still hears the state.
		CompletableFuture<PeerState> state = new CompletableFuture<>();
		peers[0].onStateChange(state::complete);

		assertEquals(PeerState.CONNECTED, await(state));
	}

	@Test
	void earlyMessagesKept() throws Exception {
		Peer[] peers = pair();
		CompletableFuture<DataChannel> remote = new CompletableFuture<>();
		peers[1].onChannel(remote::complete);

		DataChannel channel = peers[0].createChannel("chat");
		await(channel.send("one"));
		await(channel.send("two"));

		// No message handler while the messages arrive.
		DataChannel received = await(remote);
		Thread.sleep(300);

		List<String> messages = Collections.synchronizedList(new ArrayList<>());
		CountDownLatch both = new CountDownLatch(2);
		received.onMessage(message -> {
			messages.add(message.text());
			both.countDown();
		});

		assertTrue(both.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
		assertEquals(Arrays.asList("one", "two"), messages);
	}

	@Test
	void earlyMessagesBounded() throws Exception {
		// The sender holds more than the receiver keeps.
		Peer[] peers = pair(options -> options.channelSendLimit(32 * 1024 * 1024));
		CompletableFuture<DataChannel> remote = new CompletableFuture<>();
		CompletableFuture<WebRtcException> dropped = new CompletableFuture<>();

		peers[1].onChannel(remote::complete);
		peers[1].onError(dropped::complete);

		// About 20 MiB while the remote channel has no message handler.
		DataChannel channel = peers[0].createChannel("bulk");
		int count = 100;
		byte[] payload = new byte[200 * 1024];

		for (int i = 0; i < count; i++) {
			payload[0] = (byte) i;
			channel.send(payload);
		}
		await(channel.send(new byte[] { (byte) 0xFF }));

		assertTrue(await(dropped).getMessage().contains("dropped received messages"));

		List<Integer> received = Collections.synchronizedList(new ArrayList<>());
		CompletableFuture<Void> last = new CompletableFuture<>();
		await(remote).onMessage(message -> {
			byte[] bytes = message.bytes();
			if (bytes.length == 1) {
				last.complete(null);
			}
			else {
				received.add(bytes[0] & 0xFF);
			}
		});
		await(last);

		// The newest messages were kept, in order.
		assertTrue(received.size() < count, () -> "Kept " + received.size() + " messages");
		assertEquals(count - 1, (int) received.get(received.size() - 1));
	}

	@Test
	void eventsInOrderOneAtATime() throws Exception {
		Peer[] peers = pair();
		int count = 2000;
		List<Integer> received = new ArrayList<>();
		AtomicInteger running = new AtomicInteger();
		AtomicInteger overlaps = new AtomicInteger();
		CountDownLatch all = new CountDownLatch(count);

		peers[1].onChannel(channel -> channel.onMessage(message -> {
			if (running.incrementAndGet() > 1) {
				overlaps.incrementAndGet();
			}
			received.add(Integer.parseInt(message.text()));
			running.decrementAndGet();
			all.countDown();
		}));

		DataChannel channel = peers[0].createChannel("numbers");

		for (int i = 0; i < count; i++) {
			channel.send(Integer.toString(i));
		}

		assertTrue(all.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
		assertEquals(0, overlaps.get());

		for (int i = 0; i < count; i++) {
			assertEquals(i, received.get(i));
		}
	}

	@Test
	void handlerMayClosePeer() throws Exception {
		Peer[] peers = pair();

		peers[0].onStateChange(state -> {
			if (state == PeerState.CONNECTED) {
				peers[0].close();
			}
		});
		peers[0].createChannel("chat");

		await(peers[0].whenClosed());

		assertEquals(PeerState.CLOSED, peers[0].state());
		assertFalse(rtc.peers().contains(peers[0]));
	}

	@Test
	void joinOnEventThreadRefused() throws Exception {
		Peer[] peers = pair();
		CompletableFuture<Throwable> refused = new CompletableFuture<>();

		peers[0].onStateChange(state -> {
			try {
				peers[0].whenClosed().join();
				refused.complete(null);
			}
			catch (Throwable e) {
				refused.complete(e);
			}
		});

		assertInstanceOf(BlockingCallException.class, await(refused));

		// On Java 9 and later, so do the futures derived from them.
		CompletableFuture<Throwable> derivedRefused = new CompletableFuture<>();
		peers[1].onStateChange(state -> {
			try {
				peers[1].whenClosed().thenApply(ignored -> state).join();
				derivedRefused.complete(null);
			}
			catch (Throwable e) {
				derivedRefused.complete(e);
			}
		});

		assertInstanceOf(BlockingCallException.class, await(derivedRefused));

		// A future that is done may be read anywhere.
		CompletableFuture<Object> doneRead = new CompletableFuture<>();
		peers[0].createChannel("chat");
		peers[0].whenConnected().thenAccept(peer -> doneRead.complete(peer.whenConnected().join()));

		assertEquals(peers[0], await(doneRead));
	}

	@Test
	void blockedHandlerDelaysOnlyItsPeer() throws Exception {
		Peer[] slow = pair();
		Peer[] fast = pair();
		CountDownLatch release = new CountDownLatch(1);

		slow[0].onStateChange(state -> {
			try {
				// Holds the event thread of this peer only.
				release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		slow[0].createChannel("chat");

		CompletableFuture<String> reply = new CompletableFuture<>();
		fast[1].onChannel(channel -> channel.onMessage(message -> reply.complete(message.text())));
		fast[0].createChannel("chat").send("through");

		try {
			assertEquals("through", await(reply));

			// The blocked peer still connects underneath; only its events wait.
			assertFalse(slow[0].whenConnected().isDone());
		}
		finally {
			release.countDown();
		}

		await(slow[0].whenConnected());
	}

	@Test
	void politePeerResolvesCollision() throws Exception {
		HeldSignaling link = new HeldSignaling();
		Peer caller = rtc.createPeer(PeerOptions.builder().signaling(link.first()).build());
		Peer callee = rtc.createPeer(PeerOptions.builder().signaling(link.second()).polite(true).build());
		CompletableFuture<String> atCaller = new CompletableFuture<>();
		CompletableFuture<String> atCallee = new CompletableFuture<>();

		caller.onChannel(channel -> channel.onMessage(message -> atCaller.complete(message.text())));
		callee.onChannel(channel -> channel.onMessage(message -> atCallee.complete(message.text())));

		// Both send an offer before either sees the other's.
		caller.createChannel("from-caller").send("hello callee");
		callee.createChannel("from-callee").send("hello caller");
		link.releaseWhenBothOffered(TIMEOUT_SECONDS);

		assertEquals("hello callee", await(atCallee));
		assertEquals("hello caller", await(atCaller));
		assertTrue(errors.isEmpty(), () -> "Errors: " + errors);
	}

	@Test
	void impoliteCollisionReported() throws Exception {
		HeldSignaling link = new HeldSignaling();
		Peer first = rtc.createPeer(PeerOptions.builder().signaling(link.first()).build());
		Peer second = rtc.createPeer(PeerOptions.builder().signaling(link.second()).build());

		CompletableFuture<WebRtcException> error = new CompletableFuture<>();
		first.onError(error::complete);
		second.onError(error::complete);

		// Both send an offer before either sees the other's, and neither gives way.
		first.createChannel("a");
		second.createChannel("b");
		link.releaseWhenBothOffered(TIMEOUT_SECONDS);

		WebRtcException reported = error.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

		assertInstanceOf(NegotiationException.class, reported);
		assertTrue(reported.getMessage().contains("polite(true)"), reported.getMessage());
	}

	@Test
	void manualNegotiation() throws Exception {
		Peer offerer = rtc.createPeer(PeerOptions.builder().negotiation(Negotiation.MANUAL).build());
		Peer answerer = rtc.createPeer(PeerOptions.builder().negotiation(Negotiation.MANUAL).build());

		CompletableFuture<String> received = new CompletableFuture<>();
		answerer.onChannel(channel -> channel.onMessage(message -> received.complete(message.text())));
		offerer.createChannel("manual").send("no trickle");

		// Without a signaling, the descriptions carry all candidates.
		RTCSessionDescription offer = await(offerer.negotiator().createOffer(Gathering.COMPLETE));
		RTCSessionDescription answer = await(answerer.negotiator().answer(offer, Gathering.COMPLETE));
		await(offerer.negotiator().applyAnswer(answer));

		assertTrue(offer.sdp.contains("a=candidate"), "The offer has no candidates");
		assertEquals("no trickle", await(received));
		assertThrows(IllegalStateException.class, () -> pair()[0].negotiator());
	}

	@Test
	void unreachableStunServerBoundsGathering() throws Exception {
		// 192.0.2.1 is reserved for documentation and never answers, which
		// holds up the end of gathering far beyond the timeout.
		Peer peer = rtc.createPeer(PeerOptions.builder()
				.negotiation(Negotiation.MANUAL)
				.iceServer("stun:192.0.2.1:3478")
				.gatheringTimeout(Duration.ofMillis(500))
				.receive(MediaKind.VIDEO)
				.build());

		long start = System.nanoTime();
		RTCSessionDescription offer = await(peer.negotiator().createOffer(Gathering.COMPLETE));
		long millis = (System.nanoTime() - start) / 1_000_000;

		assertTrue(millis < 5000, () -> "Gathering took " + millis + " ms");
		assertTrue(offer.sdp.contains("typ host"), "The offer has no host candidates");
	}

	@Test
	void closeFailsPendingWork() throws Exception {
		Peer peer = rtc.createPeer(PeerOptions.builder().negotiation(Negotiation.MANUAL).build());
		DataChannel channel = peer.createChannel("never-opened");

		// Held, since the channel never opens.
		CompletableFuture<Void> sent = channel.send("lost");
		CompletableFuture<Peer> connected = peer.whenConnected();

		peer.close();
		await(peer.whenClosed());

		assertInstanceOf(PeerClosedException.class, cause(sent));
		assertInstanceOf(PeerClosedException.class, cause(connected));
		assertInstanceOf(PeerClosedException.class, cause(peer.negotiator().createOffer()));
		assertInstanceOf(PeerClosedException.class, cause(channel.send("after")));
		assertEquals(ChannelState.CLOSED, channel.state());
	}

	@Test
	void runtimeCloseClosesPeers() throws Exception {
		Peer[] peers = pair();
		peers[0].createChannel("chat");
		await(peers[0].whenConnected());

		rtc.close();

		// Closed once close() returns; the futures and the CLOSED events follow
		// on the event threads.
		assertEquals(PeerState.CLOSED, peers[0].state());
		assertEquals(PeerState.CLOSED, peers[1].state());
		await(peers[0].whenClosed());
		await(peers[1].whenClosed());
		assertThrows(PeerClosedException.class, this::pair);
	}

	@Test
	void openAfterShutdownFails() {
		// A createPeer that passed its check just before the runtime closed:
		// the runtime's threads are gone and drop the task that would create
		// the connection, so opening has to fail rather than wait forever.
		PeerImpl peer = new PeerImpl(rtc, manualOptions(), "late");

		rtc.close();

		assertThrows(PeerClosedException.class, () -> assertTimeoutPreemptively(
				Duration.ofSeconds(TIMEOUT_SECONDS), peer::open));
	}

	@Test
	void openAfterCloseFails() throws Exception {
		// The closing runtime found the peer before its connection was created.
		// It may free the factory once the peer is closed, so the connection
		// must not be created after that.
		PeerImpl peer = new PeerImpl(rtc, manualOptions(), "closed-first");

		await(peer.closeInternal());

		assertThrows(PeerClosedException.class, peer::open);
	}

	@Test
	void createPeerRacingClose() throws Exception {
		// A peer created while its runtime closes is either refused or closed
		// with the runtime, and creating it never waits forever.
		for (int round = 0; round < 50; round++) {
			WebRtc runtime = WebRtc.builder()
					.audio(AudioMode.HEADLESS)
					.errorHandler(errors::add)
					.build();
			CountDownLatch started = new CountDownLatch(1);

			CompletableFuture<List<Peer>> created = CompletableFuture.supplyAsync(() -> {
				List<Peer> peers = new ArrayList<>();

				try {
					while (true) {
						peers.add(runtime.createPeer(manualOptions()));
						started.countDown();
					}
				}
				catch (PeerClosedException e) {
					return peers;
				}
			});

			// Closed while peers are being created, not before the first.
			started.await();
			runtime.closeAsync().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

			for (Peer peer : created.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				assertEquals(PeerState.CLOSED, peer.state());
			}
		}
	}

	@Test
	void sendBeyondLimitFails() throws Exception {
		// Never negotiated, so the channel stays connecting and holds every send.
		Peer peer = rtc.createPeer(PeerOptions.builder()
				.negotiation(Negotiation.MANUAL)
				.channelSendLimit(1000)
				.build());
		DataChannel channel = peer.createChannel("bounded");

		CompletableFuture<Void> first = channel.send(new byte[600]);
		ExecutionException full = assertThrows(ExecutionException.class, () -> await(channel.send(new byte[600])));
		CompletableFuture<Void> fitting = channel.send(new byte[400]);

		assertInstanceOf(ChannelFullException.class, full.getCause());
		assertEquals(1000, channel.bufferedAmount());
		assertFalse(first.isDone());
		assertFalse(fitting.isDone());

		// A message larger than the limit never fits.
		peer.close();
		Peer other = rtc.createPeer(PeerOptions.builder()
				.negotiation(Negotiation.MANUAL)
				.channelSendLimit(10)
				.build());
		ExecutionException tooLarge = assertThrows(ExecutionException.class,
				() -> await(other.createChannel("small").send(new byte[11])));

		assertInstanceOf(ChannelFullException.class, tooLarge.getCause());
	}

	private static PeerOptions manualOptions() {
		return PeerOptions.builder().negotiation(Negotiation.MANUAL).build();
	}

	@Test
	void statsAfterConnecting() throws Exception {
		Peer[] peers = pair();
		DataChannel channel = peers[0].createChannel("chat");
		await(channel.whenOpen());
		await(channel.send(new byte[10_000]));

		PeerStats first = await(peers[0].stats());
		PeerStats second = await(peers[0].stats());

		assertEquals("host", first.candidateType());
		assertTrue(first.bytesSent() > 10_000, () -> "Bytes sent: " + first.bytesSent());
		assertEquals(-1, first.outgoingBitrate());
		assertTrue(second.outgoingBitrate() >= 0);
	}

	@Test
	void subscriptionStopsHandler() throws Exception {
		Peer[] peers = pair();
		List<String> messages = Collections.synchronizedList(new ArrayList<>());
		CompletableFuture<DataChannel> remote = new CompletableFuture<>();
		peers[1].onChannel(remote::complete);

		DataChannel channel = peers[0].createChannel("chat");
		DataChannel received = await(remote);

		CompletableFuture<Void> first = new CompletableFuture<>();
		Subscription subscription = received.onMessage(message -> {
			messages.add(message.text());
			first.complete(null);
		});
		CompletableFuture<Void> last = new CompletableFuture<>();
		received.onMessage(message -> {
			if (message.text().equals("two")) {
				last.complete(null);
			}
		});

		channel.send("one");
		await(first);
		subscription.close();
		channel.send("two");
		await(last);

		assertEquals(Arrays.asList("one"), messages);
	}

	@Test
	void failedAttachClosesPeer() {
		SignalingPair link = Signaling.loopback();
		rtc.createPeer(PeerOptions.builder().signaling(link.first()).build());

		// One end of a loopback cannot serve two peers.
		assertThrows(IllegalStateException.class,
				() -> rtc.createPeer(PeerOptions.builder().signaling(link.first()).build()));
		assertEquals(1, rtc.peers().size());
	}

	@Test
	void operationAfterRuntimeCloseFails() throws Exception {
		// The runtime's threads are gone, so the task that would fail the
		// operation never runs. Its future has to fail all the same.
		Peer peer = rtc.createPeer(manualOptions());

		rtc.close();

		assertInstanceOf(PeerClosedException.class, cause(peer.negotiator().createOffer()));
		assertInstanceOf(PeerClosedException.class, cause(peer.withNative(connection -> connection)));
	}

	@Test
	void rejectedEventsReported() throws Exception {
		// An event executor that refuses work, as a bounded pool does when full.
		AtomicBoolean refusing = new AtomicBoolean();
		ExecutorService pool = Executors.newSingleThreadExecutor();
		Executor executor = task -> {
			if (refusing.get()) {
				throw new RejectedExecutionException("Full");
			}
			pool.execute(task);
		};
		WebRtc runtime = WebRtc.builder()
				.audio(AudioMode.HEADLESS)
				.eventExecutor(executor)
				.errorHandler(errors::add)
				.build();

		try {
			Peer peer = runtime.createPeer(manualOptions());
			refusing.set(true);

			// Delivered all the same, though not on the event executor.
			assertTrue(await(peer.negotiator().createOffer()).sdp.startsWith("v=0"));
			assertTrue(errors.stream().anyMatch(error -> error.getCause() instanceof RejectedExecutionException),
					() -> "Errors: " + errors);

			refusing.set(false);
		}
		finally {
			runtime.close();
			pool.shutdown();
		}

		errors.clear();
	}

	private static Throwable cause(CompletableFuture<?> future) throws Exception {
		try {
			future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			return null;
		}
		catch (ExecutionException e) {
			return e.getCause();
		}
	}

}
