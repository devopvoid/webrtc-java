# Peers and Signaling

`rtc.createPeer(options)` creates a connection to one remote peer. It is the one call of the client API that waits, briefly, for the connection to be created, so that a configuration WebRTC refuses fails right there with a `ConfigurationException`.

```java
Peer peer = rtc.createPeer(PeerOptions.builder()
        .name("alice")
        .iceServer("stun:stun.l.google.com:19302")
        .iceServer("turn:turn.example.com:3478", "user", "secret")
        .polite(true)
        .signaling(message -> socket.send(message.toJson()))
        .build());
```

| Method | What it sets |
|---|---|
| `name(String)` | The name in thread names and error messages. |
| `iceServer(String...)` | A STUN or TURN server without credentials. |
| `iceServer(String, String, String)` | A TURN server with a user name and password. |
| `relayOnly(boolean)` | Connects through TURN servers only, which hides this side's addresses. |
| `signaling(Signaling)` | What carries messages to the remote peer. Required unless negotiation is manual. |
| `polite(boolean)` | The role in perfect negotiation, see below. |
| `negotiation(Negotiation)` | `AUTOMATIC` by default, or `MANUAL`, see [Manual Negotiation](#manual-negotiation). |
| `receive(MediaKind...)` | Receives audio or video without sending any, as a viewer does. |
| `gatheringTimeout(Duration)` | How long `Gathering.COMPLETE` waits for ICE gathering, 3 seconds by default. See [Manual Negotiation](#manual-negotiation). |
| `channelSendLimit(long)` | How many bytes each data channel may hold waiting to be sent, 16 MiB by default. See [Data Channels](/guide/client/data-channels). |
| `channelReceiveLimit(long)` | How many bytes of received messages each data channel may hold that its handlers are not done with, 16 MiB by default. See [Data Channels](/guide/client/data-channels). |
| `configure(Consumer<RTCConfiguration>)` | Anything else the [peer connection configuration](/guide/networking/peer-connection-config) has. |

## Signaling

The library has no transport of its own. A `Signaling` sends a peer's messages to the remote peer, and messages from the remote peer are handed to `peer.signal(...)`, in the order they were sent:

```java
// Outgoing: called on the peer's event thread, in order.
Signaling signaling = message -> socket.send(message.toJson());

// Incoming: from any thread.
socket.onText(text -> peer.signal(SignalMessage.fromJson(text)));
```

A transport class can implement both directions by overriding `Signaling.attach(Consumer<SignalMessage>)`, which the peer calls once when it is created, with the consumer to push received messages into. It returns a `Subscription` that removes that consumer, and only that one: the peer closes it when it closes, after its last `send`, on its event thread. The transport itself stays open, so a WebSocket can outlive the peer and take the next one, or serve several peers at once. A transport that keeps the consumer must drop it there, or the closed peer and the runtime with it stay reachable:

```java
@Override
public synchronized Subscription attach(Consumer<SignalMessage> inbound) {
    this.inbound = inbound;

    return () -> {
        synchronized (this) {
            // A peer closed after the next one attached leaves that one in place.
            if (this.inbound == inbound) {
                this.inbound = null;
            }
        }
    };
}
```

Each end of `Signaling.loopback()` takes one peer at a time; once that peer has closed, the next one can attach, and receives what was sent to the end in between.

A `SignalMessage` is a session description (`OFFER`, `ANSWER`) or an ICE candidate (`CANDIDATE`). `toJson()` writes the shapes the browser API uses, so a JavaScript peer can pass them to `setRemoteDescription` and `addIceCandidate` as they are:

```json
{"type":"offer","sdp":"v=0\r\no=- 4611731400430051336 2 IN IP4 127.0.0.1\r\n..."}
{"candidate":"candidate:842163049 1 udp 1677729535 203.0.113.7 49203 typ srflx raddr 0.0.0.0 rport 0","sdpMid":"0","sdpMLineIndex":0}
```

`SignalMessage.fromJson(String)` reads these, what a browser's `JSON.stringify(pc.localDescription)` and `JSON.stringify(candidate)` write, and the `{"description": ...}` and `{"candidate": {...}}` envelopes of the perfect negotiation examples. What it rejects is listed under [Details](#parsing-signal-messages). Routing, such as room names or peer ids, belongs in an envelope of your own around this JSON.

A browser peer that only answers needs no more than this:

```js
socket.onmessage = async ({ data }) => {
    const message = JSON.parse(data);

    if (message.type) {
        await pc.setRemoteDescription(message);

        if (message.type === "offer") {
            await pc.setLocalDescription();
            socket.send(JSON.stringify(pc.localDescription));
        }
    }
    else if (message.candidate) {
        await pc.addIceCandidate(message);
    }
};

pc.onicecandidate = ({ candidate }) => candidate && socket.send(JSON.stringify(candidate));
```

A browser peer that adds tracks of its own sends offers too, and then has to implement perfect negotiation as well, as the [W3C example](https://w3c.github.io/webrtc-pc/#perfect-negotiation-example) does.

For tests and demos, `Signaling.loopback()` links two peers in the same process:

```java
SignalingPair link = Signaling.loopback();

Peer caller = rtc.createPeer(PeerOptions.builder().signaling(link.first()).build());
Peer callee = rtc.createPeer(PeerOptions.builder().signaling(link.second()).polite(true).build());
```

## Polite and Impolite Peers

With automatic negotiation, a peer sends an offer whenever its tracks or channels change. When both peers send one at the same moment, the **polite** peer drops its own offer and answers the other one, and the impolite peer ignores the offer it got. Exactly one of two peers must be polite. Peers are impolite by default, so set `polite(true)` on one side, for example on the peer that joined second.

When two impolite peers collide, neither gives way. An impolite peer that waited 5 seconds for an answer reports a `NegotiationException` that says to make one of them polite.

## Peer State

`peer.state()` returns the last state the connection reported:

| State | Meaning |
|---|---|
| `NEW` | Created, nothing negotiated yet. |
| `CONNECTING` | ICE and DTLS are establishing the connection. |
| `CONNECTED` | Media and data can flow. |
| `DISCONNECTED` | Connectivity was lost. It may come back by itself. |
| `FAILED` | The connection failed. `peer.restartIce()` or the configured recovery policy tries to recover it. |
| `CLOSED` | The peer was closed. This state is final. |

```java
peer.onStateChange(state -> statusLabel.setText(state.toString()));

peer.whenConnected().thenAccept(p -> System.out.println("Connected"));
peer.whenClosed().thenRun(() -> System.out.println("Closed"));
```

`onStateChange` calls a new handler with the current state first, so a handler added late still learns where the peer stands. `whenConnected()` fails if the connection fails or closes first.

## Automatic ICE Recovery

Automatic peers can opt into a `RecoveryPolicy`. Without one, the application still
calls `peer.restartIce()` itself. The policy waits through short disconnections,
restarts ICE on the same peer, and bounds retries with exponential backoff:

```java
RecoveryPolicy recovery = RecoveryPolicy.builder()
        .grace(Duration.ofSeconds(2))
        .maxAttempts(3)
        .backoff(Duration.ofSeconds(1), Duration.ofSeconds(30))
        .attemptTimeout(Duration.ofSeconds(15))
        .onEvent(event -> System.out.println(event.state() + " attempt " + event.attempt()))
        .build();

Peer peer = rtc.createPeer(PeerOptions.builder()
        .signaling(signaling)
        .recovery(recovery)
        .build());
```

`DISCONNECTED` starts the grace period; `FAILED` skips it. Repeated failure
notifications during an attempt do not start parallel restarts. Events are
`WAITING`, `ATTEMPTING`, `RETRYING`, `RECOVERED` and `EXHAUSTED`, delivered on the
peer's event executor. `attempt()` counts attempts in the current outage, starting
at one; a recovery during the grace period reports zero. `cause()` gives the last
failure on retry or exhaustion. Successful signaling alone does not count as
recovery: the peer must report `CONNECTED`. That resets the budget, including
after exhaustion. Exhaustion leaves the peer open and creates no further automatic
attempts until it connects again. `whenConnected()` keeps its existing first-connection
semantics; observe `RECOVERED` for later recovery.

Before an attempt, `iceServers(attempt -> completionStage)` can obtain fresh TURN
credentials asynchronously. It runs on the event executor and must return promptly;
the resulting list replaces **all** ICE servers, so include STUN servers that should
remain available. The library copies the supplied servers; failed, null or invalid
results fail the attempt. For example, with an application's asynchronous credential
service returning `CompletionStage<List<RTCIceServer>>`:

```java
RecoveryPolicy recovery = RecoveryPolicy.builder()
        .iceServers(attempt -> credentials.fetchIceServers())
        .onEvent(event -> {
            if (event.state() == RecoveryEvent.State.EXHAUSTED) {
                showReconnectAction(event.cause());
            }
        })
        .build();
```

ICE recovery requires working signaling. Reconnecting a WebSocket or refreshing
HTTP authorization is the application's separate responsibility. A policy does not
create a replacement peer or session. If the application chooses that fallback, it
must close the old session, attach handlers to the new peer, republish local tracks,
and handle new remote tracks and data channels explicitly; their old handles are
not transferred.

`Whip.to(endpoint).recovery(recovery)` and `Whep.from(endpoint).recovery(recovery)`
use the same policy with the session's ICE `PATCH` exchange. This replaces their
built-in restart rule for that session. Keep using `session.restartIce()` for a
manual HTTP restart; `session.peer().restartIce()` does not perform the HTTP
exchange. For a manually negotiated peer outside WHIP/WHEP, the application owns
recovery and its protocol-specific offer/answer exchange.

How attempts are timed and cancelled is described under [Details](#ice-recovery-timing).

## Manual Negotiation

SFUs and other servers dictate an offer and answer exchange of their own. A peer built with `Negotiation.MANUAL` leaves it to the application, through `peer.negotiator()`; `Whip` and `Whep` are built on it. Each step creates and applies a description in one go, and `Gathering.COMPLETE` waits until the description holds all local candidates, for servers that take no trickled candidates. A STUN or TURN server that cannot be reached, as on networks that block UDP, holds up the end of gathering for a long time, so `COMPLETE` waits at most for the peer's gathering timeout and then goes with the candidates it has. This WHEP client does by hand what `Whep` does:

```java
Peer viewer = rtc.createPeer(PeerOptions.builder()
        .negotiation(Negotiation.MANUAL)
        .receive(MediaKind.VIDEO, MediaKind.AUDIO)
        .build());

viewer.onTrack(track -> System.out.println("Receiving " + track.kind()));

viewer.negotiator().createOffer(Gathering.COMPLETE)
        .thenApplyAsync(offer -> http.postSdp(whepUrl, offer.sdp), ioPool)   // blocking HTTP off the event thread
        .thenCompose(sdp -> viewer.negotiator().applyAnswer(new RTCSessionDescription(RTCSdpType.ANSWER, sdp)))
        .thenCompose(applied -> viewer.whenConnected())
        .get(10, TimeUnit.SECONDS);
```

| Method | What it does |
|---|---|
| `createOffer()`, `createOffer(Gathering)` | Creates the local offer and applies it. |
| `answer(offer)`, `answer(offer, Gathering)` | Applies the remote offer, then creates the answer and applies it. |
| `applyAnswer(answer)` | Applies the remote answer to the local offer. |
| `addCandidate(candidate)` | Adds a remote candidate, or holds it until there is a remote description to add it to. The future completes once the candidate is added or held; a held candidate that WebRTC rejects later reaches the error handler. |

A manual peer with a `Signaling` still sends its local candidates through it. `peer.negotiator()` throws an `IllegalStateException` on a peer that negotiates automatically.

## Details

### Parsing Signal Messages

Anything else throws an `IllegalArgumentException` that says what is wrong and where, as does text longer than a MiB or nested deeper than 64 levels, so a message from the remote side cannot exhaust the stack. The JSON must be strict (RFC 8259): no raw line breaks or other control characters inside strings, no member name twice in one object, no numbers like `01`, `1.` or `1e999`. An `sdpMLineIndex` must be an integer from 0 to 65535; `1.9` is rejected rather than truncated to 1.

### ICE Recovery Timing

The attempt deadline covers credentials, signaling and waiting for connectivity.
An HTTP restart already in flight finishes or reaches its own operation timeout
before another starts. Late credentials and completions after recovery or `close()`
are ignored; the credential service owns cancellation of its requests. Closing the
peer or runtime cancels scheduled retries, including one whose timer has fired but
whose control task has not run yet. Event notifications already queued may still arrive.
