# Data Channels

A data channel carries text and binary messages between two peers. `peer.createChannel` opens one, and `peer.onChannel` hands over those the remote peer opens:

```java
DataChannel chat = peer.createChannel("chat");

chat.onMessage(message -> System.out.println("Peer says: " + message.text()));
chat.send("hello");                          // sent as soon as the channel opens

peer.onChannel(channel -> {                  // channels the remote peer opens
    if (channel.label().equals("files")) {
        channel.onMessage(message -> output.write(message.bytes()));
    }
});
```

- A channel may be used right away: messages sent before it opens are held until it does.
- Messages that arrive while a channel has no `onMessage` handler are kept for the next one, so none are lost while a handler is being set up.
- `send` takes a `String`, a `byte[]` or a `ByteBuffer`, and copies the data before it returns.
- A `Message` holds a copy of the data. `isText()` tells text from binary, `text()` decodes it as UTF-8, and `bytes()` returns a copy.

What a message shows in logs is described under [Details](#message-previews).

## Flow Control

The future `send` returns completes when the message is handed to the transport. While more than 1 MiB waits to be sent, further messages are held in Java, and their futures complete only as the backlog shrinks. A loop that waits for each send therefore slows down to what the network carries, instead of filling native memory:

```java
for (byte[] chunk : chunks) {
    channel.send(chunk).get();               // from a thread that is not an event thread
}
```

A sender that does not wait still cannot fill Java memory: each channel holds at most 16 MiB waiting to be sent, and a `send` beyond that fails at once with a `ChannelFullException`. `PeerOptions.builder().channelSendLimit(bytes)` changes the limit for every channel of a peer. `bufferedAmount()` tells how much a channel holds.

Received messages are bounded as well. WebRTC cannot be told to stop receiving, so messages wait in Java when they arrive faster than the handlers take them, or while a channel has no handler. Each channel lets at most 16 MiB of them wait.

When more arrive, the channel closes, instead of dropping messages unnoticed:

- The peer's `onError` handlers hear why.
- The messages after the one over the limit are not delivered.
- Both sides see the channel close.

`PeerOptions.builder().channelReceiveLimit(bytes)` changes the limit. A handler that takes long should hand the work to a thread of its own.

## Channel Options

`ChannelOptions` set how a channel delivers:

```java
DataChannel positions = peer.createChannel("positions", ChannelOptions.builder()
        .ordered(false)
        .maxRetransmits(0)
        .build());
```

`negotiatedId(int)` creates a channel both peers set up themselves with the same id, without announcing it; such a channel never shows up in `onChannel`. The [Data Channels](/guide/data/data-channels) guide explains the options in detail.

The ranges the options accept are listed under [Details](#option-ranges).

## Sending Files

A completed `send` future means the data was handed to the transport, not that the remote application processed it. To send files, use `FileTransfers` from `dev.onvoid.webrtc.client.transfer`, which adds the receiver's own confirmations, a SHA-256 checksum, progress, cancelling and resume. See the [File Transfer](/guide/data/file-transfer) guide.

## Details

### Message Previews

`Message.toString()` reports only the message type and byte length. Access the content explicitly, with `text()` or `bytes()`.

- For diagnostics, `message.preview(64)` inspects at most 64 payload bytes, and escapes control characters. Binary previews use hex.
- `message.preview(64, prefix -> redact(prefix))` applies your own masking before escaping. The masking function must handle secrets truncated at the prefix boundary.
- The preview marks truncated content, and also limits the masking function's output.

### Option Ranges

`maxRetransmits(int)` accepts 0 to 65535 retransmissions. `maxPacketLifeTime(Duration)` accepts nonnegative durations up to 65535 milliseconds; positive fractions of a millisecond are rounded down, so a duration below one millisecond becomes zero. The two limits exclude each other. Values outside these ranges are rejected before native processing.
