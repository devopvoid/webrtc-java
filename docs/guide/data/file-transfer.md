# File Transfer

`dev.onvoid.webrtc.client.transfer` sends files over a [client API](/guide/client-api) data channel. It adds what a plain `send()` doesn't give you: chunking with bounded memory, progress, cancelling, a confirmation from the receiver with a SHA-256 checksum, and resuming after a reconnect.

```java
FileTransfers files = FileTransfers.over(peer.createChannel("files"));

OutgoingTransfer upload = files.send(Paths.get("report.pdf"));
upload.onProgress(p -> System.out.printf("%.0f%%%n", 100 * p.fraction()));
upload.whenDone().thenRun(() -> System.out.println("The receiver has it"));

// On the other side:
peer.onChannel(channel -> {
    if (channel.label().equals("files")) {
        FileTransfers.over(channel).onOffer(offer ->
                offer.accept(downloads.resolve(offer.safeFileName())));
    }
});
```

`FileTransfers` takes over the channel: nothing else may be sent on it.

- Files go one at a time in each direction, in the order they were sent, and both peers may send at the same time.
- Each side reads and writes its files, and runs its handlers, on a thread of its own, not on the peer's event thread. Those handlers must not block.

## Confirmation, not just handing data to the transport

The future that `DataChannel.send()` returns completes once the data has been handed to the transport. It tells you nothing about the receiver. The transfer protocol has its own confirmations:

- The receiver acknowledges the bytes it wrote to its file, a quarter of its window at a time. Progress on both sides counts confirmed bytes.
- The sender never has more than the receiver's window unconfirmed (`TransferOptions.builder().window(bytes)`, 4 MiB by default). A slow disk therefore slows the sender down instead of filling memory. Keep the window below the receiver's `channelReceiveLimit`.
- `whenDone()` completes only once the receiver has checked the SHA-256 of the whole file against the sender's and moved the file into place.

## Offers

An offer's `name()` and `size()` come from the remote peer. Don't turn the name into a path as it is, because it may contain `../` or path separators. `safeFileName()` gives you a single file name that stays in the directory you resolve it against.

`reject(reason)` makes the sender's transfer fail with that reason, and the sender's next file follows. If an offer arrives before any handler is registered, it waits for the first handler.

The data goes to `partialFile()` next to the target, and that file replaces the target only after the checksum matched.

The receiver treats it as a protocol error if more data arrives than the offer announced, more than the window allows, or the data arrives out of order. The transfer then fails, and the channel closes.

## Cancelling and failures

`cancel()` works on either side. It tells the remote peer, and the receiver deletes its partial file. The other side fails with a `TransferCancelledException`. Every other failure is a `TransferException`, for example a rejected offer, a checksum mismatch, a read or write error, or a protocol error.

## Resuming after a reconnect

When the channel closes, or `close()` is called, unfinished transfers fail with an exception whose `isResumable()` is true. The receiver keeps its partial file. Over a new channel, for example of a new peer after the connection has been set up again, the sender resumes the transfer:

```java
OutgoingTransfer resumed = newFiles.resume(interrupted);
```

The offer keeps its id. If the receiver accepts it with the same target, the transfer continues from the end of the partial file, and the checksum still covers the whole file. With `TransferOptions.builder().resumable(false)`, the receiver deletes partial files on every failure and always starts over. An application that won't resume a transfer deletes `partialFile()` itself.

## Wire format

Each frame is one binary message, big-endian, and starts with its type and the 16-byte transfer id:

| Frame  | Type | Fields after the id                     |
|--------|------|-----------------------------------------|
| OFFER  | 1    | version (u8, 1), size (u64), name (text) |
| ACCEPT | 2    | offset (u64), window (u32)              |
| REJECT | 3    | reason (text)                           |
| DATA   | 4    | offset (u64), payload                   |
| ACK    | 5    | offset (u64)                            |
| END    | 6    | SHA-256 (32 bytes)                      |
| DONE   | 7    |                                         |
| CANCEL | 8    | failed (u8), reason (text)              |

A text is a u16 length followed by at most 1024 bytes of UTF-8.
