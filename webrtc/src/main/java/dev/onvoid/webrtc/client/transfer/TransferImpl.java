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

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import dev.onvoid.webrtc.client.Subscription;

/**
 * What both directions of a transfer share. The state is confined to the
 * thread of the {@link FileTransfers}, except the progress and the done
 * future, which may be read anywhere.
 */
abstract class TransferImpl implements Transfer {

	enum State {
		/** Queued by the sender, or accepted and not yet begun by the receiver. */
		WAITING,
		/** The sender offered it and waits for an answer. */
		OFFERED,
		/** The SHA-256 of what was transferred before a resume is computed. */
		HASHING,
		/** Data flows. */
		TRANSFERRING
	}

	final FileTransfers owner;

	final String id;

	final String name;

	final long size;

	final Path path;

	final CompletableFuture<Void> done = new CompletableFuture<>();

	final List<Consumer<TransferProgress>> progressHandlers = new CopyOnWriteArrayList<>();

	volatile TransferProgress progress;

	// Confined to the thread of the owner.

	State state = State.WAITING;

	FileChannel file;

	final MessageDigest digest;


	TransferImpl(FileTransfers owner, String id, String name, long size, Path path) {
		this.owner = owner;
		this.id = id;
		this.name = name;
		this.size = size;
		this.path = path;
		this.progress = new TransferProgress(0, size);

		try {
			this.digest = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e) {
			// Every Java platform has SHA-256.
			throw new IllegalStateException(e);
		}
	}

	@Override
	public String id() {
		return id;
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public long size() {
		return size;
	}

	@Override
	public Path file() {
		return path;
	}

	@Override
	public TransferProgress progress() {
		return progress;
	}

	@Override
	public Subscription onProgress(Consumer<TransferProgress> handler) {
		requireNonNull(handler, "handler");

		return owner.subscribe(this, handler);
	}

	@Override
	public CompletableFuture<Void> whenDone() {
		return done.copy();
	}

	@Override
	public void cancel() {
		owner.cancel(this);
	}

	boolean isDone() {
		return done.isDone();
	}

	/**
	 * Ends the transfer, with a failure or, when the error is null, done.
	 * Runs on the owner's thread, or anywhere for a transfer the owner never
	 * took.
	 */
	void finish(TransferException error) {
		closeFile();
		progressHandlers.clear();

		if (error == null) {
			done.complete(null);
		}
		else {
			done.completeExceptionally(error);
		}
	}

	void closeFile() {
		FileChannel open = file;

		if (open != null) {
			try {
				open.close();
			}
			catch (IOException e) {
				// Nothing left to lose.
			}
		}
	}

	@Override
	public String toString() {
		return getClass().getSimpleName() + "[" + id + ", " + progress + "]";
	}
}
