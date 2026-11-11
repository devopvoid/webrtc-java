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

import static java.util.Objects.requireNonNull;

import java.io.EOFException;
import java.io.IOException;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import dev.onvoid.webrtc.client.ChannelState;
import dev.onvoid.webrtc.client.DataChannel;
import dev.onvoid.webrtc.client.Message;
import dev.onvoid.webrtc.client.PeerClosedException;
import dev.onvoid.webrtc.client.Subscription;

/**
 * Sends and receives files over a {@link DataChannel}, which it takes over:
 * both peers create one over their end of the channel, and nothing else may
 * be sent on it.
 * <pre>{@code
 * FileTransfers files = FileTransfers.over(peer.createChannel("files"));
 *
 * OutgoingTransfer upload = files.send(Paths.get("report.pdf"));
 * upload.onProgress(p -> System.out.printf("%.0f%%%n", 100 * p.fraction()));
 * upload.whenDone().thenRun(() -> System.out.println("The receiver has it"));
 *
 * // On the other side:
 * peer.onChannel(channel -> {
 *     if (channel.label().equals("files")) {
 *         FileTransfers.over(channel).onOffer(offer ->
 *                 offer.accept(downloads.resolve(offer.safeFileName())));
 *     }
 * });
 * }</pre>
 * <p>
 * A {@code send} future of the channel completes once the data is handed to
 * the transport, which says nothing about the receiver. The protocol confirms
 * on its own: the receiver acknowledges what it wrote to its file, and the
 * sender never has more than the receiver's {@linkplain
 * TransferOptions.Builder#window(int) window} unconfirmed, so a slow disk
 * slows the sender down instead of filling memory. A transfer is done once
 * the receiver found the SHA-256 of the whole file to match the sender's and
 * moved the file into place.
 * <p>
 * Files go one at a time in each direction, in the order they were sent; both
 * peers may send at once. Each side reads and writes its files and runs the
 * handlers on a thread of its own, never on the peer's event thread.
 * <p>
 * When the channel closes, transfers fail with a {@link
 * TransferException#isResumable() resumable} error. The sender may then
 * {@linkplain #resume(OutgoingTransfer) resume} one over a new channel, say
 * of a new peer once the connection was set up again, and the receiver
 * continues where it stopped once it accepts the offer with the same target.
 */
public final class FileTransfers implements AutoCloseable {

	private static final System.Logger LOGGER = System.getLogger(FileTransfers.class.getName());

	private static final SecureRandom RANDOM = new SecureRandom();

	/** How much of a file is hashed in one go before a resume, between other work. */
	private static final int HASH_STEP = 1024 * 1024;

	private final DataChannel channel;

	private final TransferOptions options;

	private final Executor executor;

	/** The thread this created for itself, or null with an injected one. */
	private final ExecutorService ownExecutor;

	/** The most bytes of received messages that may wait for the thread. */
	private final long inboundLimit;

	/** Bytes of received messages that wait for the thread. */
	private final AtomicLong inbound = new AtomicLong();

	/** Set once received messages went over {@link #inboundLimit}. */
	private final AtomicBoolean overflowed = new AtomicBoolean();

	private final CompletableFuture<Void> closed = new CompletableFuture<>();

	/** The bytes received after which this side acknowledges them. */
	private final long ackInterval;

	/** The most bytes of DATA frames whose send futures may be open. */
	private final long unsentLimit;

	// Confined to the thread.

	private boolean shut;

	private TransferException closeError;

	private Subscription messageSubscription;

	private Subscription stateSubscription;

	private final ArrayDeque<Outgoing> queue = new ArrayDeque<>();

	private Outgoing sending;

	private long unsent;

	/** The remote peer's offer that was neither accepted nor rejected yet. */
	private TransferOffer offer;

	private Incoming receiving;

	private final List<Consumer<TransferOffer>> offerHandlers = new ArrayList<>();


	private FileTransfers(DataChannel channel, TransferOptions options, Executor executor) {
		this.channel = channel;
		this.options = options;

		if (executor == null) {
			String name = "webrtc-transfer-" + channel.label();
			this.ownExecutor = Executors.newSingleThreadExecutor(task -> {
				Thread thread = new Thread(task, name);
				thread.setDaemon(true);
				return thread;
			});
			this.executor = ownExecutor;
		}
		else {
			this.ownExecutor = null;
			this.executor = executor;
		}

		this.ackInterval = Math.max(TransferOptions.MIN_CHUNK_SIZE, options.window / 4);
		this.unsentLimit = 4L * options.chunkSize;
		// The window, its frame headers and room for the control frames.
		this.inboundLimit = options.window + options.window / 8 + 64 * 1024;
	}

