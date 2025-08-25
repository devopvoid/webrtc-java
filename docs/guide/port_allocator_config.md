# Port Allocator Configuration (ICE) <!-- {docsify-ignore-all} -->

This guide explains how to configure the ICE port allocator using `dev.onvoid.webrtc.PortAllocatorConfig` and how to use it with `RTCConfiguration` when creating a peer connection.

The Port Allocator controls:
- The local ephemeral port range used for gathering ICE candidates (HOST, SRFLX, RELAY).
- Transport behavior via bit flags that mirror native WebRTC PortAllocator flags (e.g., disable TCP candidates, enable IPv6, etc.).

When you need to restrict the ports your application binds to (e.g., to satisfy firewall rules) or tweak which transport types are gathered, use `PortAllocatorConfig`.

## API Overview

`PortAllocatorConfig` exposes three fields:
- `minPort` (int): Minimum UDP/TCP port to use for candidate gathering (inclusive). Set to 0 to leave unspecified.
- `maxPort` (int): Maximum UDP/TCP port to use for candidate gathering (inclusive). Set to 0 to leave unspecified.
- `flags` (int): Bitwise OR of allocator flags (default 0).

Notes:
- If both `minPort` and `maxPort` are set to non‑zero values, `minPort` must be less than or equal to `maxPort`.
- A value of 0 for either `minPort` or `maxPort` means "not specified" and the native defaults are used.

Convenience methods are provided to toggle specific behaviors and to combine flags:
- `setFlag(int flag)`, `clearFlag(int flag)`, `isFlagEnabled(int flag)`
- Boolean helpers like `setDisableTcp(boolean)`, `isTcpDisabled()`, etc.

## Supported Flags

The following flags mirror WebRTC's native PortAllocator flags. You can use them directly via `setFlag/clearFlag` or through the boolean helpers.

- `PORTALLOCATOR_DISABLE_UDP` — Disable local UDP socket allocation for host candidates.
- `PORTALLOCATOR_DISABLE_STUN` — Disable STUN candidate gathering (server reflexive).
- `PORTALLOCATOR_DISABLE_RELAY` — Disable TURN relay candidate gathering.
- `PORTALLOCATOR_DISABLE_TCP` — Disable local TCP candidate gathering.
- `PORTALLOCATOR_ENABLE_IPV6` — Enable IPv6 support.
- `PORTALLOCATOR_ENABLE_SHARED_SOCKET` — Enable shared UDP socket mode (platform/stack‑dependent behavior).
- `PORTALLOCATOR_ENABLE_STUN_RETRANSMIT_ATTRIBUTE` — Include STUN retransmit attribute on requests.
- `PORTALLOCATOR_DISABLE_ADAPTER_ENUMERATION` — Do not enumerate network adapters.
- `PORTALLOCATOR_DISABLE_DEFAULT_LOCAL_CANDIDATE` — Do not generate a default local candidate.
- `PORTALLOCATOR_DISABLE_UDP_RELAY` — Disable UDP TURN relay.
- `PORTALLOCATOR_DISABLE_COSTLY_NETWORKS` — Avoid cellular/expensive networks for candidate gathering.
- `PORTALLOCATOR_ENABLE_IPV6_ON_WIFI` — Allow IPv6 over Wi‑Fi.
- `PORTALLOCATOR_ENABLE_ANY_ADDRESS_PORTS` — Allow binding to any‑address (0.0.0.0/::) ports.
- `PORTALLOCATOR_DISABLE_LINK_LOCAL_NETWORKS` — Avoid link‑local network interfaces.

## Basic Usage

You configure the port allocator on the `RTCConfiguration` before creating the `RTCPeerConnection`.

```java
RTCConfiguration cfg = new RTCConfiguration();

// Constrain ephemeral port range for HOST candidates
cfg.portAllocatorConfig.minPort = 48000;
cfg.portAllocatorConfig.maxPort = 48100;

// Example: Disable TCP candidates, keep UDP enabled (default)
cfg.portAllocatorConfig.setDisableTcp(true);

// Optional: Enable IPv6 support
// cfg.portAllocatorConfig.setEnableIpv6(true);

RTCPeerConnection pc = factory.createPeerConnection(cfg, observer);
```

## Using Flags Directly

You can combine flags using bitwise OR and set them at once:

```java
int flags = PortAllocatorConfig.PORTALLOCATOR_DISABLE_TCP
        | PortAllocatorConfig.PORTALLOCATOR_DISABLE_RELAY
        | PortAllocatorConfig.PORTALLOCATOR_ENABLE_IPV6;

RTCConfiguration cfg = new RTCConfiguration();
cfg.portAllocatorConfig.minPort = 50000;
cfg.portAllocatorConfig.maxPort = 50100;
cfg.portAllocatorConfig.flags = flags;

RTCPeerConnection pc = factory.createPeerConnection(cfg, observer);
```

Or use the fluent helpers:

```java
cfg.portAllocatorConfig
   .setDisableStun(true)
   .setDisableRelay(true)
   .setEnableSharedSocket(true);
```

## Tips and Troubleshooting

- Port Range Validity: Ensure `minPort <= maxPort` when both are set. If either is 0, the native default behavior applies.
- Firewalls/NATs: When running behind strict firewalls, restrict the host candidate port range to an allowed window and ensure your firewall allows outbound UDP for STUN/TURN as needed.
- Disabling Candidates: Disabling STUN and RELAY will limit you to host candidates, which may prevent connectivity across NATs. Use with care.
- TCP Candidates: Disabling TCP can speed up gathering and reduce unwanted candidates, but may reduce connectivity options in restrictive environments.
- IPv6: Enabling IPv6 may improve connectivity on IPv6‑capable networks; consider also `setEnableIpv6OnWifi(true)` when applicable.

## Related API

- `RTCConfiguration` — holds `portAllocatorConfig` used by `PeerConnectionFactory#createPeerConnection`.
- `RTCPeerConnection` — creating a peer connection triggers ICE gathering.
- `RTCIceServer` — define STUN/TURN servers for non‑host candidates.

For the full API, see the JavaDoc for `PortAllocatorConfig` and `RTCConfiguration`.
