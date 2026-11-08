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

import java.nio.channels.FileChannel;
import java.nio.file.Path;

/**
 * The {@link OutgoingTransfer} of a {@link FileTransfers}. It reads its file
 * through a channel opened when it was created.
 */
final class Outgoing extends TransferImpl implements OutgoingTransfer {

	/** The failure it ended with, which decides whether it may be resumed. */
	volatile TransferException failure;

	// Confined to the thread of the owner.

	/** The offset of the next chunk to send. */
	long next;

	/** The bytes the receiver confirmed. */
	long acked;

	/** The bytes the receiver lets this side send beyond those it confirmed. */
	int window;

	boolean endSent;


	Outgoing(FileTransfers owner, String id, String name, long size, Path source, FileChannel file) {
		super(owner, id, name, size, source);

		this.file = file;
	}

	@Override
	void finish(TransferException error) {
		if (!isDone()) {
			failure = error;
		}
		super.finish(error);
	}
}
