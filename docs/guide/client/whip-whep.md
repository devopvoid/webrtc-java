# WHIP and WHEP

Media servers and streaming services take streams over WHIP, the WebRTC HTTP Ingestion Protocol ([RFC 9725](https://www.rfc-editor.org/rfc/rfc9725)), and hand them out over its counterpart WHEP. Both exchange one offer and one answer over HTTP. `Whip` publishes tracks, and `Whep` receives a stream:

```java
Whip.Session live = Whip.to(URI.create("https://example.com/live/whip"))
        .bearerToken(streamKey)
        .track(rtc.media().camera())
        .track(rtc.media().microphone())
        .publish(rtc)
        .get(15, TimeUnit.SECONDS);

Whep.Session viewer = Whep.from(URI.create("https://example.com/live/whep"))
        .play(rtc)
        .get(15, TimeUnit.SECONDS);

viewer.peer().onTrack(track -> System.out.println("Receiving " + track.kind()));
```

## Connecting

By default, `publish` and `play` send the offer to the endpoint at once, before any candidate is gathered. Their future completes once the peer is connected.

The future fails if the endpoint refuses the offer, or if the peer does not connect within the timeout (15 seconds by default). The session is then deleted on the server again. Cancelling the future, for example after `get` gave up waiting, ends the session the same way.

`onTrack` also hands over tracks that arrived before the handler was added. A handler added after the future completed misses none.

An endpoint may ask for the offer again later, and a WHEP endpoint may answer with an offer of its own. [Details](#retries-and-counter-offers) describes both.

## Candidates

By default (`Gathering.TRICKLE`), the peer gathers its candidates only after the endpoint has answered. It then sends them to the session a few at a time, by `PATCH`.

The peer uses the STUN and TURN servers that the endpoint names in its `Link` headers (`rel="ice-server"`). If you set servers with `iceServer(...)` or `turnServer(...)`, those are used instead.

An endpoint without Trickle ICE needs the candidates in the first request. `gathering(Gathering.COMPLETE)` puts them there:

```java
Whep.Session viewer = Whep.from(endpoint)
        .turnServer("turn:turn.example.com:3478", turnUsername, turnPassword)
        .gathering(Gathering.COMPLETE)
        .play(rtc)
        .get(15, TimeUnit.SECONDS);
```

- The peer waits up to three seconds for gathering, within the session's timeout, then sends the candidates it has. Later ones may still follow by `PATCH`.
- Set the STUN and TURN servers yourself in this mode. The endpoint's `Link` headers arrive only after gathering.
- The gathering mode affects only the first request. ICE restarts always use `PATCH` and trickled candidates.

Both builders have `turnServer` and `gathering`. `turnServer` takes one TURN URL with its user name and password. `iceServer(String...)` takes URLs only, so three arguments are three URLs.

How the session treats an endpoint that refuses candidates is described under [Details](#trickle-ice).

## Codecs

By default, the offer holds every codec WebRTC has, in WebRTC's order, and the endpoint picks. An endpoint that follows the offer's order picks VP8 for video.

Endpoints that pass the stream on unchanged, for example to HLS or into a recording, often take only H.264 and Opus. `videoCodecs(...)` and `audioCodecs(...)` offer only the codecs you name, in your order of preference:

- Name a codec by its name (`H264`, `VP8`, `VP9`, `AV1`, `opus`, `G722`, ...) or by its MIME type (`video/H264`). Case does not matter.
- Retransmission, redundancy, FEC, comfort noise and DTMF stay in the offer.
- For WHEP, the codecs decide what the endpoint sends. This also holds for the answer to a counter-offer.
- A name WebRTC has no codec for fails the session with a `ConfigurationException`, before the offer goes out.

## Endpoints That Require Video

Some endpoints, Amazon IVS among them, refuse a WHIP offer without video, even when only audio is sent. `alwaysOfferVideo()` then adds send-only video to the offer, in the stream of the tracks. It has no track, so nothing is sent on it. With a video track, `alwaysOfferVideo()` changes nothing:

```java
Whip.Session live = Whip.to(URI.create("https://example.com/live/whip"))
        .bearerToken(token)
        .videoCodecs("H264")
        .audioCodecs("opus")
        .track(rtc.media().microphone())
        .alwaysOfferVideo()
        .publish(rtc)
        .get(15, TimeUnit.SECONDS);
```

## Simulcast

`simulcast()` sends the video track of a WHIP session in three layers, as simulcast (RFC 8853): full, half and quarter resolution, with the rids `f`, `h` and `q`. An endpoint that forwards the stream can then give each viewer the layer its bandwidth allows. The offer negotiates the layers, as RFC 9725 asks.

`simulcast(Encoding...)` takes two or three layers of your own, each with its resolution factor, bitrate and frame rate. WebRTC sends at most three. List the largest first: an endpoint without simulcast leaves it out of its answer and gets only the first layer.

```java
Whip.Session live = Whip.to(URI.create("https://example.com/live/whip"))
        .bearerToken(token)
        .track(rtc.media().camera())
        .simulcast(
                Encoding.builder().maxBitrate(2_500_000).build(),
                Encoding.builder().scaleDownBy(2).maxBitrate(800_000).build(),
                Encoding.builder().scaleDownBy(4).maxBitrate(200_000).build())
        .publish(rtc)
        .get(15, TimeUnit.SECONDS);
```

Simulcast needs a video track. `Sender.configure` on its sender changes every layer alike.

## Errors

If the endpoint answers with an error status, the session fails with an `HttpStatusException`. Its message names the status and what the endpoint says about it, for example `status 403: Forbidden: The stream key has expired`:

- With problem details (RFC 9457, `application/problem+json`), the message holds their title and detail.
- Without them, it holds the start of the body.

`status()`, `title()`, `detail()` and `problemType()` give the same to a program. If a deletion or a candidate request fails, the reported failure has the `HttpStatusException` as its cause.

As RFC 9725 asks, read no specific cause into a status beyond its class, since endpoints use statuses differently:

```java
try {
    session = Whip.to(url).bearerToken(key).track(camera).publish(rtc).get(15, TimeUnit.SECONDS);
}
catch (ExecutionException e) {
    if (e.getCause() instanceof HttpStatusException) {
        HttpStatusException refused = (HttpStatusException) e.getCause();
        System.err.println("Refused, " + refused.status() + ": " + refused.detail());
    }
}
```

## ICE Restarts

The session restarts ICE when the connection fails, or when it stays disconnected for two seconds. The media go on as negotiated; only the ICE information changes:

1. The peer gathers new candidates.
2. Its new ICE username fragment and password go to the session by `PATCH`, with `If-Match: *`.
3. The session answers with status 200 and Content-Type `application/trickle-ice-sdpfrag`. The answer brings the session's ICE information and a new `ETag`.
4. The candidates of the answer replace the old ones completely, even when there are none.

The attempts end after three restarts in a row that do not bring the connection back, or when the endpoint takes no restarts. The error handler hears of it.

`session.restartIce()` restarts ICE at once, for example after the network changed. A restart that fails leaves the previous ICE session as it was. Restarts also work after a WHEP counter-offer: the player stays the answering side and updates only the ICE information.

## Authentication

`bearerToken(...)` sends a token as `Authorization: Bearer`, for many services the stream key. Servers that want a user and password instead, such as MediaMTX, take them by HTTP Basic authentication (RFC 7617):

- `basicAuth("user", "password")` sends `Authorization: Basic` with both, in UTF-8, on every request of the session, without waiting to be asked.
- The user must not contain a colon.
- Of `bearerToken` and `basicAuth`, the one called last counts.
- MediaMTX also takes them as a token, the way OBS Studio sends them: `bearerToken("user:password")`.
- Other schemes, such as Digest, are not supported.

Endpoints must be `https` URLs, except for a media server on the same machine. The rules for `https`, redirects and proxies are under [Details](#https-and-redirects).

## Detecting a WHEP Endpoint

A player that was given a URL may first ask whether it is a WHEP endpoint (draft-ietf-wish-whep-05, 4.2). `Whep.from(url).detect(rtc)` asks with a `HEAD` request:

```java
if (Whep.from(url).detect(rtc).get(15, TimeUnit.SECONDS)) {
    Whep.Session viewer = Whep.from(url).play(rtc).get(15, TimeUnit.SECONDS);
}
```

- The future holds `true` if the endpoint answers 200 with Content-Type `application/sdp` and a `Link` to the profile `urn:ietf:params:whep`. The Content-Type alone is not enough.
- It holds `false` for any other answer, for example from an endpoint that takes no `HEAD`.
- It fails if the request itself fails.

The request carries the builder's credentials, takes at most the builder's timeout, and follows the same redirects as the offer.

## Closing a Session

`session.close()` deletes the session on the server, which frees what the server holds for it, and closes the peer. Both start at once, so a slow server does not keep the peer open. The deletion takes at most the session's timeout.

The tracks a WHIP session published stay open. `session.peer()` is a regular peer, for statistics and anything else.

`closeAsync()` returns a future that completes once the deletion and the peer's close are both done. It fails if the server refuses the deletion, if the deletion fails, or if the peer does not close. A deletion that the server refuses or that fails also goes to the runtime's error handler.

## Builder Methods

| Builder method | What it sets |
|---|---|
| `bearerToken(String)` | The token sent as `Authorization: Bearer`; for many services, the stream key. |
| `basicAuth(String, String)` | A user and password sent as `Authorization: Basic`. Replaces a bearer token, and the other way round. |
| `iceServer(String...)` | A STUN or TURN server without credentials, in place of the endpoint's servers. |
| `turnServer(String, String, String)` | A TURN URL, user name and password, in place of the endpoint's servers. |
| `gathering(Gathering)` | When the first offer is sent: `TRICKLE` by default, or `COMPLETE` to gather first. |
| `track(LocalTrack)` | A track to publish, WHIP only. The tracks share one stream, of one audio and one video track at most. |
| `receive(MediaKind...)` | What to receive, WHEP only; video and audio by default. |
| `videoCodecs(String...)`, `audioCodecs(String...)` | The only codecs to negotiate, in order of preference; every codec WebRTC has by default. |
| `alwaysOfferVideo()` | Video in the offer even without a video track, WHIP only. |
| `simulcast()`, `simulcast(Encoding...)` | The video track in simulcast layers, WHIP only: three by default (`f`, `h`, `q`), or two or three given, the largest first. |
| `sendTransform(RTCEncodedFrameTransformer)` | The [encoded transform](/guide/client/transforms) of every frame sent, WHIP only, in place before the offer goes out. |
| `e2ee(KeyProvider)` | [End-to-end encryption](/guide/advanced/end-to-end-encryption) of every frame sent (WHIP) or received (WHEP), in place of the transforms. |
| `receiveTransform(RTCEncodedFrameTransformer)` | The [encoded transform](/guide/client/transforms) of every frame received, WHEP only, in place before the first frame, also for a counter-offer. |
| `timeout(Duration)` | How long the session may take to connect, and its deletion to be answered. |

## Details

### Offers

As RFC 9725 asks:

- the peer bundles all media on one transport (`max-bundle`),
- the descriptions it sends mark each m-line `rtcp-mux-only`,
- a WHIP offer only sends (`sendonly`).

### Retries and Counter-Offers

Two statuses make the session send its offer again: 503, when the endpoint is busy, and for WHEP 409, when the stream has yet to start. The session waits as long as the answer's `Retry-After` says, at least a second, as long as that fits within the timeout. Without `Retry-After`, the session fails at once.

A WHEP endpoint may also reject the offer with status 406 and counter it with an offer of its own. `Whep` then:

1. takes its own offer back,
2. answers the endpoint's offer, receiving only,
3. sends the answer by `PATCH` to the session the endpoint named, before the counter-offer's `valid-until` (30 seconds by default).

The answer rejects the audio or video of the counter-offer that `receive(...)` leaves out. If the counter-offer holds nothing of what `receive(...)` asks for, the session fails. Data channels of a counter-offer are accepted.

### Trickle ICE

Candidates go to the session as `application/trickle-ice-sdpfrag`, with the session's `ETag` as `If-Match`, as RFC 9725 describes.

An endpoint that refuses them with status 405, 415, 422 or 501 gets no more. It may still learn peer-reflexive candidates from incoming connectivity checks, but that does not cover every network topology.

A 412 or 428 for the current ICE generation reaches the error handler and stops the candidate requests. Such answers for a generation that a restart replaced are ignored.

### Responses

An SDP answer must have status 201, a nonempty session `Location`, and Content-Type `application/sdp` (parameters are allowed). An invalid answer fails the session. A session the server already named is deleted, even when its SDP answer cannot be used.

### Timeouts

The timeout bounds each request as a whole, not each read. A server that sends its answer a byte at a time cannot stretch it.

When the session times out or is cancelled, requests still out are cut off at once. For a request still waiting for the head of its answer, that works only from Java 16 on; before that, the request ends at its timeout.

Once the server has named the session in the head of its answer, the session is deleted all the same. A session the server created for an answer that has not arrived yet is left for the server to time out.

### URLs in Failures

Failures and reports name the URL of an endpoint or session by its scheme, host and port only. The rest may be a secret: services take stream keys and signatures in the query, and some, such as Cloudflare Stream, in the path.

A failure can therefore go to a log as it is. The exception is what the endpoint itself says in an error, which is passed on unchanged.

### HTTPS and Redirects

Endpoints must be `https` URLs, as WHIP and WHEP ask. The same holds for the session an endpoint names and for redirect targets.

The exception is a media server on the same machine: there plain `http` works, at `localhost`, an address in 127.0.0.0/8, or `::1`. A name that merely resolves to one of them does not count, since it may resolve elsewhere later.

The credentials of a session, its bearer token or its Basic user and password, go to the endpoint. They also go, with the deletion, to the session the endpoint names in its `Location` header. That session may be on another host, but an `https` endpoint cannot name a plain `http` one.

Redirects:

- The offer follows redirects with status 307 and 308, as WHIP and WHEP ask of clients, for example behind a load balancer. It follows up to five of them, all within the timeout, and the credentials go along.
- A redirect from `https` to plain `http` fails the session.
- So does a redirect with status 301, 302 or 303, which would turn the offer into a `GET`.
- The deletion follows no redirect. A redirect fails it with its status.

### Proxies

Requests go through Java's `HttpClient`, with the default `SSLContext` and the HTTP proxy Java is configured with. That is the first proxy the default `ProxySelector` names, set by `-Dhttps.proxyHost` and `-Dhttps.proxyPort`, `-Dhttp.proxyHost`, or `-Djava.net.useSystemProxies=true`. `HttpClient` takes no SOCKS proxies.

A proxy that asks for credentials gets Basic ones from the default `Authenticator`, but only for sessions without credentials of their own. Once `HttpClient` answers for credentials itself, it drops the Authorization header of the request.

As elsewhere in Java, Basic is not used for the tunnel of an `https` request unless `jdk.http.auth.tunneling.disabledSchemes` allows it. Other schemes, such as Digest, NTLM and Negotiate, are not supported.

### Protocol Extensions

`session.extensions()` gives the protocol extensions the endpoint offers for the session (RFC 9725, 4.9), as the `Link` header fields of its answer name them. The map holds the URL of each extension by its relation type, such as `urn:ietf:params:whip:ext:example:server-sent-events` (`urn:ietf:params:whep:ext:...` for WHEP). Each URL is resolved against the URL that answered.

- Of a relation type named twice, the first counts.
- Links of other relation types are ignored, as WHIP and WHEP ask, apart from `ice-server`.
- The session uses none of the extensions itself. An application uses those it knows.
- The map is empty if the endpoint named none.
