# RTC Stats

This guide explains how to use WebRTC statistics (RTC Stats) with the webrtc-java library. RTC Stats provide detailed metrics and information about the state and performance of your WebRTC connections.

## Overview

WebRTC statistics allow you to:
- Monitor the quality of audio and video streams
- Track network performance metrics
- Diagnose connection issues
- Gather information about codecs, candidates, and data channels
- Analyze media source characteristics

Statistics are collected through the `RTCPeerConnection` and are delivered asynchronously via callback.

## Understanding RTC Stats Classes

The webrtc-java library provides several classes for working with statistics:

### RTCStats

The `RTCStats` class represents statistics for a specific monitored object at a specific moment in time. Each `RTCStats` object contains:

- `timestamp`: When the stats were collected (in microseconds since UNIX epoch)
- `type`: An enum value indicating the type of stats (from `RTCStatsType`)
- `id`: A unique identifier for the object that was inspected
- `attributes`: A map of key-value pairs containing the actual statistics data

### RTCStatsReport

The `RTCStatsReport` class contains a collection of `RTCStats` objects gathered at the same time. It provides:

- A map of `RTCStats` objects, accessible via the `getStats()` method
- A timestamp indicating when the report was generated

### RTCStatsType

The `RTCStatsType` enum defines the different types of statistics that can be collected:

```java
public enum RTCStatsType {
    CODEC,                // Codec statistics
    INBOUND_RTP,          // Incoming RTP stream statistics
    OUTBOUND_RTP,         // Outgoing RTP stream statistics
    REMOTE_INBOUND_RTP,   // Remote endpoint's incoming RTP statistics
    REMOTE_OUTBOUND_RTP,  // Remote endpoint's outgoing RTP statistics
    MEDIA_SOURCE,         // Media source statistics
    CSRC,                 // Contributing source statistics
    PEER_CONNECTION,      // Peer connection statistics
    DATA_CHANNEL,         // Data channel statistics
    STREAM,               // MediaStream statistics
    TRACK,                // MediaStreamTrack statistics
    SENDER,               // RTP sender statistics
    RECEIVER,             // RTP receiver statistics
    TRANSPORT,            // Transport statistics
    CANDIDATE_PAIR,       // ICE candidate pair statistics
    LOCAL_CANDIDATE,      // Local ICE candidate statistics
    REMOTE_CANDIDATE,     // Remote ICE candidate statistics
    CERTIFICATE,          // Certificate statistics
    ICE_SERVER            // ICE server statistics
}
```

## Collecting Statistics

### Getting Stats for the Entire Connection

To collect statistics for the entire peer connection:

```java
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCStats;
import dev.onvoid.webrtc.RTCStatsReport;
import dev.onvoid.webrtc.RTCStatsCollectorCallback;

// Assuming you already have an RTCPeerConnection
RTCPeerConnection peerConnection = /* your peer connection */;

// Request statistics
peerConnection.getStats(new RTCStatsCollectorCallback() {
    @Override
    public void onStatsDelivered(RTCStatsReport report) {
        // Process the stats report
        System.out.println("Stats collected at: " + report.getTimestamp());
        
        // Access all stats in the report
        Map<String, RTCStats> stats = report.getStats();
        System.out.println("Number of stats objects: " + stats.size());
        
        // Process individual stats objects
        for (RTCStats stat : stats.values()) {
            System.out.println("Stat type: " + stat.getType());
            System.out.println("Stat ID: " + stat.getId());
            
            // Access the attributes
            Map<String, Object> attributes = stat.getAttributes();
            for (Map.Entry<String, Object> entry : attributes.entrySet()) {
                System.out.println(entry.getKey() + ": " + entry.getValue());
            }
        }
    }
});
```

You can also use a lambda expression for more concise code:

```java
peerConnection.getStats(report -> {
    // Process the stats report
    System.out.println("Stats report received with " + report.getStats().size() + " stats objects");
});
```

### Getting Stats for a Specific Sender or Receiver

You can also collect statistics for a specific RTP sender or receiver:

```java
// For a specific sender
RTCRtpSender sender = /* your RTP sender */;
peerConnection.getStats(sender, report -> {
    // Process sender stats
    System.out.println("Sender stats received");
});

// For a specific receiver
RTCRtpReceiver receiver = /* your RTP receiver */;
peerConnection.getStats(receiver, report -> {
    // Process receiver stats
    System.out.println("Receiver stats received");
});
```

