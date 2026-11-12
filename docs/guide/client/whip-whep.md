# WHIP and WHEP

Media servers and streaming services take streams with the WebRTC HTTP Ingestion Protocol ([WHIP](https://www.rfc-editor.org/rfc/rfc9725)) and hand them out with its egress counterpart, WHEP. Both exchange one offer and one answer over HTTP. `Whip` publishes tracks, and `Whep` receives a stream:

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

By default, the offer goes to the endpoint at once, before any candidate is gathered, and the future completes once the peer is connected. If the endpoint refuses the offer, or the peer does not connect within the timeout (15 seconds by default), the future fails, and the session is deleted on the server again. Cancelling the future, say once `get` gave up waiting, ends the session the same way. `onTrack` hands over tracks that already arrived, so a handler added once the future completed misses none. Retries and counter-offers are described under [Details](#retries-and-counter-offers).

## Candidates

With the default `Gathering.TRICKLE`, only once the endpoint answered does the peer gather its candidates, with the STUN and TURN servers the endpoint names in `Link` headers with `rel="ice-server"`, unless `iceServer(...)` or `turnServer(...)` names servers of its own, which take precedence. The candidates follow the offer by `PATCH` to the session, as `application/trickle-ice-sdpfrag`, a few at a time and with the session's `ETag` as `If-Match`, as RFC 9725 describes.

For an endpoint without Trickle ICE, use `.gathering(Gathering.COMPLETE)` to include gathered candidates in the initial POST. It waits at most three seconds for gathering, within the overall session timeout, then sends the candidates available so far; any later candidates may still follow by PATCH. Configure STUN/TURN servers locally in this mode, since the endpoint's Link headers arrive after gathering. For example:

```java
Whep.Session viewer = Whep.from(endpoint)
        .turnServer("turn:turn.example.com:3478", turnUsername, turnPassword)
        .gathering(Gathering.COMPLETE)
        .play(rtc)
        .get(15, TimeUnit.SECONDS);
```

Both builders support these methods. `turnServer` is separate from `iceServer(String...)`, so passing three URLs to `iceServer` still means three URLs. The gathering setting controls the initial POST only; ICE restarts continue to use PATCH and trickled candidates.

How the session treats an endpoint that refuses candidates is described under [Details](#trickle-ice-responses).

## Codecs

By default, the offer holds every codec WebRTC has, in WebRTC's order, and the endpoint picks; one that follows the offer's order picks VP8 for video. Endpoints that pass the stream on unchanged, to HLS or a recording, often take only H.264 and Opus. `videoCodecs(...)` and `audioCodecs(...)` offer only the codecs named, in that order of preference, by name (`H264`, `VP8`, `VP9`, `AV1`, `opus`, `G722`, ...) or MIME type (`video/H264`), whatever the case. Retransmission, redundancy, FEC, comfort noise and DTMF go along. For WHEP, they decide what the endpoint sends, also in the answer to a counter-offer. A name WebRTC has no codec of fails the session with a `ConfigurationException` before the offer goes out.

## Endpoints That Require Video

Some endpoints, Amazon IVS among them, refuse a WHIP offer without video, even for audio alone. `alwaysOfferVideo()` then adds video that only sends, in the stream of the tracks, without a track, so that nothing is sent on it; with a video track, it changes nothing:

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

`simulcast()` sends the video track of a WHIP session in three layers, as simulcast (RFC 8853), in full, half and quarter resolution, with the rids `f`, `h` and `q`. The offer negotiates them, as RFC 9725 asks, and an endpoint that forwards the stream can then give each viewer the layer its bandwidth takes. `simulcast(Encoding...)` takes two or three layers of its own, the most WebRTC sends, from the largest down, with their resolution factor, bitrate and frame rate. An endpoint that takes no simulcast leaves it out of its answer and gets the first layer only, which is why the largest comes first. Simulcast needs a video track; `Sender.configure` on its sender changes every layer alike.

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

## Errors

If an endpoint answers with an error, the message of the failure names the status, and what the endpoint says of it: the title and detail of problem details (RFC 9457, `application/problem+json`), as in `status 403: Forbidden: The stream key has expired`, or else the start of the body. The failure is an `HttpStatusException`, whose `status()`, `title()`, `detail()` and `problemType()` give the same to a program; for a deletion or for candidates, it is the cause of the failure reported. As RFC 9725 asks, read no specific cause into a status beyond its class, since endpoints use them differently:

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

When the connection fails, or stays disconnected for two seconds, the session restarts ICE: the peer gathers new candidates, and only the new ICE username fragment and password go to the session, by `PATCH` with `If-Match: *`, while the media go on as negotiated. The session's answer, with status 200 and Content-Type `application/trickle-ice-sdpfrag`, brings its own ICE information and a new `ETag`. Its candidate set replaces the old one completely, including when it is empty. Three restarts in a row that do not bring the connection back, or an endpoint that takes no restarts, end the attempts, which the error handler hears of. `session.restartIce()` restarts ICE at once, say after the network changed; a restart that fails leaves the ICE session before as it was. Restarts also work after a WHEP counter-offer; the player keeps its answerer role and updates only the ICE information.

## Authentication

Servers that ask for a user and password rather than a token, such as MediaMTX, take them by HTTP Basic authentication (RFC 7617): `basicAuth("user", "password")` sends `Authorization: Basic` with both, in UTF-8, on every request of the session, without waiting to be asked. The user must not contain a colon. Of `bearerToken` and `basicAuth`, the one called last counts. MediaMTX also takes them as a token, the way OBS Studio sends them, `bearerToken("user:password")`. Other schemes, such as Digest, are not supported.

How requests reach the endpoint, over `https`, redirects and proxies, is described under [Details](#https-and-redirects).

## Detecting a WHEP Endpoint

`Whep.from(url).detect(rtc)` asks a URL with `HEAD` whether it is a WHEP endpoint, as a player may before it plays a URL it was given (draft-ietf-wish-whep-05, 4.2). Its future holds `true` when the endpoint answers 200 with the Content-Type `application/sdp` and a `Link` to the profile `urn:ietf:params:whep`; the Content-Type alone does not count. It holds `false` for any other answer, such as that of an endpoint that takes no `HEAD`, and fails when the request does. The request carries the credentials of the builder, takes its timeout at most, and follows the redirects the offer follows:

```java
if (Whep.from(url).detect(rtc).get(15, TimeUnit.SECONDS)) {
    Whep.Session viewer = Whep.from(url).play(rtc).get(15, TimeUnit.SECONDS);
}
```

## Closing a Session

`session.close()` deletes the session on the server, which frees what the server holds for it, and closes the peer. Both start at once, so a slow server does not keep the peer open, and the deletion takes at most the session's timeout. The tracks a WHIP session published stay open. `session.peer()` is a regular peer, for stats and anything else. A deletion the server refuses or that fails goes to the runtime's error handler, and also fails the future of `closeAsync()`, which completes once both are done; a peer that did not close fails it too.

## Builder Methods

| Builder method | What it sets |
|---|---|
| `bearerToken(String)` | The token sent as `Authorization: Bearer`; for many services, the stream key. |
| `basicAuth(String, String)` | A user and password sent as `Authorization: Basic`, as MediaMTX takes them; replaces a bearer token, and the other way round. |
| `iceServer(String...)` | A STUN or TURN server without credentials, overriding endpoint-provided servers. |
| `turnServer(String, String, String)` | A TURN URL, username and password, overriding endpoint-provided servers. |
| `gathering(Gathering)` | When the initial offer is sent: `TRICKLE` by default, or `COMPLETE` to gather first. |
| `track(LocalTrack)` | A track to publish, WHIP only. The tracks share one stream, which holds one audio and one video track at most. |
| `receive(MediaKind...)` | What to receive, WHEP only; video and audio by default. |
| `videoCodecs(String...)`, `audioCodecs(String...)` | The only codecs to negotiate, in order of preference; every codec WebRTC has by default. |
| `alwaysOfferVideo()` | Video in the offer even without a video track, WHIP only, for endpoints that refuse an offer without video. |
| `simulcast()`, `simulcast(Encoding...)` | The video track in layers, as simulcast, WHIP only: three by default (`f`, `h`, `q`), or two or three given, the largest first. |
| `sendTransform(RTCEncodedFrameTransformer)` | The [encoded transform](/guide/client/transforms) of every frame sent, WHIP only, in place before the offer goes out. |
| `e2ee(KeyProvider)` | [End-to-end encryption](/guide/advanced/end-to-end-encryption) of every frame sent (WHIP) or received (WHEP), in place of the transforms. |
| `receiveTransform(RTCEncodedFrameTransformer)` | The [encoded transform](/guide/client/transforms) of every frame received, WHEP only, in place before the first frame, also for a counter-offer. |
| `timeout(Duration)` | How long the session may take to be connected, and its deletion to be answered. |

## Details

### Offers

As RFC 9725 asks, the peer bundles all media on one transport (`max-bundle`), the descriptions it sends mark each m-line `rtcp-mux-only`, and a WHIP offer only sends (`sendonly`).

### Retries and Counter-Offers

An endpoint that is busy, with status 503, or a WHEP endpoint whose stream has yet to start, with status 409, gets the offer again after the wait its `Retry-After` names, at least a second, as long as that fits within the timeout; without `Retry-After` the session fails at once.

A WHEP endpoint may also reject the offer and counter it with one of its own, with status 406: `Whep` then takes its offer back, answers the endpoint's, receiving only, and sends the answer by `PATCH` to the session the endpoint named, before the counter-offer's `valid-until`, 30 seconds by default. The answer rejects the audio or video of the counter-offer that `receive(...)` leaves out, and the session fails if the counter-offer holds none of what it asks for. Data channels of a counter-offer are accepted.

### Trickle ICE Responses

An endpoint that takes no such `PATCH`, with status 405, 415, 422 or 501, gets no more; it may still learn peer-reflexive candidates from incoming connectivity checks, but that does not cover every network topology. A 412 or 428 for the current ICE generation reaches the error handler and stops candidate PATCHes; responses to a generation replaced by a restart are ignored.

### Responses

A normal SDP answer must have status 201, a nonempty session `Location`, and Content-Type `application/sdp` (parameters are allowed). Invalid responses fail the session; a resource already named by the server is deleted even when its SDP response cannot be used.

### Timeouts

The timeout bounds the requests as a whole, not each read, so a server that sends its answer a byte at a time cannot stretch it. A request still out when the session times out or is cancelled is cut off at once; one still waiting for the head of its answer only from Java 16 on, and before that at its timeout. Once the server has named the session, in the head of its answer, it is deleted all the same; a session the server created for an answer that had not come yet is left for the server to time out.

### URLs in Failures

Failures and reports name the URL of an endpoint or session by its scheme, host and port only, since the rest may be a secret: services take stream keys and signatures in the query, and some, such as Cloudflare Stream, in the path. A failure can go to a log as it is, apart from what the endpoint itself says in an error, which it passes on unchanged.

### HTTPS and Redirects

Endpoints have to be `https` URLs, as WHIP and WHEP ask, except on the machine the application runs on: there plain `http` will do, for a media server beside it, at `localhost`, an address in 127.0.0.0/8 or `::1`. A name that merely resolves to one of them does not count, since it may resolve elsewhere later. The same holds for the session an endpoint names and for where a redirect points.

The credentials of a session, its bearer token or its Basic user and password, go to the endpoint, and along with the deletion to the session the endpoint names in its `Location` header. That session may be on another host, but an `https` endpoint cannot name a plain `http` one. The offer follows redirects with status 307 and 308, as WHIP and WHEP ask of clients, such as those of a load balancer: up to five of them, all within the timeout, and the credentials go along. A redirect from `https` to plain `http` fails the session, and so does one with status 301, 302 or 303, which would turn the offer into a `GET`. The deletion follows no redirect; one fails it with its status.

### Proxies

Requests go through Java's `HttpClient`, with the default `SSLContext` and the HTTP proxy Java is configured with, the first the default `ProxySelector` names: `-Dhttps.proxyHost` and `-Dhttps.proxyPort`, `-Dhttp.proxyHost`, or `-Djava.net.useSystemProxies=true`. `HttpClient` takes no SOCKS proxies. A proxy that asks for credentials gets Basic ones from the default `Authenticator`, but only for sessions without credentials of their own: `HttpClient` drops the Authorization header of a request once it answers for credentials itself. As in Java, Basic is not used for the tunnel of an `https` request unless `jdk.http.auth.tunneling.disabledSchemes` allows it. Other schemes, such as Digest, NTLM and Negotiate, are not supported.

### Protocol Extensions

`session.extensions()` gives the protocol extensions the endpoint offers for the session (RFC 9725, 4.9): the URL of each by its relation type, such as `urn:ietf:params:whip:ext:example:server-sent-events` (`urn:ietf:params:whep:ext:...` for WHEP), as the endpoint named them in `Link` header fields of its answer, resolved against it. Of a relation type named twice, the first counts. Links of other relation types are ignored, as WHIP and WHEP ask, apart from `ice-server`. The session uses none of the extensions itself; an application uses those it knows, and the map is empty if the endpoint named none.
