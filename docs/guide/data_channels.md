# Data Channels

This guide explains how to use WebRTC data channels with the webrtc-java library. Data channels provide a bidirectional communication mechanism that can be used to send arbitrary data between peers.

## Overview

WebRTC data channels allow you to:
- Send and receive text or binary data between peers
- Configure reliability and ordering properties
- Set up multiple channels with different configurations
- Monitor channel state changes and buffer amounts

Data channels are created through an `RTCPeerConnection` and use the SCTP protocol for data transmission.

## Creating a Data Channel

To create a data channel, you need an established `RTCPeerConnection`. You can then call the `createDataChannel` method with a label and optional configuration:

```java
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelInit;

// Assuming you already have a PeerConnectionFactory and RTCConfiguration
RTCPeerConnection peerConnection = factory.createPeerConnection(config, peerConnectionObserver);

// Create a data channel with default configuration
RTCDataChannel dataChannel = peerConnection.createDataChannel("myChannel", new RTCDataChannelInit());
```

### Data Channel Configuration

You can customize the behavior of a data channel by configuring the `RTCDataChannelInit` object:

```java
RTCDataChannelInit config = new RTCDataChannelInit();

// Configure ordering (default: true)
config.ordered = true;  // Messages will be delivered in order

// Configure reliability
// Option 1: Reliable (default)
config.maxPacketLifeTime = -1;
config.maxRetransmits = -1;

// Option 2: Time-limited reliability
config.maxPacketLifeTime = 1000;  // Retransmit for up to 1000ms
config.maxRetransmits = -1;       // Don't use retransmit count limit

// Option 3: Count-limited reliability
config.maxPacketLifeTime = -1;    // Don't use time limit
config.maxRetransmits = 5;        // Retransmit up to 5 times

// Note: You cannot set both maxPacketLifeTime and maxRetransmits

// Configure channel negotiation (default: false)
config.negotiated = false;  // Channel will be announced in-band

// Configure channel ID (default: -1, auto-assigned)
config.id = -1;  // Let WebRTC assign an ID

// Configure sub-protocol (default: null)
config.protocol = "my-protocol";  // Optional sub-protocol name

// Configure priority (default: LOW)
config.priority = RTCPriorityType.LOW;

// Create the data channel with this configuration
RTCDataChannel dataChannel = peerConnection.createDataChannel("myChannel", config);
```

## Handling Data Channel Events

To receive events from a data channel, you need to implement the `RTCDataChannelObserver` interface and register it with the data channel:

```java
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelState;

dataChannel.registerObserver(new RTCDataChannelObserver() {
    @Override
    public void onBufferedAmountChange(long previousAmount) {
        // Called when the buffered amount changes
        long currentAmount = dataChannel.getBufferedAmount();
        System.out.println("Buffered amount changed from " + previousAmount + 
                           " to " + currentAmount + " bytes");
    }

    @Override
    public void onStateChange() {
        // Called when the data channel state changes
        RTCDataChannelState state = dataChannel.getState();
        System.out.println("Data channel state changed to: " + state);
        
        // Handle different states
        switch (state) {
            case CONNECTING:
                System.out.println("Data channel is being established");
                break;
            case OPEN:
                System.out.println("Data channel is open and ready to use");
                break;
            case CLOSING:
                System.out.println("Data channel is being closed");
                break;
            case CLOSED:
                System.out.println("Data channel is closed");
                break;
        }
    }

    @Override
    public void onMessage(RTCDataChannelBuffer buffer) {
        // Called when a message is received
        // IMPORTANT: The buffer data will be freed after this method returns,
        // so you must copy it if you need to use it asynchronously
        
        if (buffer.binary) {
            // Handle binary data
            handleBinaryMessage(buffer.data);
        } else {
            // Handle text data
            handleTextMessage(buffer.data);
        }
    }
});
```

### Receiving Data Channels

When the remote peer creates a data channel, the `onDataChannel` method of your `PeerConnectionObserver` will be called:

```java
import dev.onvoid.webrtc.PeerConnectionObserver;

public class MyPeerConnectionObserver implements PeerConnectionObserver {
    // Other PeerConnectionObserver methods...
    
    @Override
    public void onDataChannel(RTCDataChannel dataChannel) {
        System.out.println("Received data channel: " + dataChannel.getLabel());
        
        // Register an observer to handle events from this channel
        dataChannel.registerObserver(new MyDataChannelObserver());
    }
}
```

## Sending and Receiving Data

### Sending Data

You can send text or binary data through a data channel using the `send` method:

```java
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

// Send text data
String textMessage = "Hello, WebRTC!";
ByteBuffer textBuffer = ByteBuffer.wrap(textMessage.getBytes(StandardCharsets.UTF_8));
RTCDataChannelBuffer textChannelBuffer = new RTCDataChannelBuffer(textBuffer, false);

try {
    dataChannel.send(textChannelBuffer);
} catch (Exception e) {
    System.err.println("Failed to send text message: " + e.getMessage());
}

// Send binary data
byte[] binaryData = new byte[] { 0x01, 0x02, 0x03, 0x04 };
ByteBuffer binaryBuffer = ByteBuffer.wrap(binaryData);
RTCDataChannelBuffer binaryChannelBuffer = new RTCDataChannelBuffer(binaryBuffer, true);

try {
    dataChannel.send(binaryChannelBuffer);
} catch (Exception e) {
    System.err.println("Failed to send binary data: " + e.getMessage());
}
```

### Receiving Data

To receive data, implement the `onMessage` method in your `RTCDataChannelObserver`:

```java
@Override
public void onMessage(RTCDataChannelBuffer buffer) {
    ByteBuffer data = buffer.data;
    
    if (buffer.binary) {
        // Handle binary data
        byte[] binaryData;
        
        if (data.hasArray()) {
            binaryData = data.array();
        } else {
            binaryData = new byte[data.remaining()];
            data.get(binaryData);
        }
        
        System.out.println("Received binary data, " + binaryData.length + " bytes");
        // Process binary data...
    } else {
        // Handle text data
        byte[] textBytes;
        
        if (data.hasArray()) {
            textBytes = data.array();
        } else {
            textBytes = new byte[data.remaining()];
            data.get(textBytes);
        }
        
        String text = new String(textBytes, StandardCharsets.UTF_8);
        System.out.println("Received text message: " + text);
        // Process text message...
    }
}
```

## Data Channel Properties

You can query various properties of a data channel:

```java
// Get the channel label
String label = dataChannel.getLabel();

// Check if the channel is reliable
boolean reliable = dataChannel.isReliable();

// Check if messages are delivered in order
boolean ordered = dataChannel.isOrdered();

// Get the maximum packet lifetime (in milliseconds)
int maxPacketLifeTime = dataChannel.getMaxPacketLifeTime();

// Get the maximum number of retransmits
int maxRetransmits = dataChannel.getMaxRetransmits();

// Get the sub-protocol
String protocol = dataChannel.getProtocol();

// Check if the channel was negotiated by the application
boolean negotiated = dataChannel.isNegotiated();

// Get the channel ID
int id = dataChannel.getId();

// Get the current state
RTCDataChannelState state = dataChannel.getState();

// Get the amount of buffered data (in bytes)
long bufferedAmount = dataChannel.getBufferedAmount();
```

## Closing and Cleanup

When you're done with a data channel, you should properly clean it up:

```java
// Unregister the observer
dataChannel.unregisterObserver();

// Close the data channel
dataChannel.close();

// Dispose of native resources
dataChannel.dispose();
```

## Best Practices

1. **Error Handling**: Always wrap `send` calls in try-catch blocks as they can throw exceptions if the buffer is full or the channel is not in the OPEN state.

2. **Buffer Management**: Monitor the buffered amount to avoid overwhelming the channel. If `getBufferedAmount()` returns a large value, consider pausing sending until it decreases.

3. **Copy Received Data**: Remember that the data in the `RTCDataChannelBuffer` will be freed after the `onMessage` method returns. If you need to process the data asynchronously, make a copy of it.

4. **Proper Cleanup**: Always unregister observers, close channels, and dispose of native resources to prevent memory leaks.

5. **State Checking**: Check the channel state before sending data to avoid exceptions:
   ```java
   if (dataChannel.getState() == RTCDataChannelState.OPEN) {
       // Safe to send data
   }
   ```

6. **Multiple Channels**: Consider using multiple data channels with different configurations for different types of data (e.g., one reliable channel for critical data and one unreliable channel for real-time updates).

7. **Binary vs. Text**: Use the appropriate flag when creating `RTCDataChannelBuffer` objects:
   - `false` for UTF-8 text data
   - `true` for binary data

---

## Conclusion

WebRTC data channels provide a powerful way to establish peer-to-peer communication for transferring arbitrary data between clients.
Data channels complement WebRTC's audio and video capabilities, making it possible to build comprehensive real-time applications that include text chat, file transfers, game state synchronization, and other custom data exchange requirements.

For optimal performance, remember to follow the best practices outlined in this guide, particularly regarding buffer management and proper cleanup of resources.

For more information on other WebRTC features, refer to the additional guides in the documentation.