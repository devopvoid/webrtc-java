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

package dev.onvoid.webrtc.client.transfer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import dev.onvoid.webrtc.client.LoopbackChannel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The transfer protocol over an in-memory channel, with no native code.
 */
class FileTransfersTests {

	private static final long TIMEOUT_SECONDS = 15;

	/** Small, so that a transfer takes many chunks and acknowledgements. */
	private static final TransferOptions SMALL = TransferOptions.builder()
			.chunkSize(16 * 1024)
			.window(64 * 1024)
			.build();

	@TempDir
	Path dir;

	private final List<FileTransfers> opened = new ArrayList<>();


	@AfterEach
	void closeTransfers() {
		opened.forEach(FileTransfers::close);
	}

	@Test
	void sendsFile() throws Exception {
		LoopbackChannel[] channels = LoopbackChannel.pair("files");
		FileTransfers sender = open(channels[0], SMALL);
		FileTransfers receiver = open(channels[1], SMALL);

		Path source = randomFile("source.bin", 1024 * 1024 + 123);
		Path target = dir.resolve("target.bin");
		CompletableFuture<IncomingTransfer> incoming = acceptInto(receiver, target);

		OutgoingTransfer outgoing = sender.send(source);
		List<Long> progress = new CopyOnWriteArrayList<>();
		outgoing.onProgress(p -> progress.add(p.bytes()));

		await(outgoing.whenDone());
		IncomingTransfer received = await(incoming);
		await(received.whenDone());

		assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(target));
		assertEquals(outgoing.id(), received.id());
		assertEquals("source.bin", received.name());
		assertEquals(Files.size(source), received.progress().bytes());
		assertEquals(Files.size(source), outgoing.progress().bytes());
		assertFalse(Files.exists(received.partialFile()));

