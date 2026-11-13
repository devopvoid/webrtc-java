# Statistics and Call Checks

`peer.stats()` returns the numbers people look for first, taken from the full [RTC stats](/guide/monitoring/rtc-stats) report:

```java
peer.onStats(Duration.ofSeconds(1), stats -> System.out.printf(
        "%s candidate, rtt %s, %d kbit/s out, %d kbit/s in, %.1f%% lost%n",
        stats.candidateType(), stats.roundTripTime(),
        stats.outgoingBitrate() / 1000, stats.incomingBitrate() / 1000,
        stats.recentPacketLoss() * 100));
```

| Method | Value |
|---|---|
| `transport()` | The transport in use, whose candidate type and round trip time the sample reports. Null while no transport has a candidate pair in use. |
| `transports()` | Every transport of the peer, ordered by id, each with its id, the id of its candidate pair in use, candidate type, round trip time and bytes. |
| `candidateType()` | The type of the local candidate in use on `transport()`: `host`, `srflx`, `prflx` or `relay`. Null while not connected. |
| `roundTripTime()` | The current round trip time on `transport()`, or null while not measured. |
| `bytesSent()`, `bytesReceived()` | The bytes sent and received on all transports so far. |
| `timestamp()` | When WebRTC measured the statistics. |
| `outgoingBitrate()`, `incomingBitrate()` | Bits per second between the measurement times of the previous sample and this one, or -1 (see below). |
| `packetLoss()` | The share of received packets lost so far, 0 to 1. After a long session it barely moves. |
| `recentPacketLoss()` | The share of expected packets lost since the previous sample, 0 to 1; 0 if none were expected, or -1 (see below). |
| `outboundVideo()`, `inboundVideo()` | Codec, resolution and frame rate of each video stream, and what limits a sent one. |
| `inboundAudio()`, `outboundAudio()` | Audio streams ordered by statistics id: codec, jitter, concealment counters, audio level and sample counters. |
| `diagnostics()` | The current state and confirmed `QualityCondition` causes. |
| `diagnosticReport()` | A JSON snapshot with allowlisted diagnostics, media measurements, codec names, OS/architecture, Java version, library version and processor count. |
| `report()` | The whole `RTCStatsReport`, including potentially sensitive connection information. |

- The bitrates are -1 when there is nothing to compare with: for a peer's first sample, after the clock went back, or after the byte counter started again. A report that WebRTC hands out again within its short cache lifetime repeats the previous bitrates.
- `recentPacketLoss()` is -1 for the first sample and after the clock went back. A stream that came up, or whose counters started again, counts all it has.
- In the audio statistics, unavailable fields are null. Received PCM callbacks are observed only while an `onAudio` sink is attached.