## Working with Specific Stat Types

Different stat types contain different attributes. Here are examples of how to work with some common stat types:

### Inbound RTP Statistics

```java
peerConnection.getStats(report -> {
    for (RTCStats stats : report.getStats().values()) {
        if (stats.getType() == RTCStatsType.INBOUND_RTP) {
            Map<String, Object> attributes = stats.getAttributes();
            
            // Access common inbound RTP attributes
            Long packetsReceived = (Long) attributes.get("packetsReceived");
            Long bytesReceived = (Long) attributes.get("bytesReceived");
            Double jitter = (Double) attributes.get("jitter");
            Long packetsLost = (Long) attributes.get("packetsLost");
            
            System.out.println("Inbound RTP Stats:");
            System.out.println("Packets received: " + packetsReceived);
            System.out.println("Bytes received: " + bytesReceived);
            System.out.println("Jitter (seconds): " + jitter);
            System.out.println("Packets lost: " + packetsLost);
        }
    }
});
```

### Outbound RTP Statistics

```java
peerConnection.getStats(report -> {
    for (RTCStats stats : report.getStats().values()) {
        if (stats.getType() == RTCStatsType.OUTBOUND_RTP) {
            Map<String, Object> attributes = stats.getAttributes();
            
            // Access common outbound RTP attributes
            Long packetsSent = (Long) attributes.get("packetsSent");
            Long bytesSent = (Long) attributes.get("bytesSent");
            
            System.out.println("Outbound RTP Stats:");
            System.out.println("Packets sent: " + packetsSent);
            System.out.println("Bytes sent: " + bytesSent);
        }
    }
});
```

### Candidate Pair Statistics

```java
peerConnection.getStats(report -> {
    for (RTCStats stats : report.getStats().values()) {
        if (stats.getType() == RTCStatsType.CANDIDATE_PAIR) {
            Map<String, Object> attributes = stats.getAttributes();
            
            // Access common candidate pair attributes
            Boolean nominated = (Boolean) attributes.get("nominated");
            String state = (String) attributes.get("state");
            Long bytesSent = (Long) attributes.get("bytesSent");
            Long bytesReceived = (Long) attributes.get("bytesReceived");
            Double currentRoundTripTime = (Double) attributes.get("currentRoundTripTime");
            
            System.out.println("ICE Candidate Pair Stats:");
            System.out.println("Nominated: " + nominated);
            System.out.println("State: " + state);
            System.out.println("Bytes sent: " + bytesSent);
            System.out.println("Bytes received: " + bytesReceived);
            System.out.println("Current RTT (seconds): " + currentRoundTripTime);
        }
    }
});
```

## Monitoring Connection Quality

You can periodically collect statistics to monitor the quality of your WebRTC connection:

```java
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// Create a scheduler
ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

// Schedule periodic stats collection
scheduler.scheduleAtFixedRate(() -> {
    peerConnection.getStats(report -> {
        // Process and analyze stats
        analyzeConnectionQuality(report);
    });
}, 0, 2, TimeUnit.SECONDS);  // Collect stats every 2 seconds

// Example method to analyze connection quality
private void analyzeConnectionQuality(RTCStatsReport report) {
    // Track packet loss
    Long totalPacketsLost = 0L;
    Long totalPacketsReceived = 0L;
    
    // Track jitter
    Double maxJitter = 0.0;
    
    // Track round-trip time
    Double currentRtt = 0.0;
    
    for (RTCStats stats : report.getStats().values()) {
        Map<String, Object> attributes = stats.getAttributes();
        
        if (stats.getType() == RTCStatsType.INBOUND_RTP) {
            Long packetsLost = (Long) attributes.get("packetsLost");
            Long packetsReceived = (Long) attributes.get("packetsReceived");
            Double jitter = (Double) attributes.get("jitter");
            
            if (packetsLost != null) totalPacketsLost += packetsLost;
            if (packetsReceived != null) totalPacketsReceived += packetsReceived;
            if (jitter != null && jitter > maxJitter) maxJitter = jitter;
        }
        else if (stats.getType() == RTCStatsType.CANDIDATE_PAIR) {
            Double rtt = (Double) attributes.get("currentRoundTripTime");
            if (rtt != null) currentRtt = rtt;
        }
    }
    
    // Calculate packet loss percentage
    double packetLossPercent = 0;
    if (totalPacketsReceived + totalPacketsLost > 0) {
        packetLossPercent = (totalPacketsLost * 100.0) / (totalPacketsReceived + totalPacketsLost);
    }
    
    // Log or display the quality metrics
    System.out.println("Connection Quality Metrics:");
    System.out.println("Packet Loss: " + String.format("%.2f%%", packetLossPercent));
    System.out.println("Max Jitter: " + String.format("%.2f ms", maxJitter * 1000));
    System.out.println("Round-Trip Time: " + String.format("%.2f ms", currentRtt * 1000));
    
    // Determine overall quality
    String qualityRating;
    if (packetLossPercent < 1 && maxJitter < 0.030 && currentRtt < 0.100) {
        qualityRating = "Excellent";
    } else if (packetLossPercent < 3 && maxJitter < 0.050 && currentRtt < 0.200) {
        qualityRating = "Good";
    } else if (packetLossPercent < 8 && maxJitter < 0.100 && currentRtt < 0.300) {
        qualityRating = "Fair";
    } else {
        qualityRating = "Poor";
    }
    
    System.out.println("Overall Quality: " + qualityRating);
}

// Don't forget to shut down the scheduler when done
// scheduler.shutdown();
```