	/**
	 * Takes over a channel with the default options.
	 *
	 * @param channel the channel, which carries nothing else from now on.
	 *
	 * @return the file transfers over the channel.
	 */
	public static FileTransfers over(DataChannel channel) {
		return over(channel, TransferOptions.defaults());
	}

	/**
	 * Takes over a channel. Messages that arrived on it before are kept for
	 * it, as the channel keeps them for its next handler.
	 *
	 * @param channel the channel, which carries nothing else from now on.
	 * @param options how to send and receive.
	 *
	 * @return the file transfers over the channel.
	 */
	public static FileTransfers over(DataChannel channel, TransferOptions options) {
		return over(channel, options, null);
	}

	/** With a serial executor of the test's in place of a thread of its own. */
	static FileTransfers over(DataChannel channel, TransferOptions options, Executor executor) {
		requireNonNull(channel, "channel");
		requireNonNull(options, "options");

		FileTransfers transfers = new FileTransfers(channel, options, executor);

		// Subscribed on the thread, which so owns the subscriptions from the
		// start. The channel keeps what arrives until then.
		transfers.post(transfers::subscribe, () -> {});

		return transfers;
	}

	/**
	 * Sends a file under its own name.
	 *
	 * @param file the file, opened before this returns and read as it is
	 *             sent.
	 *
	 * @return the transfer, which starts once the transfers before it are
	 * done.
	 *
	 * @throws IOException if the file cannot be opened.
	 */
	public OutgoingTransfer send(Path file) throws IOException {
		requireNonNull(file, "file");

		if (file.getFileName() == null) {
			throw new IllegalArgumentException("The path " + file + " names no file");
		}

		return send(file, file.getFileName().toString());
	}

	/**
	 * Sends a file under the name given.
	 *
	 * @param file the file, opened before this returns and read as it is
	 *             sent.
	 * @param name the name the receiver sees, at most 1024 bytes of UTF-8.
	 *
	 * @return the transfer, which starts once the transfers before it are
	 * done.
	 *
	 * @throws IOException if the file cannot be opened.
	 */
	public OutgoingTransfer send(Path file, String name) throws IOException {
		requireNonNull(file, "file");
		requireNonNull(name, "name");
		Frames.utf8(name);

		return start(file, name, newId());
	}

	/**
	 * Sends a file again whose transfer failed with a {@linkplain
	 * TransferException#isResumable() resumable} error, as one of the same id
	 * and name. The receiver continues where it stopped once it accepts the
	 * offer with the same target, and the checksum covers the whole file.
	 *
	 * @param previous the transfer that failed, of these or of other file
	 *                 transfers.
	 *
	 * @return the new transfer.
	 *
	 * @throws IOException           if the file cannot be opened.
	 * @throws IllegalStateException if the transfer is not done, or failed in
	 *                               a way that cannot be resumed.
	 */
	public OutgoingTransfer resume(OutgoingTransfer previous) throws IOException {
		requireNonNull(previous, "previous");

		if (!(previous instanceof Outgoing)) {
			throw new IllegalArgumentException("Not a transfer of FileTransfers: " + previous);
		}

		Outgoing failed = (Outgoing) previous;
		TransferException failure = failed.failure;

		if (failure == null || !failure.isResumable()) {
			throw new IllegalStateException("The transfer " + failed.id + " did not fail in a way that can be resumed");
		}

		return start(failed.path, failed.name, failed.id);
	}

	/**
	 * Calls the handler with each file the remote peer offers. One offered
	 * while there is no handler waits for the next one.
	 *
	 * @param handler called on the thread of these file transfers; it accepts
	 *                or rejects the offer, then or later.
	 *
	 * @return the subscription that removes the handler.
	 */
	public Subscription onOffer(Consumer<TransferOffer> handler) {
		requireNonNull(handler, "handler");

		post(() -> {
			if (shut) {
				return;
			}

			offerHandlers.add(handler);

			if (offer != null && !offer.isDecided()) {
				call(handler, offer);
			}
		}, () -> {});

		return () -> post(() -> offerHandlers.remove(handler), () -> {});
	}