How `transport()` is chosen when media do not share one transport is described under [Details](#separate-transports).

## Diagnosis and Quality Events

```java
Subscription diagnosis = peer.onDiagnostics(Duration.ofSeconds(1), status -> {
    System.out.println(status.state());
    status.conditions().forEach(cause -> System.out.println(cause.reason()));
});

// Export on demand, for example from a support button. File I/O belongs on
// an application worker, outside the peer's event executor.
peer.stats().thenAccept(stats -> supportExecutor.execute(() -> {
    try {
        Files.write(Paths.get("diagnostics.json"),
                stats.diagnosticReport().getBytes(StandardCharsets.UTF_8));
    }
    catch (IOException error) {
        error.printStackTrace();
    }
}));
```

The export example uses `java.nio.file.Files`, `java.nio.file.Paths`, `java.nio.charset.StandardCharsets` and `java.io.IOException`. `supportExecutor` is an executor of the application.

`onDiagnostics` reports the first diagnosis, and after that only changes of its state or causes, including recovery. Its handlers run on the peer's event executor, and closing the subscription stops sampling. All `stats`, `onStats` and `onDiagnostics` calls of a peer share one history. A captured `PeerStats` and its diagnosis never change.

There is no combined score. A diagnosis is in one of three states:

- `OBSERVING`: there is no continuous measurement interval yet. This includes the time after a clock rollback or a gap longer than five seconds.
- `HEALTHY`: no supported problem has been confirmed. It is not a guarantee that all media paths work.
- `DEGRADED`: one or more of these causes are confirmed:

| Condition | Evidence |
|---|---|
| `VIDEO_CPU_LIMITED` | The same outgoing video RTP stream consistently reports `qualityLimitationReason=cpu`. |
| `HIGH_PACKET_LOSS` | Recent inbound packet loss is at least 5%; recovery needs at most 2%. Values in between keep the state and restart the confirmation window. |
| `AUDIO_PACKETS_WITHOUT_PCM` | The same received audio RTP stream gains packets while no PCM callbacks reach its attached `onAudio` sinks. |

`AUDIO_PACKETS_WITHOUT_PCM` observes what reaches the sinks, before the application's queues and handlers. It does not diagnose speakers or slow application handlers.

`TURN_IN_USE` is reported at once, as information, when either selected candidate on any transport is a relay. It does not make a peer degraded. `TransportStats.relayed()` also tells this, while `candidateType()` still describes the local candidate.

How a condition is confirmed, the audio statistics and what the JSON export holds are described under [Details](#confirming-a-condition).

## Checking Before a Call

Diagnostics watch a call that is under way. `checkCall` answers the question before it: am I ready for the call? It checks, all at the same time, that:

- the camera delivers frames,
- the microphone delivers audio that is neither silent nor clipped,
- a connection to a test peer comes up.

```java
LocalCamera camera = rtc.media().camera();
LocalAudio microphone = rtc.media().microphone();

CallCheck check = CallCheck.builder()
        .video(camera)
        .audio(microphone)                 // ask the user to say something meanwhile
        .network(PeerOptions.builder()
                .iceServer("turn:turn.example.com", "user", "secret")
                .relayOnly(true)           // only a relayed connection counts
                .signaling(testPeerSignaling)
                .build())
        .build();

rtc.checkCall(check).thenAccept(result -> {
    if (result.isReady()) {
        System.out.println("Ready: " + result);
    }
    result.problems().forEach(problem -> System.out.println(problem.reason()));
});
```

Every part is optional, but a check needs at least one. Each part waits up to the check's `timeout` (10 seconds by default) for the first frame, the first audio or the connection. It then measures for the check's `duration` (3 seconds by default).

The future completes on an event thread of the runtime's local media, so the [rule against `join()`](/guide/client/threads#don-t-wait-in-a-handler) holds there. The tracks stay the application's: the check neither closes nor mutes them, and the same tracks can go into the call once it is done.

| Problem | Found when |
|---|---|
| `NO_VIDEO` | The video track delivered no frame within the timeout. |
| `DARK_VIDEO` | No frame had an average luma above 8% of white: a covered lens, a closed privacy shutter or no light. |
| `NO_AUDIO` | The audio track delivered no audio within the timeout. |
| `SILENT_AUDIO` | The audio never rose above -50 dBFS: a muted microphone, or nobody spoke. |
| `CLIPPED_AUDIO` | More than 0.1% of the samples were at full scale (99% of it or above): the input is too loud and distorts. |
| `NO_CONNECTION` | No connection to the test peer came up within the timeout. `network().failure()` tells why: a `TimeoutException`, or the failure of the peer. |

For `DARK_VIDEO`, the brightest frame counts, since a camera's first frames are often dark while its exposure settles. A muted track sends black frames, so it is dark too.

Besides the problems, the result holds the measurements behind them:

- **Video:** frames, frame rate, resolution and brightness.
- **Audio:** how much audio arrived, its peak and RMS level, and the share of clipped samples.
- **Network:** the time it took to connect, the round trip time, the local candidate type, and whether the connection is relayed.

`isReady()` means that no part found a problem, not that the call is sure to work.

Whether the network lets a call through cannot be told from the local configuration, only from a connection that is made. The network part therefore connects to a real test peer. That is one the application runs for this, such as an echo bot behind its own signaling, or any other peer that answers the offer.

The check creates a peer with the given options, and opens a data channel so that the peer offers. Once the connection is up, the check reads its statistics and closes the peer. With `relayOnly(true)`, the connection can only go through the TURN servers of the options, so a connection proves that they work. Without it, a direct connection may come up instead.

How the microphone is checked without being heard is described under [Details](#how-the-microphone-is-checked).

## Details

### Separate Transports

A peer bundles its media on one transport by default. If the remote side refuses to bundle, each m-line has a transport of its own. `transport()` is then the one with a candidate pair in use that carried the most bytes, sent and received. Of transports that carried as many, it is the one with the lowest id. Read `transports()` to watch each connection by itself.

### Confirming a Condition

Each problem, and its recovery, needs at least three distinct measurements spanning at least three seconds. Polling once a second is recommended; cached reports do not advance the window.

- A missing stream or required measurement, a changed set of sinks, or a reset audio counter discards that stream's evidence, instead of guessing.
- Gaps between samples longer than five seconds, and timestamps that go backwards, discard the evidence gathered over time.
- Diagnostics do not add audio sinks. Without a subscription of the application, whether PCM arrives is unknown.
- Silence (audio level zero) alone is not a fault.

### Audio Statistics

- `AudioStats.jitter()` is a `Duration`.
- `audioLevel()` is linear, from 0 to 1.
- `concealedSamples()`, `concealmentEvents()` and `totalSamplesReceived()` are cumulative counters.
- The outbound level is read from the media source when available. Measurements that exist only for received audio remain null on outbound audio.
- `pcmCallbacks()` counts sink callbacks across subscriptions, so several subscriptions can count the same audio more than once.

### The JSON Export

The JSON export includes the confirmation policy and explanations of the causes. It uses schema version 1 and an explicit allowlist, which includes a bounded set of codec MIME names; unknown names become `other`. Missing measurements become JSON null.

It leaves out raw statistics, identifiers of tracks, streams, transports and candidates, addresses, ports, URLs, ICE credentials, SDP, certificates and codec parameters.

The raw `report()` and its string representation are not sanitized like the export.

### How the Microphone Is Checked

Audio is measured as the track sends it, after audio processing. WebRTC records a microphone only while it sends it. For a microphone, the check therefore sends the track to a peer of its own, in the same runtime, which drops every encoded frame before it goes out. Nothing is played back, and the user does not hear themselves.

That peer, and the one of the network check, show among `rtc.peers()` while the check runs. They are closed before the result goes out.
