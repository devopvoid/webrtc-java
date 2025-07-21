# Bitrate and Framerate Constraints Guide

This guide explains how to set bitrate and framerate constraints for MediaStreamTrack of RTCRtpSender. It covers:

- [Understanding RTCRtpSender Parameters](#understanding-rtcrtsender-parameters)
- [Setting maximum and minimum bitrate constraints](#setting-bitrate-constraints)
- [Setting maximum framerate constraints](#setting-framerate-constraints)
- [Scaling video resolution](#scaling-video-resolution)

## Understanding RTCRtpSender Parameters

The `RTCRtpSender` class allows you to control how a `MediaStreamTrack` is encoded and transmitted to a remote peer. You can modify encoding parameters such as bitrate and framerate by getting the current parameters, modifying them, and then setting the updated parameters.

The key methods for this process are:

- `getParameters()` - Returns the current parameters for how the track is encoded and transmitted
- `setParameters(RTCRtpSendParameters parameters)` - Updates how the track is encoded and transmitted

It's important to note that you must first call `getParameters()`, modify the returned object, and then call `setParameters()` with the modified object. This is because the parameters object contains a transaction ID that ensures there are no intervening changes.

## Setting Bitrate Constraints

You can set both maximum and minimum bitrate constraints for a MediaStreamTrack. These constraints help control the quality and bandwidth usage of the transmitted media.

<pre>
// Import required classes
import dev.onvoid.webrtc.RTCRtpSender;
import dev.onvoid.webrtc.RTCRtpSendParameters;
import dev.onvoid.webrtc.RTCRtpEncodingParameters;

// Assuming you have an RTCRtpSender instance
RTCRtpSender sender = /* ... */;

// Get the current parameters
RTCRtpSendParameters parameters = sender.getParameters();

// Check if there are any encodings
if (parameters.encodings != null && !parameters.encodings.isEmpty()) {
    // Set maximum bitrate (in bits per second)
    // For example, 1000000 bps = 1 Mbps
    parameters.encodings.get(0).maxBitrate = 1000000;
    
    // Set minimum bitrate (in bits per second)
    // For example, 100000 bps = 100 Kbps
    parameters.encodings.get(0).minBitrate = 100000;
    
    // Apply the modified parameters
    sender.setParameters(parameters);
}
</pre>

Setting a maximum bitrate helps ensure that your application doesn't use excessive bandwidth, which is particularly important for users with limited data plans or slower connections. Setting a minimum bitrate can help maintain a certain level of quality, though it may cause issues if the available bandwidth falls below this threshold.

## Setting Framerate Constraints

You can also set a maximum framerate constraint for video tracks. This can be useful for limiting CPU usage or bandwidth consumption.

<pre>
// Import required classes
import dev.onvoid.webrtc.RTCRtpSender;
import dev.onvoid.webrtc.RTCRtpSendParameters;
import dev.onvoid.webrtc.RTCRtpEncodingParameters;

// Assuming you have an RTCRtpSender instance with a video track
RTCRtpSender sender = /* ... */;

// Get the current parameters
RTCRtpSendParameters parameters = sender.getParameters();

// Check if there are any encodings
if (parameters.encodings != null && !parameters.encodings.isEmpty()) {
    // Set maximum framerate (in frames per second)
    // For example, 15.0 fps
    parameters.encodings.get(0).maxFramerate = 15.0;
    
    // Apply the modified parameters
    sender.setParameters(parameters);
}
</pre>

Setting a lower framerate can significantly reduce bandwidth usage, which is beneficial for users with limited bandwidth. However, it may result in less smooth video playback.

## Scaling Video Resolution

In addition to bitrate and framerate constraints, you can also scale down the resolution of a video track. This is done using the `scaleResolutionDownBy` parameter.

<pre>
// Import required classes
import dev.onvoid.webrtc.RTCRtpSender;
import dev.onvoid.webrtc.RTCRtpSendParameters;
import dev.onvoid.webrtc.RTCRtpEncodingParameters;

// Assuming you have an RTCRtpSender instance with a video track
RTCRtpSender sender = /* ... */;

// Get the current parameters
RTCRtpSendParameters parameters = sender.getParameters();

// Check if there are any encodings
if (parameters.encodings != null && !parameters.encodings.isEmpty()) {
    // Scale down resolution by a factor of 2 (each dimension)
    // This will reduce the video size to 1/4 of the original
    parameters.encodings.get(0).scaleResolutionDownBy = 2.0;
    
    // Apply the modified parameters
    sender.setParameters(parameters);
}
</pre>

The `scaleResolutionDownBy` parameter specifies how much to scale down the video in each dimension. For example, a value of 2.0 means the video will be scaled down by a factor of 2 in both width and height, resulting in a video that is 1/4 the size of the original.

Note that these constraints are applied without requiring SDP renegotiation, making them suitable for dynamic adaptation to changing network conditions.

You can use this code as a starting point for your own applications that need to control media quality and bandwidth usage.