	/**
	 * @return a future that completes once these file transfers closed,
	 * exceptionally with a {@link TransferException} when the remote peer
	 * broke the protocol.
	 */
	public CompletableFuture<Void> whenClosed() {
		return closed.copy();
	}

	/**
	 * Closes the channel. Transfers that are not done fail with a resumable
	 * {@link TransferException}. Never blocks, may be called more than once.
	 */
	@Override
	public void close() {
		post(() -> shutDown(new TransferException("The file transfers over the data channel " + channel.label()
				+ " were closed", true)), () -> {});
	}

	// Called by the transfers and offers, on any thread.

	Subscription subscribe(TransferImpl transfer, Consumer<TransferProgress> handler) {
		post(() -> {
			if (!transfer.isDone()) {
				transfer.progressHandlers.add(handler);
			}
			call(handler, transfer.progress);
		}, () -> {});

		return () -> transfer.progressHandlers.remove(handler);
	}

	void cancel(TransferImpl transfer) {
		post(() -> cancelHere(transfer), () -> {});
	}

	IncomingTransfer accept(TransferOffer accepted, Path target) {
		Incoming transfer = new Incoming(this, accepted, target);

		post(() -> begin(transfer), () -> transfer.finish(closedError()));

		return transfer;
	}

	void reject(TransferOffer rejected, String reason) {
		post(() -> {
			if (offer == rejected) {
				offer = null;
				sendControl(Frames.reject(rejected.id(), reason));
			}
		}, () -> {});
	}

	// The thread.

	/**
	 * Runs the task on the thread, or the fallback if the thread is gone, so
	 * that what the task would complete never stays open.
	 */
	private void post(Runnable task, Runnable dropped) {
		try {
			executor.execute(task);
		}
		catch (RejectedExecutionException e) {
			dropped.run();
		}
	}

	private void subscribe() {
		if (shut) {
			return;
		}

		messageSubscription = channel.onMessage(this::received);
		stateSubscription = channel.onStateChange(state -> {
			if (state == ChannelState.CLOSING || state == ChannelState.CLOSED) {
				post(() -> shutDown(new TransferException("The data channel " + channel.label() + " closed", true)),
						() -> {});
			}
		});
	}

	/**
	 * Takes a message on the peer's event thread and passes it on. What waits
	 * for the thread is bounded: a peer that keeps to the protocol never has
	 * more than a window of data unconfirmed.
	 */
	private void received(Message message) {
		if (overflowed.get()) {
			return;
		}

		int size = message.size();

		if (inbound.addAndGet(size) > inboundLimit) {
			inbound.addAndGet(-size);

			if (overflowed.compareAndSet(false, true)) {
				post(() -> protocolError("it sent more than the window of " + options.window + " bytes allows"),
						() -> {});
			}
			return;
		}

		boolean text = message.isText();
		byte[] data = message.bytes();

		post(() -> {
			inbound.addAndGet(-size);
			handle(text, data);
		}, () -> inbound.addAndGet(-size));
	}

	private void handle(boolean text, byte[] message) {
		if (shut) {
			return;
		}
		if (text) {
			protocolError("it sent a text message");
			return;
		}

		Frames.Frame frame;

		try {
			frame = Frames.decode(message);
		}
		catch (ProtocolException e) {
			protocolError(e.getMessage());
			return;
		}

		switch (frame.type) {
			case Frames.OFFER:
				offered(frame);
				break;
			case Frames.ACCEPT:
				accepted(frame);
				break;
			case Frames.REJECT:
				rejected(frame);
				break;
			case Frames.DATA:
				data(frame);
				break;
			case Frames.ACK:
				acknowledged(frame);
				break;
			case Frames.END:
				ended(frame);
				break;
			case Frames.DONE:
				confirmed(frame);
				break;
			case Frames.CANCEL:
				cancelled(frame);
				break;
			default:
				protocolError("unknown frame type " + frame.type);
		}
	}

	// Sending.

	private Outgoing start(Path file, String name, String id) throws IOException {
		if (!Files.isRegularFile(file)) {
			throw new FileSystemException(file.toString(), null, "Not a regular file");
		}

		FileChannel source = FileChannel.open(file, StandardOpenOption.READ);
		Outgoing transfer;

		try {
			transfer = new Outgoing(this, id, name, source.size(), file, source);
		}
		catch (IOException | RuntimeException e) {
			source.close();
			throw e;
		}

		post(() -> {
			if (shut) {
				transfer.finish(closedError());
				return;
			}

			queue.add(transfer);
			next();
		}, () -> transfer.finish(closedError()));

		return transfer;
	}

