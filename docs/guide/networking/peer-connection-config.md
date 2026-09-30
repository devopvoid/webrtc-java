# Peer Connection Configuration

`RTCConfiguration` holds the settings a peer connection is created with: ICE servers and policies, how candidates are gathered, how ICE checks connections, and how media is protected. This guide covers the settings beyond the ICE servers, which most applications leave at their defaults, but which matter for fast connecting, restrictive networks, and mobile or multi-homed hosts.

Every setting described here is unset by default, which keeps WebRTC's default. `getConfiguration()` returns the values in effect, defaults included.

```java
RTCConfiguration config = new RTCConfiguration();
config.iceServers.add(stunServer);
config.iceCandidatePoolSize = 2;
config.continualGatheringPolicy = RTCContinualGatheringPolicy.GATHER_CONTINUALLY;

RTCPeerConnection peerConnection = factory.createPeerConnection(config, observer);
```

Some settings can be changed later with `setConfiguration()`, starting from `getConfiguration()`; those WebRTC does not allow to change make it throw. A configuration WebRTC rejects makes `createPeerConnection()` throw, with the reason in the message.

## Candidate Gathering

| Setting | Effect |
|---|---|
| `iceCandidatePoolSize` | Gathers this many candidates before a connection needs them, so that connecting is faster. Default 0. |
| `continualGatheringPolicy` | `GATHER_CONTINUALLY` keeps gathering after the first candidates, so that a network that comes up later gets candidates too, which lets a connection survive a network change. Default `GATHER_ONCE`. |
| `tcpCandidatePolicy` | `DISABLED` gathers no TCP candidates. Default `ENABLED`. |
| `candidateNetworkPolicy` | `LOW_COST` leaves out cellular networks. Default `ALL`. |
| `disableIpv6OnWifi`, `maxIpv6Networks` | Limit IPv6 candidates. Default: IPv6 on Wi-Fi allowed, at most 5 IPv6 networks. |
| `networkPreference` | A kind of network, e.g. `ETHERNET`, whose candidate pairs take precedence regardless of their priority. Default: none. |
| `vpnPreference` | Whether to use, avoid, prefer or require VPN connections. Default `DEFAULT`. |
| `surfaceIceCandidatesOnIceTransportTypeChanged` | Signals at once the candidates a change of `iceTransportPolicy` lets through. Default false. |

To restrict ports and interfaces, see [Port Allocator Configuration](/guide/networking/port-allocator-config).

## Connectivity Checks

These tune how often ICE checks candidate pairs and when it gives up on one, all in milliseconds. Shorter intervals notice failures sooner, at the cost of more traffic. WebRTC checks that they fit together; for example, the check interval under strong connectivity may not exceed `stableWritableConnectionPingInterval`.

`iceConnectionReceivingTimeout`, `iceBackupCandidatePairPingInterval`, `iceCheckIntervalStrongConnectivity`, `iceCheckIntervalWeakConnectivity`, `iceCheckMinInterval`, `iceUnwritableTimeout`, `iceUnwritableMinChecks` (a count), `iceInactiveTimeout`, `stunCandidateKeepaliveInterval`, `stableWritableConnectionPingInterval`.

`prioritizeMostLikelyIceCandidatePairs` checks the pairs most likely to work first, usually those through TURN, and `enableIceRenomination` offers ICE renomination, which lets the controlling end switch the selected pair.

## TURN

| Setting | Effect |
|---|---|
| `presumeWritableWhenFullyRelayed` | Presumes TURN-to-TURN pairs work before a check succeeds, so that DTLS starts at once, which speeds up connecting through TURN. |
| `turnPortPrunePolicy` | Prunes TURN ports: `PRUNE_BASED_ON_PRIORITY` or `KEEP_FIRST_READY` per network. Default `NO_PRUNE`. |
| `turnLoggingId` | An identifier sent to TURN servers, to tie their logs to the application's. |

## Media

| Setting | Effect |
|---|---|
| `enableDscp` | Marks media packets with DSCP values. Default true. |
| `enableCpuAdaptation` | Lowers the resolution or frame rate of video when the CPU is overused. Default true. |
| `suspendBelowMinBitrate` | Stops sending video when the bitrate falls below its minimum. Default false. |
| `screencastMinBitrate` | The bitrate screen share video is padded up to, in kbps. Default 100. |

## Security

`cryptoOptions` selects the SRTP cipher suites a peer connection offers. A new `RTCCryptoOptions` holds WebRTC's defaults, to change from:

```java
RTCCryptoOptions crypto = new RTCCryptoOptions();
crypto.preferGcmCryptoSuites = true;
crypto.cryptexPolicy = RTCCryptexPolicy.NEGOTIATE;

config.cryptoOptions = crypto;
```

`cryptexPolicy` encrypts the RTP header extensions and CSRCs as a whole (RFC 9335) where the peer supports it (`NEGOTIATE`) or always (`REQUIRE`).

## Signaling

| Setting | Effect |
|---|---|
| `offerExtmapAllowMixed` | Allows one- and two-byte header extensions to be mixed in offers. Default true. |
| `enableImplicitRollback` | Rolls a pending local offer back when a remote offer arrives, as perfect negotiation needs. Default false. |
| `alwaysNegotiateDataChannels` | Includes data channels in offers before any is created. Default false. |

## Related API

- `RTCConfiguration` — the configuration of a peer connection.
- `RTCPeerConnection.getConfiguration()`, `setConfiguration()` — read and change it.
- `RTCCryptoOptions` — the SRTP cipher suites.
- `PortAllocatorConfig` — ports and interfaces, see [Port Allocator Configuration](/guide/networking/port-allocator-config).