		// Confirmed a quarter window at a time, never going back.
		assertTrue(progress.size() > 10, () -> "Progress: " + progress);
		for (int i = 1; i < progress.size(); i++) {
			assertTrue(progress.get(i) >= progress.get(i - 1), () -> "Progress: " + progress);
		}
	}

	@Test
	void sendsEmptyFile() throws Exception {
		LoopbackChannel[] channels = LoopbackChannel.pair("files");
		FileTransfers sender = open(channels[0]);
		FileTransfers receiver = open(channels[1]);

		Path target = dir.resolve("empty.out");
		CompletableFuture<IncomingTransfer> incoming = acceptInto(receiver, target);

		await(sender.send(Files.createFile(dir.resolve("empty"))).whenDone());
		await(await(incoming).whenDone());

		assertEquals(0, Files.size(target));
		assertEquals(1, await(incoming).progress().fraction());
	}

	@Test
	void bothDirectionsAtOnce() throws Exception {
		LoopbackChannel[] channels = LoopbackChannel.pair("files");
		FileTransfers first = open(channels[0]);
		FileTransfers second = open(channels[1]);

		Path one = randomFile("one", 300 * 1024);
		Path two = randomFile("two", 200 * 1024);
		CompletableFuture<IncomingTransfer> toSecond = acceptInto(second, dir.resolve("one.out"));
		CompletableFuture<IncomingTransfer> toFirst = acceptInto(first, dir.resolve("two.out"));

		CompletableFuture<Void> sentOne = first.send(one).whenDone();
		CompletableFuture<Void> sentTwo = second.send(two).whenDone();

		await(sentOne);
		await(sentTwo);
		await(await(toSecond).whenDone());
		await(await(toFirst).whenDone());

		assertArrayEquals(Files.readAllBytes(one), Files.readAllBytes(dir.resolve("one.out")));
		assertArrayEquals(Files.readAllBytes(two), Files.readAllBytes(dir.resolve("two.out")));
	}

	@Test
	void filesGoInOrder() throws Exception {
		LoopbackChannel[] channels = LoopbackChannel.pair("files");
		FileTransfers sender = open(channels[0]);
		FileTransfers receiver = open(channels[1]);

		OutgoingTransfer[] sent = new OutgoingTransfer[2];
		List<String> offered = new CopyOnWriteArrayList<>();
		List<Boolean> previousDone = new CopyOnWriteArrayList<>();

		receiver.onOffer(offer -> {
			offered.add(offer.name());
			previousDone.add(offered.size() == 1 || sent[0].whenDone().isDone());
			offer.accept(dir.resolve(offer.safeFileName() + ".out"));
		});

		sent[0] = sender.send(randomFile("a", 100 * 1024));
		sent[1] = sender.send(randomFile("b", 100 * 1024));

		await(sent[1].whenDone());

		assertEquals(List.of("a", "b"), offered);
		assertEquals(List.of(true, true), previousDone);
	}

	@Test
	void offerWaitsForHandler() throws Exception {
		LoopbackChannel[] channels = LoopbackChannel.pair("files");
		FileTransfers sender = open(channels[0]);
		FileTransfers receiver = open(channels[1]);

		CompletableFuture<Void> offered = waitForOffered(channels[0]);
		OutgoingTransfer outgoing = sender.send(randomFile("late", 1000));
		await(offered);

		// The handler comes after the offer.
		acceptInto(receiver, dir.resolve("late.out"));

		await(outgoing.whenDone());
	}

	@Test
	void rejectFailsSender() throws Exception {
		LoopbackChannel[] channels = LoopbackChannel.pair("files");
		FileTransfers sender = open(channels[0]);
		FileTransfers receiver = open(channels[1]);

		receiver.onOffer(offer -> offer.reject("too large"));

		OutgoingTransfer rejected = sender.send(randomFile("big", 1000));
		TransferException error = failure(rejected.whenDone());

		assertTrue(error.getMessage().contains("too large"), error::getMessage);
		assertFalse(error.isResumable());
		assertThrows(IllegalStateException.class, () -> sender.resume(rejected));
	}

	@Test
	void senderCancels() throws Exception {
		LoopbackChannel[] channels = LoopbackChannel.pair("files");
		FileTransfers sender = open(channels[0], SMALL);
		FileTransfers receiver = open(channels[1], SMALL);

		// Without acknowledgements, the sender stops after a window.
		channels[1].intercept(frame -> frame[0] == Frames.ACK ? null : frame);

		Path target = dir.resolve("cancelled.out");
		CompletableFuture<IncomingTransfer> incoming = acceptInto(receiver, target);
		OutgoingTransfer outgoing = sender.send(randomFile("cancelled", 1024 * 1024));
		IncomingTransfer received = await(incoming);
		await(reached(received, 64 * 1024));

		outgoing.cancel();

		assertInstanceOf(TransferCancelledException.class, failure(outgoing.whenDone()));
		TransferException error = failure(received.whenDone());
		assertInstanceOf(TransferCancelledException.class, error);
		assertTrue(error.getMessage().contains("remote peer cancelled"), error::getMessage);
		assertFalse(Files.exists(received.partialFile()));
		assertFalse(Files.exists(target));
		assertThrows(IllegalStateException.class, () -> sender.resume(outgoing));
	}

	@Test
	void receiverCancels() throws Exception {
		LoopbackChannel[] channels = LoopbackChannel.pair("files");
		FileTransfers sender = open(channels[0], SMALL);
		FileTransfers receiver = open(channels[1], SMALL);

		channels[1].intercept(frame -> frame[0] == Frames.ACK ? null : frame);

		CompletableFuture<IncomingTransfer> incoming = acceptInto(receiver, dir.resolve("cancelled.out"));
		OutgoingTransfer outgoing = sender.send(randomFile("cancelled", 1024 * 1024));
		IncomingTransfer received = await(incoming);
		await(reached(received, 64 * 1024));

		channels[1].intercept(frame -> frame);
		received.cancel();

		assertInstanceOf(TransferCancelledException.class, failure(received.whenDone()));
		assertInstanceOf(TransferCancelledException.class, failure(outgoing.whenDone()));
		assertFalse(Files.exists(received.partialFile()));

		// The channel goes on.
		CompletableFuture<IncomingTransfer> next = acceptInto(receiver, dir.resolve("next.out"));
		await(sender.send(randomFile("next", 1000)).whenDone());
		await(await(next).whenDone());
	}

	@Test
	void corruptionDetected() throws Exception {
		LoopbackChannel[] channels = LoopbackChannel.pair("files");
		FileTransfers sender = open(channels[0], SMALL);
		FileTransfers receiver = open(channels[1], SMALL);

		AtomicLong frames = new AtomicLong();
		channels[0].intercept(frame -> {
			if (frame[0] == Frames.DATA && frames.incrementAndGet() == 3) {
				frame[Frames.DATA_HEADER + 100] ^= 1;
			}
			return frame;
		});

		Path target = dir.resolve("corrupt.out");
		CompletableFuture<IncomingTransfer> incoming = acceptInto(receiver, target);
		OutgoingTransfer outgoing = sender.send(randomFile("corrupt", 200 * 1024));

		TransferException error = failure(outgoing.whenDone());
		assertTrue(error.getMessage().contains("SHA-256"), error::getMessage);
		assertFalse(error instanceof TransferCancelledException);

		IncomingTransfer received = await(incoming);
		assertTrue(failure(received.whenDone()).getMessage().contains("SHA-256"));
		assertFalse(Files.exists(target));
		assertFalse(Files.exists(received.partialFile()));
	}

	@Test
	void resumesOverNewChannel() throws Exception {
		LoopbackChannel[] first = LoopbackChannel.pair("files");
		FileTransfers sender = open(first[0], SMALL);
		FileTransfers receiver = open(first[1], SMALL);

		// The connection breaks after 300 KiB of data.
		AtomicLong data = new AtomicLong();
		first[0].intercept(frame -> {
			if (frame[0] == Frames.DATA && data.addAndGet(frame.length - Frames.DATA_HEADER) > 300 * 1024) {
				first[0].close();
				return null;
			}
			return frame;
		});

		Path source = randomFile("resumed", 1024 * 1024);
		Path target = dir.resolve("resumed.out");
		CompletableFuture<IncomingTransfer> incoming = acceptInto(receiver, target);
		OutgoingTransfer interrupted = sender.send(source);
		IncomingTransfer partial = await(incoming);

		assertTrue(failure(interrupted.whenDone()).isResumable());
		assertTrue(failure(partial.whenDone()).isResumable());
		await(sender.whenClosed());

		long kept = Files.size(partial.partialFile());
		assertTrue(kept > 200 * 1024 && kept <= 300 * 1024, () -> "Kept " + kept);

		LoopbackChannel[] second = LoopbackChannel.pair("files");
		FileTransfers newSender = open(second[0], SMALL);
		FileTransfers newReceiver = open(second[1], SMALL);

		CompletableFuture<IncomingTransfer> resumedIncoming = acceptInto(newReceiver, target);
		OutgoingTransfer resumed = newSender.resume(interrupted);
		List<Long> progress = new CopyOnWriteArrayList<>();
		resumed.onProgress(p -> progress.add(p.bytes()));

		await(resumed.whenDone());
		await(await(resumedIncoming).whenDone());

		assertEquals(interrupted.id(), resumed.id());
		assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(target));
		assertFalse(Files.exists(partial.partialFile()));
		// Only what was missing went over the new channel.
		assertTrue(second[0].sentBytes.get() < Files.size(source) - kept + 64 * 1024,
				() -> "Sent " + second[0].sentBytes.get());
		// Progress goes on from where the first channel stopped.
		assertTrue(progress.contains(kept), () -> "Progress: " + progress);
		assertTrue(progress.stream().allMatch(bytes -> bytes == 0 || bytes >= kept), () -> "Progress: " + progress);
	}

	@Test
	void notResumableStartsOver() throws Exception {
		TransferOptions options = TransferOptions.builder().resumable(false).build();
		LoopbackChannel[] channels = LoopbackChannel.pair("files");
		FileTransfers sender = open(channels[0], options);
		FileTransfers receiver = open(channels[1], options);

		Path source = randomFile("fresh", 50 * 1024);
		Path target = dir.resolve("fresh.out");
		CompletableFuture<IncomingTransfer> incoming = new CompletableFuture<>();
		receiver.onOffer(offer -> {
			// A partial file of the same transfer, from before, is not continued.
			// Written before the accept, which opens it.
			try {
				Files.write(dir.resolve("fresh.out." + offer.id().substring(0, 16) + ".part"), new byte[10 * 1024]);
				incoming.complete(offer.accept(target));
			}
			catch (IOException e) {
				incoming.completeExceptionally(e);
			}
		});
		OutgoingTransfer outgoing = sender.send(source);

		await(outgoing.whenDone());
		await(await(incoming).whenDone());
		assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(target));
	}

	@Test
	void closeFailsResumable() throws Exception {
		LoopbackChannel[] channels = LoopbackChannel.pair("files");
		FileTransfers sender = open(channels[0]);
		open(channels[1]);

		CompletableFuture<Void> sentOffer = waitForOffered(channels[0]);
		OutgoingTransfer offered = sender.send(randomFile("offered", 1000));
		await(sentOffer);

		sender.close();

		assertTrue(failure(offered.whenDone()).isResumable());
		await(sender.whenClosed());
		assertTrue(failure(sender.send(randomFile("after", 10)).whenDone()).isResumable());
	}

	@Test
	void windowEnforced() throws Exception {
		LoopbackChannel[] channels = LoopbackChannel.pair("files");
		BlockingQueue<byte[]> frames = rawPeer(channels[0]);
		FileTransfers receiver = open(channels[1], TransferOptions.builder().window(16 * 1024).build());

		CompletableFuture<IncomingTransfer> incoming = acceptInto(receiver, dir.resolve("flood.out"));
		String id = "00112233445566778899aabbccddeeff";
		channels[0].send(Frames.offer(id, 1024 * 1024, "flood"));

		Frames.Frame accept = Frames.decode(poll(frames));
		assertEquals(Frames.ACCEPT, accept.type);
		assertEquals(16 * 1024, accept.window);

		// More than the window, with no acknowledgement awaited.
		channels[0].send(Frames.data(id, 0, 20 * 1024));

		Frames.Frame cancel = Frames.decode(poll(frames));
		assertEquals(Frames.CANCEL, cancel.type);
		assertTrue(cancel.failed);
		assertTrue(cancel.text.contains("window"), cancel.text);

		TransferException error = failure(await(incoming).whenDone());
		assertFalse(error.isResumable());
		assertTrue(failure(receiver.whenClosed()).getMessage().contains("broke the file transfer protocol"));
		assertFalse(Files.exists(await(incoming).partialFile()));
	}

	@Test
	void announcedSizeEnforced() throws Exception {
		LoopbackChannel[] channels = LoopbackChannel.pair("files");
		BlockingQueue<byte[]> frames = rawPeer(channels[0]);
		FileTransfers receiver = open(channels[1]);

		CompletableFuture<IncomingTransfer> incoming = acceptInto(receiver, dir.resolve("small.out"));
		String id = "ffeeddccbbaa99887766554433221100";
		channels[0].send(Frames.offer(id, 100, "small"));

		assertEquals(Frames.ACCEPT, Frames.decode(poll(frames)).type);

		channels[0].send(Frames.data(id, 0, 101));

		Frames.Frame cancel = Frames.decode(poll(frames));
		assertEquals(Frames.CANCEL, cancel.type);
		assertTrue(cancel.text.contains("100 bytes offered"), cancel.text);
		assertFalse(failure(await(incoming).whenDone()).isResumable());
	}

	@Test
	void textMessageClosesChannel() throws Exception {
		LoopbackChannel[] channels = LoopbackChannel.pair("files");
		FileTransfers receiver = open(channels[1]);

		channels[0].send("hello");

		assertTrue(failure(receiver.whenClosed()).getMessage().contains("text message"));
	}

	@Test
	void sendRefusesDirectory() {
		LoopbackChannel[] channels = LoopbackChannel.pair("files");
		FileTransfers sender = open(channels[0]);

		assertThrows(IOException.class, () -> sender.send(dir));
		assertThrows(IOException.class, () -> sender.send(dir.resolve("missing")));
		assertThrows(IllegalArgumentException.class, () -> sender.send(dir, "x".repeat(1025)));
	}

	@Test
	void framesRoundTrip() throws Exception {
		String id = "0123456789abcdef0123456789abcdef";

		Frames.Frame offer = Frames.decode(Frames.offer(id, 1L << 40, "Bericht ü.pdf"));
		assertEquals(Frames.OFFER, offer.type);
		assertEquals(id, offer.id);
		assertEquals(1L << 40, offer.value);
		assertEquals("Bericht ü.pdf", offer.text);

		Frames.Frame cancel = Frames.decode(Frames.cancel(id, true, "ä".repeat(600)));
		assertTrue(cancel.failed);
		assertEquals(512, cancel.text.length());

		byte[] data = Frames.data(id, 7, 3);
		data[Frames.DATA_HEADER] = 42;
		Frames.Frame chunk = Frames.decode(data);
		assertEquals(7, chunk.value);
		assertEquals(3, chunk.payloadLength);
		assertEquals(42, chunk.payload[chunk.payloadOffset]);

		byte[] ack = Frames.ack(id, 5);
		assertThrows(java.net.ProtocolException.class, () -> Frames.decode(java.util.Arrays.copyOf(ack, ack.length - 1)));
		assertThrows(java.net.ProtocolException.class, () -> Frames.decode(java.util.Arrays.copyOf(ack, ack.length + 1)));
		assertThrows(java.net.ProtocolException.class, () -> Frames.decode(new byte[] { 99 }));
	}

	@Test
	void safeFileNames() {
		assertEquals("passwd", TransferOffer.safeFileName("../../etc/passwd"));
		assertEquals("evil.exe", TransferOffer.safeFileName("C:\\Windows\\evil.exe"));
		assertEquals("_bashrc", TransferOffer.safeFileName(".bashrc"));
		assertEquals("_", TransferOffer.safeFileName(".."));
		assertEquals("file", TransferOffer.safeFileName(""));
		assertEquals("file", TransferOffer.safeFileName("dir/"));
		assertEquals("a_b_c", TransferOffer.safeFileName("a:b\u0000c"));
		assertEquals("report_fdp.exe", TransferOffer.safeFileName("report\u202Efdp.exe"));
		assertEquals("_CON.txt", TransferOffer.safeFileName("CON.txt"));
		assertEquals("_nul", TransferOffer.safeFileName("nul"));
		assertEquals("name", TransferOffer.safeFileName("name. . "));
		assertEquals("Bericht ü.pdf", TransferOffer.safeFileName("Bericht ü.pdf"));
		assertEquals(200, TransferOffer.safeFileName("x".repeat(300)).length());
	}

	private FileTransfers open(LoopbackChannel channel) {
		return open(channel, TransferOptions.defaults());
	}

	private FileTransfers open(LoopbackChannel channel, TransferOptions options) {
		FileTransfers transfers = FileTransfers.over(channel, options);
		opened.add(transfers);
		return transfers;
	}

	private Path randomFile(String name, int size) throws IOException {
		byte[] data = new byte[size];
		new Random(size).nextBytes(data);
		return Files.write(dir.resolve(name), data);
	}

	/** Accepts the next offer, and only that one. */
	private static CompletableFuture<IncomingTransfer> acceptInto(FileTransfers receiver, Path target) {
		CompletableFuture<IncomingTransfer> accepted = new CompletableFuture<>();
		AtomicBoolean taken = new AtomicBoolean();
		receiver.onOffer(offer -> {
			if (taken.compareAndSet(false, true)) {
				accepted.complete(offer.accept(target));
			}
		});
		return accepted;
	}

	/** Completes once the end sent an offer. */
	private static CompletableFuture<Void> waitForOffered(LoopbackChannel end) {
		CompletableFuture<Void> offered = new CompletableFuture<>();
		end.intercept(frame -> {
			if (frame[0] == Frames.OFFER) {
				offered.complete(null);
			}
			return frame;
		});
		return offered;
	}

	private static CompletableFuture<Void> reached(Transfer transfer, long bytes) {
		CompletableFuture<Void> reached = new CompletableFuture<>();
		transfer.onProgress(p -> {
			if (p.bytes() >= bytes) {
				reached.complete(null);
			}
		});
		return reached;
	}

	/** Collects what reaches an end that speaks the protocol by hand. */
	private static BlockingQueue<byte[]> rawPeer(LoopbackChannel end) {
		BlockingQueue<byte[]> frames = new LinkedBlockingQueue<>();
		end.onMessage(message -> frames.add(message.bytes()));
		return frames;
	}

	private static byte[] poll(BlockingQueue<byte[]> frames) throws InterruptedException {
		byte[] frame = frames.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		assertTrue(frame != null, "No frame arrived");
		return frame;
	}

	private static <T> T await(CompletableFuture<T> future) throws Exception {
		return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	private static TransferException failure(CompletableFuture<?> future) throws Exception {
		ExecutionException error = assertThrows(ExecutionException.class,
				() -> future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
		return assertInstanceOf(TransferException.class, error.getCause());
	}
}