	/** Offers the next file once the one before is done. */
	private void next() {
		while (sending == null && !shut && !queue.isEmpty()) {
			Outgoing transfer = queue.poll();

			if (transfer.isDone()) {
				continue;
			}

			sending = transfer;
			transfer.state = TransferImpl.State.OFFERED;
			sendControl(Frames.offer(transfer.id, transfer.size, transfer.name));
		}
	}

	private void accepted(Frames.Frame frame) {
		Outgoing transfer = sending(frame);

		if (transfer == null) {
			return;
		}
		if (transfer.state != TransferImpl.State.OFFERED) {
			protocolError("it accepted " + frame.id + " twice");
			return;
		}
		if (frame.value < 0 || frame.value > transfer.size || frame.window <= 0) {
			protocolError("it accepted " + frame.id + " from offset " + frame.value + " with a window of "
					+ frame.window);
			return;
		}

		transfer.next = frame.value;
		transfer.acked = frame.value;
		transfer.window = frame.window;
		transfer.state = TransferImpl.State.HASHING;
		progress(transfer, frame.value);

		hash(transfer, frame.value, () -> {
			transfer.state = TransferImpl.State.TRANSFERRING;
			pump();
		}, error -> abort(transfer, "Reading " + transfer.path + " failed", error));
	}

	/** Sends chunks while the receiver's window and the channel have room. */
	private void pump() {
		Outgoing transfer = sending;

		if (shut || transfer == null || transfer.state != TransferImpl.State.TRANSFERRING) {
			return;
		}

		while (transfer.next < transfer.size && transfer.next - transfer.acked < transfer.window
				&& unsent < unsentLimit) {
			long room = transfer.acked + transfer.window - transfer.next;
			int length = (int) Math.min(options.chunkSize, Math.min(transfer.size - transfer.next, room));
			byte[] frame = Frames.data(transfer.id, transfer.next, length);

			try {
				readFully(transfer.file, ByteBuffer.wrap(frame, Frames.DATA_HEADER, length), transfer.next);
			}
			catch (EOFException e) {
				abort(transfer, transfer.path + " got shorter than the " + transfer.size + " bytes offered", e);
				return;
			}
			catch (IOException e) {
				abort(transfer, "Reading " + transfer.path + " failed", e);
				return;
			}

			transfer.digest.update(frame, Frames.DATA_HEADER, length);
			transfer.next += length;
			unsent += frame.length;

			int size = frame.length;

			channel.send(frame).whenComplete((result, error) -> post(() -> {
				unsent -= size;

				if (error != null) {
					sendFailed(error);
				}
				else {
					pump();
				}
			}, () -> {}));
		}

		if (transfer.next == transfer.size && !transfer.endSent) {
			transfer.endSent = true;
			sendControl(Frames.end(transfer.id, transfer.digest.digest()));
		}
	}

	private void acknowledged(Frames.Frame frame) {
		Outgoing transfer = sending(frame);

		if (transfer == null) {
			return;
		}
		if (transfer.state != TransferImpl.State.TRANSFERRING || frame.value < transfer.acked
				|| frame.value > transfer.next) {
			protocolError("it acknowledged " + frame.value + " bytes of " + frame.id + ", of which "
					+ transfer.next + " were sent");
			return;
		}

		transfer.acked = frame.value;
		progress(transfer, frame.value);
		pump();
	}

	private void confirmed(Frames.Frame frame) {
		Outgoing transfer = sending(frame);

		if (transfer == null) {
			return;
		}
		if (!transfer.endSent) {
			protocolError("it confirmed " + frame.id + " before it got all of it");
			return;
		}

		if (transfer.progress.bytes() != transfer.size) {
			progress(transfer, transfer.size);
		}

		transfer.finish(null);
		endSending(transfer);
	}

	private void rejected(Frames.Frame frame) {
		Outgoing transfer = sending(frame);

		if (transfer == null) {
			return;
		}
		if (transfer.state != TransferImpl.State.OFFERED) {
			protocolError("it rejected " + frame.id + " after it accepted it");
			return;
		}

		transfer.finish(new TransferException("The remote peer rejected " + transfer.name + ": "
				+ Frames.printable(frame.text), false));
		endSending(transfer);
	}

