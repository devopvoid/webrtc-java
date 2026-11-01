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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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
	void lateResultFreed() throws Exception {
		// A result that comes after the caller cancelled has no one else to
		// free it. One the caller takes is left alone.
		PeerImpl peer = (PeerImpl) rtc.createPeer(PeerOptions.builder().negotiation(Negotiation.MANUAL).build());
		CompletableFuture<String> late = new CompletableFuture<>();
		CompletableFuture<String> freed = new CompletableFuture<>();
		CompletableFuture<String> cancelled = peer.toPublic(late, freed::complete);

		assertTrue(cancelled.cancel(true));
		late.complete("late");
		assertEquals("late", await(freed));

		CompletableFuture<String> taken = new CompletableFuture<>();
		CompletableFuture<String> kept = new CompletableFuture<>();
		CompletableFuture<String> handed = peer.toPublic(taken, kept::complete);

		taken.complete("taken");
		assertEquals("taken", await(handed));
		assertFalse(kept.isDone());
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
		// The sender holds more than the receiver keeps while it has no
		// message handler, so the receiving channel closes.
		Peer[] peers = pair(options -> options.channelSendLimit(32 * 1024 * 1024));
		CompletableFuture<WebRtcException> error = new CompletableFuture<>();
		peers[1].onError(error::complete);

		DataChannel channel = peers[0].createChannel("bulk");
		CompletableFuture<Void> closed = whenClosed(channel);
		byte[] payload = new byte[200 * 1024];

		// About 20 MiB, beyond the default limit of 16 MiB.
		for (int i = 0; i < 100; i++) {
			channel.send(payload);
		}

		assertTrue(await(error).getMessage().contains("fell more than"), () -> error.join().getMessage());
		await(closed);
	}

	@Test
	void slowHandlerClosesChannel() throws Exception {
		// The handler blocks on the first message while more arrive than the
		// channel may hold.
		int limit = 64 * 1024;
		Peer[] peers = pair(options -> options.channelReceiveLimit(limit));
		CountDownLatch release = new CountDownLatch(1);
		List<Integer> handled = new CopyOnWriteArrayList<>();
		CompletableFuture<WebRtcException> error = new CompletableFuture<>();

		peers[1].onError(error::complete);
		peers[1].onChannel(remote -> remote.onMessage(message -> {
			handled.add(message.bytes()[0] & 0xFF);
			awaitQuietly(release);
		}));

		DataChannel channel = peers[0].createChannel("bulk");
		CompletableFuture<Void> closed = whenClosed(channel);
		byte[] payload = new byte[16 * 1024];

		try {
			for (int i = 0; i < 10; i++) {
				payload[0] = (byte) i;
				channel.send(payload);
			}

			// The receiver closed the channel while its handler still blocked.
			await(closed);
		}
		finally {
			release.countDown();
		}

		assertTrue(await(error).getMessage().contains("fell more than"), () -> error.join().getMessage());

		// The one in the handler and three queued fit the limit; the fifth
		// went over it, and nothing after it was delivered. The error came
		// after those queued before it.
		assertEquals(Arrays.asList(0, 1, 2, 3), handled);
	}

	@Test
	void handlerAddedOnChannelGetsQueuedMessages() throws Exception {
		// The channel handler returns only after the receiver closed the
		// channel, so the messages and the close wait behind it. The message
		// handler it adds still gets those that fit the limit.
		int limit = 64 * 1024;
		Peer[] peers = pair(options -> options.channelReceiveLimit(limit));
		CountDownLatch overflowed = new CountDownLatch(1);
		List<Integer> handled = new CopyOnWriteArrayList<>();
		CompletableFuture<WebRtcException> error = new CompletableFuture<>();

		peers[1].onError(error::complete);
		peers[1].onChannel(remote -> {
			awaitQuietly(overflowed);
			remote.onMessage(message -> handled.add(message.bytes()[0] & 0xFF));
		});

		DataChannel channel = peers[0].createChannel("bulk");
		CompletableFuture<Void> closed = whenClosed(channel);
		closed.thenRun(overflowed::countDown);
		byte[] payload = new byte[16 * 1024];

		try {
			for (int i = 0; i < 10; i++) {
				payload[0] = (byte) i;
				channel.send(payload);
			}

			await(closed);
		}
		finally {
			overflowed.countDown();
		}

		assertTrue(await(error).getMessage().contains("fell more than"), () -> error.join().getMessage());
		assertEquals(Arrays.asList(0, 1, 2, 3), handled);
	}

	@Test
	void handledMessagesFreeLimit() throws Exception {
		// Far more than the limit passes through, a message at a time.
		int limit = 64 * 1024;
		Peer[] peers = pair(options -> options.channelReceiveLimit(limit));
		Semaphore handled = new Semaphore(0);

		peers[1].onChannel(remote -> remote.onMessage(message -> handled.release()));

		DataChannel channel = peers[0].createChannel("bulk");
		byte[] payload = new byte[16 * 1024];

		for (int i = 0; i < 64; i++) {
			await(channel.send(payload));
			assertTrue(handled.tryAcquire(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Message " + i + " was not handled");
		}

		// Nothing reached the error handler either; the base class checks.
		assertEquals(ChannelState.OPEN, channel.state());
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

		// So do the futures derived from them.
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
		DataChannel small = other.createChannel("small");
		ExecutionException tooLarge = assertThrows(ExecutionException.class,
				() -> await(small.send(new byte[11])));
		ExecutionException tooLong = assertThrows(ExecutionException.class,
				() -> await(small.send("x".repeat(11))));

		assertInstanceOf(ChannelFullException.class, tooLarge.getCause());
		assertInstanceOf(ChannelFullException.class, tooLong.getCause());
		assertEquals(0, small.bufferedAmount());
	}

	@Test
	void oversizedBufferNotCopied() throws Exception {
		// A sparse file mapped read-only, whose pages are never touched: a
		// copy of its 2 GiB could not even be allocated as a byte array.
		Path dir = Files.createTempDirectory("channel-send");
		Path file = dir.resolve("sparse.bin");

		try {
			try (FileChannel writer = FileChannel.open(file, StandardOpenOption.CREATE_NEW,
					StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
				writer.write(ByteBuffer.wrap(new byte[1]), Integer.MAX_VALUE - 1);
			}

			ByteBuffer huge;

			try (FileChannel reader = FileChannel.open(file, StandardOpenOption.READ)) {
				huge = reader.map(FileChannel.MapMode.READ_ONLY, 0, Integer.MAX_VALUE);
			}

			Peer peer = rtc.createPeer(PeerOptions.builder()
					.negotiation(Negotiation.MANUAL)
					.channelSendLimit(1000)
					.build());
			DataChannel channel = peer.createChannel("bounded");

			ExecutionException full = assertThrows(ExecutionException.class, () -> await(channel.send(huge)));

			assertInstanceOf(ChannelFullException.class, full.getCause());
			assertEquals(0, channel.bufferedAmount());

			// Nothing of the limit stayed taken.
			CompletableFuture<Void> fitting = channel.send(new byte[1000]);

			assertEquals(1000, channel.bufferedAmount());
			assertFalse(fitting.isDone());

			peer.close();
		}
		finally {
			// Windows keeps a mapped file until the mapping is collected.
			try {
				Files.deleteIfExists(file);
				Files.deleteIfExists(dir);
			}
			catch (IOException e) {
				file.toFile().deleteOnExit();
				dir.toFile().deleteOnExit();
			}
		}
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
		assertEquals(first.report().getTimestamp(), ChronoUnit.MICROS.between(Instant.EPOCH, first.timestamp()));
		assertEquals(-1, first.outgoingBitrate());
		// Asked for right after the first, the second may be the report WebRTC
		// still caches, which repeats the first one's bitrate.
		if (second.timestamp().equals(first.timestamp())) {
			assertEquals(-1, second.outgoingBitrate());
		}
		else {
			assertTrue(second.outgoingBitrate() >= 0);
		}
	}

	@Test
	void statsHandlerErrorReported() throws Exception {
		// A stats handler that throws reaches the runtime's error handler, and
		// the samples after it still come.
		ManualTimer timer = new ManualTimer();
		CompletableFuture<Throwable> reported = new CompletableFuture<>();
		Duration interval = Duration.ofSeconds(7);

		try (WebRtc runtime = WebRtc.builder()
				.audio(AudioMode.HEADLESS)
				.errorHandler(error -> {
					errors.add(error);
					reported.complete(error);
				})
				.timer(timer)
				.build()) {
			Peer peer = runtime.createPeer(manualOptions());
			AtomicInteger calls = new AtomicInteger();
			CompletableFuture<PeerStats> later = new CompletableFuture<>();

			peer.onStats(interval, stats -> {
				if (calls.incrementAndGet() == 1) {
					throw new IllegalStateException("Handler failed");
				}
				later.complete(stats);
			});

			assertEquals(1, timer.fire(interval));
			Throwable error = await(reported);

			assertInstanceOf(IllegalStateException.class, error);
			errors.remove(error);

			assertEquals(1, timer.fire(interval));
			await(later);
		}
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
	void closeDetachesSignaling() throws Exception {
		List<Consumer<SignalMessage>> attached = new CopyOnWriteArrayList<>();
		AtomicInteger detached = new AtomicInteger();
		Signaling transport = new Signaling() {

			@Override
			public void send(SignalMessage message) {
			}

			@Override
			public Subscription attach(Consumer<SignalMessage> inbound) {
				attached.add(inbound);
				return detached::incrementAndGet;
			}
		};

		Peer peer = rtc.createPeer(PeerOptions.builder().signaling(transport).build());

		assertEquals(1, attached.size());
		assertEquals(0, detached.get());

		// Once, by the time the close completes.
		await(peer.closeAsync());
		await(peer.closeAsync());
		assertEquals(1, detached.get());
	}

	@Test
	void closeWhileAttaching() throws Exception {
		// The peer has closed, and found nothing to let go of, before attach
		// returns. The subscription it then gets is closed all the same.
		AtomicInteger detached = new AtomicInteger();
		Signaling transport = new Signaling() {

			@Override
			public void send(SignalMessage message) {
			}

			@Override
			public Subscription attach(Consumer<SignalMessage> inbound) {
				rtc.peers().forEach(peer -> peer.closeAsync().join());
				return detached::incrementAndGet;
			}
		};

		Peer peer = rtc.createPeer(PeerOptions.builder().signaling(transport).build());

		assertEquals(PeerState.CLOSED, peer.state());
		assertEquals(1, detached.get());
	}

	@Test
	void signalingOutlivesPeer() throws Exception {
		// A closed peer frees its end of the loopback for the next one, which
		// connects through it.
		SignalingPair link = Signaling.loopback();
		await(rtc.createPeer(PeerOptions.builder().signaling(link.first()).build()).closeAsync());

		Peer caller = rtc.createPeer(PeerOptions.builder().signaling(link.first()).build());
		Peer callee = rtc.createPeer(PeerOptions.builder().signaling(link.second()).polite(true).build());
		CompletableFuture<String> reply = new CompletableFuture<>();

		callee.onChannel(channel -> channel.onMessage(message -> channel.send("pong:" + message.text())));

		DataChannel channel = caller.createChannel("chat");
		channel.onMessage(message -> reply.complete(message.text()));
		channel.send("ping");

		assertEquals("pong:ping", await(reply));
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

	private static CompletableFuture<Void> whenClosed(DataChannel channel) {
		CompletableFuture<Void> closed = new CompletableFuture<>();

		channel.onStateChange(state -> {
			if (state == ChannelState.CLOSED) {
				closed.complete(null);
			}
		});

		return closed;
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
