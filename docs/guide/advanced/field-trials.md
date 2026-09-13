# Field Trials

WebRTC uses field trials to gate experimental features and tune internal behavior (e.g. congestion control, codec selection, message interleaving) without changing the public API. This guide explains how to enable them when creating a `PeerConnectionFactory`.

Field trials are applied once, at `PeerConnectionFactory` creation, and take effect for everything created by that factory (peer connections, tracks, sources). Different `PeerConnectionFactory` instances in the same process may use different field trials.

## Basic Usage

Pass a `Map<String, String>` of trial names to their assigned group to one of the `PeerConnectionFactory` constructors that accept `fieldTrials`:

```java
Map<String, String> fieldTrials = Map.of(
        "WebRTC-Bar", "Enabled"
);

PeerConnectionFactory factory = new PeerConnectionFactory(fieldTrials);
```

Field trials can be combined with a custom audio device module and/or audio processing module:

```java
PeerConnectionFactory factory = new PeerConnectionFactory(fieldTrials, audioModule);
PeerConnectionFactory factory = new PeerConnectionFactory(fieldTrials, audioProcessing);
PeerConnectionFactory factory = new PeerConnectionFactory(fieldTrials, audioModule, audioProcessing);
```

## Constraints

Each key and value must be non-null and non-empty. Passing a map that violates this constraint throws an exception when the `PeerConnectionFactory` is created.

## Tips and Troubleshooting

- Field trial names and accepted groups (e.g. `Enabled`, `Disabled`) are defined by the underlying WebRTC native code and change between WebRTC releases; consult the WebRTC source for the branch this library is built against to find currently available trials.
- Since field trials configure experimental or internal behavior, prefer enabling only the trials you specifically need, and re-verify them after upgrading to a new release of this library.

## Related API

- `PeerConnectionFactory` — the constructors accepting `fieldTrials` apply them for the lifetime of that factory.

For the full API, see the JavaDoc for `PeerConnectionFactory`.