	/** Tells the receiver the transfer failed, and ends it. */
	private void abort(Outgoing transfer, String reason, Throwable cause) {
		sendControl(Frames.cancel(transfer.id, true, reason));
		transfer.finish(new TransferException(reason, cause, false));
		endSending(transfer);
	}

	private void endSending(Outgoing transfer) {
		if (sending == transfer) {
			sending = null;
			next();
		}
		else {
			queue.remove(transfer);
		}
	}

	/** @return the transfer sent now if the frame is about it, else null. */
	private Outgoing sending(Frames.Frame frame) {
		// A frame for another one was on its way when this side ended that.
		return sending != null && sending.id.equals(frame.id) ? sending : null;
	}

	// Receiving.

	private void offered(Frames.Frame frame) {
		if (frame.version != Frames.VERSION) {
			sendControl(Frames.reject(frame.id, "Version " + frame.version + " of the protocol is not supported"));
			return;
		}
		if (offer != null || receiving != null) {
			protocolError("it offered " + frame.id + " while another transfer was not done");
			return;
		}
		if (frame.value < 0) {
			protocolError("it offered " + frame.id + " with a size of " + frame.value);
			return;
		}

		offer = new TransferOffer(this, frame.id, frame.text, frame.value);

		for (Consumer<TransferOffer> handler : new ArrayList<>(offerHandlers)) {
			if (offer == null || offer.isDecided()) {
				break;
			}
			call(handler, offer);
		}
	}

	/** Opens the partial file of an accepted offer and accepts from where it ends. */
	private void begin(Incoming transfer) {
		if (shut) {
			transfer.finish(closedError());
			return;
		}
		if (offer != transfer.offer) {
			transfer.finish(new TransferCancelledException("The remote peer cancelled the transfer of "
					+ transfer.id + " before it was accepted"));
			return;
		}

		offer = null;

		if (transfer.isDone()) {
			// Cancelled before it began.
			sendControl(Frames.reject(transfer.id, "Cancelled"));
			return;
		}

		receiving = transfer;
		transfer.state = TransferImpl.State.HASHING;

		long offset = 0;

		try {
			if (options.resumable && Files.isRegularFile(transfer.part)) {
				offset = Files.size(transfer.part);

				if (offset > transfer.size) {
					offset = 0;
				}
			}

			transfer.file = FileChannel.open(transfer.part, StandardOpenOption.CREATE, StandardOpenOption.READ,
					StandardOpenOption.WRITE);
			transfer.file.truncate(offset);
		}
		catch (IOException e) {
			refuse(transfer, e);
			return;
		}

		long start = offset;

		hash(transfer, start, () -> {
			transfer.state = TransferImpl.State.TRANSFERRING;
			transfer.written = start;
			transfer.acked = start;
			progress(transfer, start);
			sendControl(Frames.accept(transfer.id, start, options.window));
		}, error -> refuse(transfer, error));
	}

	/** Rejects an accepted offer whose partial file cannot be used. */
	private void refuse(Incoming transfer, IOException cause) {
		String reason = "The receiver cannot write the file";

		sendControl(Frames.reject(transfer.id, reason));
		receiving = null;
		transfer.closeFile();
		delete(transfer.part);
		transfer.finish(new TransferException(reason + " " + transfer.part, cause, false));
	}

	private void data(Frames.Frame frame) {
		Incoming transfer = receiving(frame);

		if (transfer == null) {
			return;
		}

		long end = frame.value + frame.payloadLength;

		if (transfer.state != TransferImpl.State.TRANSFERRING) {
			protocolError("it sent data of " + frame.id + " before it was accepted");
			return;
		}
		if (frame.value != transfer.written || frame.payloadLength == 0) {
			protocolError("it sent " + frame.payloadLength + " bytes of " + frame.id + " at offset " + frame.value
					+ " where " + transfer.written + " were expected");
			return;
		}
		if (end > transfer.size) {
			protocolError("it sent more of " + frame.id + " than the " + transfer.size + " bytes offered");
			return;
		}
		if (end > transfer.acked + options.window) {
			protocolError("it sent more of " + frame.id + " than the window of " + options.window + " bytes allows");
			return;
		}

		try {
			ByteBuffer payload = ByteBuffer.wrap(frame.payload, frame.payloadOffset, frame.payloadLength);
			long position = transfer.written;

			while (payload.hasRemaining()) {
				position += transfer.file.write(payload, position);
			}
		}
		catch (IOException e) {
			failReceiving(transfer, "Writing " + transfer.part + " failed", e);
			return;
		}

		transfer.digest.update(frame.payload, frame.payloadOffset, frame.payloadLength);
		transfer.written = end;

		if (transfer.written - transfer.acked >= ackInterval || transfer.written == transfer.size) {
			transfer.acked = transfer.written;
			sendControl(Frames.ack(transfer.id, transfer.acked));
			progress(transfer, transfer.acked);
		}
	}

