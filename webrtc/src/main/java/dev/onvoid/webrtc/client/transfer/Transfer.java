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

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import dev.onvoid.webrtc.client.Subscription;

/**
 * A file on its way to or from the remote peer, over {@link FileTransfers}.
 * <p>
 * Handlers run on the thread of the {@link FileTransfers}, which also reads
 * and writes the files, so they should not block.
 */
public interface Transfer {

	/**
	 * @return the id both peers know the transfer by, 32 hex digits. A
	 * resumed transfer keeps the id.
	 */
	String id();

	/**
	 * @return the name the sender gave the file.
	 */
	String name();

	/**
	 * @return the size of the file in bytes.
	 */
	long size();

	/**
	 * @return the file read on this side, or for the receiver the file that
	 * holds the data once the transfer is done.
	 */
	Path file();

	/**
	 * @return the bytes the receiver confirmed so far.
	 */
	TransferProgress progress();

	/**
	 * Calls the handler with the current progress, and then each time the
	 * receiver confirms more data, a quarter of its window at a time and once
	 * the file is complete.
	 *
	 * @param handler called on the thread of the {@link FileTransfers}.
	 *
	 * @return the subscription that removes the handler.
	 */
	Subscription onProgress(Consumer<TransferProgress> handler);

	/**
	 * @return a future that completes once the receiver wrote the whole file,
	 * found its SHA-256 to match the sender's and moved it into place. It
	 * fails with a {@link TransferException}, a {@link
	 * TransferCancelledException} for a cancelled transfer. Each call returns
	 * a future of its own, so completing or cancelling it changes nothing for
	 * the transfer.
	 */
	CompletableFuture<Void> whenDone();

	/**
	 * Cancels the transfer and tells the remote peer, unless it is done
	 * already. The receiver deletes the data it received. Never blocks, may be
	 * called more than once.
	 */
	void cancel();

}
