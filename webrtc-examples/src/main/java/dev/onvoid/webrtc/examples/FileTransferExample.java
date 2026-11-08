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

package dev.onvoid.webrtc.examples;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import dev.onvoid.webrtc.client.AudioMode;
import dev.onvoid.webrtc.client.Peer;
import dev.onvoid.webrtc.client.PeerOptions;
import dev.onvoid.webrtc.client.Signaling;
import dev.onvoid.webrtc.client.SignalingPair;
import dev.onvoid.webrtc.client.WebRtc;
import dev.onvoid.webrtc.client.transfer.FileTransfers;
import dev.onvoid.webrtc.client.transfer.IncomingTransfer;
import dev.onvoid.webrtc.client.transfer.OutgoingTransfer;

/**
 * Example of file transfer over a data channel ({@code dev.onvoid.webrtc.client.transfer}): two
 * peers in one process, one sends a file, the other saves it.
 * <p>
 * This example shows how to:
 * <ul>
 *   <li>Run {@link FileTransfers} over a data channel on both sides</li>
 *   <li>Accept an offer under a safe file name in a directory of the application's choice</li>
 *   <li>Follow the progress the receiver confirms</li>
 *   <li>Wait until the receiver checked the SHA-256 of the file and moved it into place</li>
 * </ul>
 * <p>
 * Pass a file to send, or a size in MiB for a file of random data, 20 by default:
 * <pre>
 * mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.FileTransferExample" -D"exec.args=50"
 * </pre>
 *
 * @author Alex Andres
 */
public class FileTransferExample {

    public static void main(String[] args) throws Exception {
        Path downloads = Files.createTempDirectory("webrtc-downloads");
        Path source = source(args.length > 0 ? args[0] : "20");

        try (WebRtc rtc = WebRtc.builder().audio(AudioMode.HEADLESS).build()) {
            // Both peers in one process. A real application signals through its own server.
            SignalingPair link = Signaling.loopback();
            Peer sender = rtc.createPeer(PeerOptions.builder()
                    .name("sender")
                    .signaling(link.first())
                    .build());
            Peer receiver = rtc.createPeer(PeerOptions.builder()
                    .name("receiver")
                    .signaling(link.second())
                    .polite(true)
                    .build());

            CompletableFuture<IncomingTransfer> incoming = new CompletableFuture<>();

            receiver.onChannel(channel -> {
                if (!channel.label().equals("files")) {
                    return;
                }

                FileTransfers.over(channel).onOffer(offer -> {
                    // The name comes from the remote peer: never use it as a path as it is.
                    System.out.printf("Receiver is offered %s, %d bytes%n", offer.safeFileName(), offer.size());
                    incoming.complete(offer.accept(downloads.resolve(offer.safeFileName())));
                });
            });

            FileTransfers files = FileTransfers.over(sender.createChannel("files"));
            OutgoingTransfer upload = files.send(source);

            upload.onProgress(progress -> System.out.printf("Receiver confirmed %.0f%% (%d of %d bytes)%n",
                    100 * progress.fraction(), progress.bytes(), progress.size()));

            // The main thread may wait; a handler must not.
            upload.whenDone().get(60, TimeUnit.SECONDS);

            IncomingTransfer received = incoming.get(5, TimeUnit.SECONDS);
            received.whenDone().get(5, TimeUnit.SECONDS);

            boolean same = Arrays.equals(Files.readAllBytes(source), Files.readAllBytes(received.file()));

            System.out.println("Receiver saved " + received.file() + (same ? ", identical to " : ", differing from ")
                    + source);
        }
    }

    /**
     * @return the file named, or a new file of random data of that many MiB.
     */
    private static Path source(String argument) throws Exception {
        Path file = Paths.get(argument);

        if (Files.isRegularFile(file)) {
            return file;
        }

        byte[] data = new byte[Integer.parseInt(argument) * 1024 * 1024];
        new Random().nextBytes(data);

        Path random = Files.createTempFile("webrtc-upload", ".bin");
        random.toFile().deleteOnExit();
        return Files.write(random, data);
    }
}