	/** Checks the whole file and moves it into place. */
	private void ended(Frames.Frame frame) {
		Incoming transfer = receiving(frame);

		if (transfer == null) {
			return;
		}
		if (transfer.state != TransferImpl.State.TRANSFERRING || transfer.written != transfer.size) {
			protocolError("it ended " + frame.id + " after " + transfer.written + " of " + transfer.size + " bytes");
			return;
		}
		if (!MessageDigest.isEqual(transfer.digest.digest(), frame.digest)) {
			failReceiving(transfer, "The SHA-256 of the received file " + transfer.path + " does not match the "
					+ "sender's", null);
			return;
		}

		try {
			transfer.file.force(true);
			transfer.file.close();
			move(transfer.part, transfer.path);
		}
		catch (IOException e) {
			failReceiving(transfer, "Moving the received file to " + transfer.path + " failed", e);
			return;
		}

		receiving = null;
		sendControl(Frames.done(transfer.id));
		transfer.finish(null);
	}

	/** Tells the sender the transfer failed, and ends it. */
	private void failReceiving(Incoming transfer, String reason, Throwable cause) {
		sendControl(Frames.cancel(transfer.id, true, reason));
		receiving = null;
		transfer.closeFile();
		delete(transfer.part);
		transfer.finish(new TransferException(reason, cause, false));
	}

	/** @return the transfer received now if the frame is about it, else null. */
	private Incoming receiving(Frames.Frame frame) {
		return receiving != null && receiving.id.equals(frame.id) ? receiving : null;
	}

	// Both directions.

	private void cancelled(Frames.Frame frame) {
		String reason = frame.text.isEmpty() ? "" : ": " + Frames.printable(frame.text);

		Outgoing outgoing = sending(frame);

		if (outgoing != null) {
			outgoing.finish(remoteEnd(frame, outgoing.name, reason));
			endSending(outgoing);
			return;
		}

		Incoming incoming = receiving(frame);

		if (incoming != null) {
			receiving = null;
			incoming.closeFile();
			delete(incoming.part);
			incoming.finish(remoteEnd(frame, incoming.path.toString(), reason));
			return;
		}

		if (offer != null && offer.id().equals(frame.id)) {
			// An accept that follows finds the offer gone.
			offer = null;
		}
	}

	private static TransferException remoteEnd(Frames.Frame frame, String name, String reason) {
		return frame.failed
				? new TransferException("The remote peer stopped the transfer of " + name + reason, false)
				: new TransferCancelledException("The remote peer cancelled the transfer of " + name + reason);
	}

	private void cancelHere(TransferImpl transfer) {
		if (transfer.isDone()) {
			return;
		}

		TransferCancelledException cancelled = new TransferCancelledException("The transfer " + transfer.id
				+ " was cancelled");

		if (transfer instanceof Outgoing) {
			Outgoing outgoing = (Outgoing) transfer;

			if (sending == outgoing) {
				sendControl(Frames.cancel(outgoing.id, false, ""));
			}

			outgoing.finish(cancelled);
			endSending(outgoing);
		}
		else {
			Incoming incoming = (Incoming) transfer;

			if (receiving == incoming) {
				sendControl(Frames.cancel(incoming.id, false, ""));
				receiving = null;
			}

			incoming.closeFile();
			delete(incoming.part);
			incoming.finish(cancelled);
		}
	}

	/**
	 * Hashes the first bytes of a transfer's file a step at a time, so that
	 * a large file does not hold up the other direction, then runs the next
	 * step unless the transfer ended meanwhile.
	 */
	private void hash(TransferImpl transfer, long end, Runnable then, Consumer<IOException> failed) {
		ByteBuffer buffer = ByteBuffer.allocate((int) Math.max(1, Math.min(HASH_STEP, end)));

		hashStep(transfer, 0, end, buffer, then, failed);
	}

