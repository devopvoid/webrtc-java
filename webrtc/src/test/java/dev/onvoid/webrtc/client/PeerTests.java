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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
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
		Peer[] peers = pair();
		CompletableFuture<String> atCaller = new CompletableFuture<>();
		CompletableFuture<String> atCallee = new CompletableFuture<>();

		// Both create a channel at once, so both send an offer at once.
		DataChannel fromCaller = peers[0].createChannel("from-caller");
		DataChannel fromCallee = peers[1].createChannel("from-callee");

		peers[0].onChannel(channel -> channel.onMessage(message -> atCaller.complete(message.text())));
		peers[1].onChannel(channel -> channel.onMessage(message -> atCallee.complete(message.text())));

		fromCaller.send("hello callee");
		fromCallee.send("hello caller");

		assertEquals("hello callee", await(atCallee));
		assertEquals("hello caller", await(atCaller));
		assertTrue(errors.isEmpty(), () -> "Errors: " + errors);
	}

	@Test
	void impoliteCollisionReported() throws Exception {
		SignalingPair link = Signaling.loopback();
		Peer first = rtc.createPeer(PeerOptions.builder().signaling(link.first()).build());
		Peer second = rtc.createPeer(PeerOptions.builder().signaling(link.second()).build());

		CompletableFuture<WebRtcException> error = new CompletableFuture<>();
		first.onError(error::complete);
		second.onError(error::complete);

		first.createChannel("a");
		second.createChannel("b");

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

		assertTrue(peers[0].whenClosed().isDone());
		assertTrue(peers[1].whenClosed().isDone());
		assertEquals(PeerState.CLOSED, peers[1].state());
		assertThrows(PeerClosedException.class, this::pair);
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
