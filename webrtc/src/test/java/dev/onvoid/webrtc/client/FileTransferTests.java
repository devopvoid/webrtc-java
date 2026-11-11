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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

import dev.onvoid.webrtc.client.transfer.FileTransfers;
import dev.onvoid.webrtc.client.transfer.IncomingTransfer;
import dev.onvoid.webrtc.client.transfer.OutgoingTransfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * File transfers over a data channel between two real peers.
 */
class FileTransferTests extends ClientTestBase {

	@TempDir
	Path dir;


	@Test
	void sendsFileBetweenPeers() throws Exception {
		Peer[] peers = pair();
		CompletableFuture<IncomingTransfer> incoming = new CompletableFuture<>();

		peers[1].onChannel(channel -> FileTransfers.over(channel)
				.onOffer(offer -> incoming.complete(offer.accept(dir.resolve(offer.safeFileName() + ".out")))));

		byte[] data = new byte[5 * 1024 * 1024 + 17];
		new Random(1).nextBytes(data);
		Path source = Files.write(dir.resolve("video.bin"), data);

		FileTransfers sender = FileTransfers.over(peers[0].createChannel("files"));
		OutgoingTransfer outgoing = sender.send(source);

		await(outgoing.whenDone());
		IncomingTransfer received = await(incoming);
		await(received.whenDone());

		assertEquals(dir.resolve("video.bin.out"), received.file());
		assertArrayEquals(data, Files.readAllBytes(received.file()));
		assertEquals(data.length, outgoing.progress().bytes());

		sender.close();
		await(sender.whenClosed());
	}
}