## Handling Asynchronous Stats Collection

Since stats are collected asynchronously, you might need to coordinate with other operations. Here's an example using a CountDownLatch:

```java
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public RTCStatsReport collectStatsSync(RTCPeerConnection peerConnection, long timeoutMs) throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<RTCStatsReport> reportRef = new AtomicReference<>();
    
    peerConnection.getStats(report -> {
        reportRef.set(report);
        latch.countDown();
    });
    
    // Wait for stats to be delivered or timeout
    if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
        throw new RuntimeException("Timed out waiting for stats");
    }
    
    return reportRef.get();
}

// Usage
try {
    RTCStatsReport report = collectStatsSync(peerConnection, 5000);
    // Process the report
} catch (InterruptedException e) {
    System.err.println("Stats collection was interrupted: " + e.getMessage());
} catch (RuntimeException e) {
    System.err.println("Stats collection failed: " + e.getMessage());
}
```

## Best Practices

1. **Don't Collect Too Frequently**: Collecting stats is resource-intensive. For most applications, collecting stats every 1-2 seconds is sufficient.

2. **Handle Null Values**: Some attributes might be null or missing depending on the state of the connection and the browser implementation. Always check for null values before using them.

3. **Type Casting**: The attributes in the stats objects are returned as generic Objects. You need to cast them to the appropriate type (Boolean, Long, Double, String, etc.) before using them.

4. **Trend Analysis**: Individual stats snapshots are useful, but tracking trends over time provides more valuable insights. Consider storing historical data to analyze trends.

5. **Focus on Relevant Stats**: Depending on your use case, focus on the most relevant stats:
   - For video quality: frame rate, resolution, packets lost
   - For audio quality: jitter, packets lost
   - For network performance: round-trip time, bandwidth

6. **Correlation**: Correlate stats with user experience. For example, if users report poor quality, check the stats during that time to identify potential issues.

7. **Logging**: Log stats periodically and especially when issues occur to help with debugging.

## Common Attributes by Stat Type

Different stat types have different attributes. Here are some common attributes for each type:

### INBOUND_RTP
- packetsReceived
- bytesReceived
- packetsLost
- jitter
- framesDecoded (video)
- framesDropped (video)
- audioLevel (audio)

### OUTBOUND_RTP
- packetsSent
- bytesSent
- retransmittedPacketsSent
- framesSent (video)
- framesEncoded (video)
- targetBitrate

### CANDIDATE_PAIR
- nominated
- state
- bytesSent
- bytesReceived
- currentRoundTripTime
- availableOutgoingBitrate
- availableIncomingBitrate

### TRANSPORT
- bytesSent
- bytesReceived
- dtlsState
- selectedCandidatePairId

### MEDIA_SOURCE
- trackIdentifier
- kind
- audioLevel (audio)
- totalAudioEnergy (audio)
- width (video)
- height (video)
- frames (video)
- framesPerSecond (video)

Remember that the available attributes may vary depending on the state of the connection.

---

## Conclusion

WebRTC statistics provide essential insights into the performance and health of your real-time communications. By leveraging the RTC Stats API in webrtc-java, you can monitor connection quality, diagnose issues, and optimize your application's performance. The ability to collect detailed metrics on packets, jitter, latency, and more allows you to make data-driven decisions.