	private void hashStep(TransferImpl transfer, long position, long end, ByteBuffer buffer, Runnable then,
			Consumer<IOException> failed) {
		if (shut || transfer.isDone()) {
			return;
		}
		if (position == end) {
			then.run();
			return;
		}

		buffer.clear();
		buffer.limit((int) Math.min(buffer.capacity(), end - position));

		try {
			readFully(transfer.file, buffer, position);
		}
		catch (IOException e) {
			failed.accept(e);
			return;
		}

		transfer.digest.update(buffer.array(), 0, buffer.limit());

		long next = position + buffer.limit();

		post(() -> hashStep(transfer, next, end, buffer, then, failed), () -> {});
	}

	private void progress(TransferImpl transfer, long bytes) {
		TransferProgress progress = new TransferProgress(bytes, transfer.size);
		transfer.progress = progress;

		for (Consumer<TransferProgress> handler : transfer.progressHandlers) {
			call(handler, progress);
		}
	}

	private void sendControl(byte[] frame) {
		channel.send(frame).whenComplete((result, error) -> {
			if (error != null) {
				post(() -> sendFailed(error), () -> {});
			}
		});
	}

	private void sendFailed(Throwable error) {
		Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;

		shutDown(new TransferException("Sending on the data channel " + channel.label() + " failed", cause,
				cause instanceof PeerClosedException));
	}

	/**
	 * Ends the transfers, tells the remote peer of those it takes part in,
	 * and closes the channel.
	 */
	private void protocolError(String what) {
		if (shut) {
			return;
		}

		String reason = "The remote peer broke the file transfer protocol: " + what;
		List<CompletableFuture<Void>> told = new ArrayList<>();

		if (sending != null) {
			told.add(channel.send(Frames.cancel(sending.id, true, reason)));
		}
		if (receiving != null) {
			told.add(channel.send(Frames.cancel(receiving.id, true, reason)));
		}

		// The channel drops on close what it has not handed to the transport.
		shutDown(new TransferException(reason, false), CompletableFuture.allOf(told.toArray(new CompletableFuture[0])));
	}

	private void shutDown(TransferException error) {
		shutDown(error, CompletableFuture.completedFuture(null));
	}

	private void shutDown(TransferException error, CompletableFuture<?> beforeClose) {
		if (shut) {
			return;
		}

		shut = true;
		closeError = error;

		if (sending != null) {
			sending.finish(error);
			sending = null;
		}

		for (Outgoing transfer : queue) {
			transfer.finish(error);
		}
		queue.clear();

		if (receiving != null) {
			receiving.closeFile();

			if (!error.isResumable() || !options.resumable) {
				delete(receiving.part);
			}

			receiving.finish(error);
			receiving = null;
		}

		offer = null;
		offerHandlers.clear();

		if (messageSubscription != null) {
			messageSubscription.close();
			stateSubscription.close();
		}

		beforeClose.whenComplete((result, failure) -> channel.close());

		if (ownExecutor != null) {
			ownExecutor.shutdown();
		}

		if (error.isResumable()) {
			closed.complete(null);
		}
		else {
			closed.completeExceptionally(error);
		}
	}

	private TransferException closedError() {
		TransferException error = closeError;

		return error != null ? error : new TransferException("The file transfers over the data channel "
				+ channel.label() + " are closed", true);
	}

	private static <T> void call(Consumer<T> handler, T value) {
		try {
			handler.accept(value);
		}
		catch (Throwable e) {
			LOGGER.log(System.Logger.Level.WARNING, "A file transfer handler failed", e);
		}
	}

	private static void readFully(FileChannel file, ByteBuffer buffer, long position) throws IOException {
		while (buffer.hasRemaining()) {
			int read = file.read(buffer, position);

			if (read < 0) {
				throw new EOFException("The file ends at " + position);
			}

			position += read;
		}
	}

	private static void move(Path from, Path to) throws IOException {
		try {
			Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (AtomicMoveNotSupportedException e) {
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void delete(Path file) {
		try {
			Files.deleteIfExists(file);
		}
		catch (IOException e) {
			LOGGER.log(System.Logger.Level.WARNING, "Deleting " + file + " failed", e);
		}
	}

	private static String newId() {
		byte[] id = new byte[Frames.ID_LENGTH];
		RANDOM.nextBytes(id);
		return Frames.hex(id);
	}
}
