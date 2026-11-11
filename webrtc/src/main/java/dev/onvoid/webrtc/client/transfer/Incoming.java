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

/**
 * The {@link IncomingTransfer} of a {@link FileTransfers}.
 */
final class Incoming extends TransferImpl implements IncomingTransfer {

	final TransferOffer offer;

	final Path part;

	// Confined to the thread of the owner.

	/** The bytes written to the partial file. */
	long written;

	/** The bytes confirmed to the sender. */
	long acked;


	Incoming(FileTransfers owner, TransferOffer offer, Path target) {
		super(owner, offer.id(), offer.name(), offer.size(), target);

		this.offer = offer;
		this.part = target.resolveSibling(target.getFileName() + "." + offer.id().substring(0, 16) + ".part");
	}

	@Override
	public Path partialFile() {
		return part;
	}
}
