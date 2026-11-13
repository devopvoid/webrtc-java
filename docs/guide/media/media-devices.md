# Media Devices

<ApiNote client="/guide/client/sending-media#devices" title="Devices in Sending Media" />

This guide explains how to work with media devices (microphones, speakers, and cameras). It covers:

- Querying available media devices
- Getting device capabilities
- Listening for device hotplug events

Cameras and microphones play a key role in WebRTC. A more complex application will most likely check all connected cameras and microphones, and select the appropriate device for the WebRTC session.

The class `MediaDevices` enumerates these devices. It can also tell you about device changes, whenever a device is connected or disconnected.

## Querying Media Devices

The `MediaDevices` class provides methods to query all available media devices connected to the system.

::: info
Query devices on worker threads, not on UI or render threads.
:::

### Audio Capture Devices (Microphones)

To get a list of all available microphones:

```java
// Import required classes
import dev.onvoid.webrtc.media.MediaDevices;
import dev.onvoid.webrtc.media.audio.AudioDevice;
import java.util.List;

// Get all microphones
List<AudioDevice> microphones = MediaDevices.getAudioCaptureDevices();

// Print microphone details
for (AudioDevice microphone : microphones) {
    System.out.println("Microphone: " + microphone.getName());
    System.out.println("  Descriptor: " + microphone.getDescriptor());
}

// Get the default microphone
AudioDevice defaultMicrophone = MediaDevices.getDefaultAudioCaptureDevice();
if (defaultMicrophone != null) {
    System.out.println("Default Microphone: " + defaultMicrophone.getName());
}
```

### Audio Render Devices (Speakers)

To get a list of all available speakers:

```java
// Import required classes
import dev.onvoid.webrtc.media.MediaDevices;
import dev.onvoid.webrtc.media.audio.AudioDevice;
import java.util.List;

// Get all speakers
List<AudioDevice> speakers = MediaDevices.getAudioRenderDevices();

// Print speaker details
for (AudioDevice speaker : speakers) {
    System.out.println("Speaker: " + speaker.getName());
    System.out.println("  Descriptor: " + speaker.getDescriptor());
}

// Get the default speaker
AudioDevice defaultSpeaker = MediaDevices.getDefaultAudioRenderDevice();
if (defaultSpeaker != null) {
    System.out.println("Default Speaker: " + defaultSpeaker.getName());
}
```

### Video Capture Devices (Cameras)

To get a list of all available cameras:

```java
// Import required classes
import dev.onvoid.webrtc.media.MediaDevices;
import dev.onvoid.webrtc.media.video.VideoDevice;
import java.util.List;

// Get all cameras
List<VideoDevice> cameras = MediaDevices.getVideoCaptureDevices();

// Print camera details
for (VideoDevice camera : cameras) {
    System.out.println("Camera: " + camera.getName());
    System.out.println("  Descriptor: " + camera.getDescriptor());
}
```

## Camera Capabilities

You can query the capabilities of a specific camera to determine the supported resolutions and frame rates:

```java
// Import required classes
import dev.onvoid.webrtc.media.MediaDevices;
import dev.onvoid.webrtc.media.video.VideoDevice;
import dev.onvoid.webrtc.media.video.VideoCaptureCapability;
import java.util.List;

// Get all cameras
List<VideoDevice> cameras = MediaDevices.getVideoCaptureDevices();
if (cameras.isEmpty()) {
    System.out.println("No cameras found");
    return;
}

// Get the first camera
VideoDevice camera = cameras.get(0);
System.out.println("Camera: " + camera.getName());

// Get camera capabilities
List<VideoCaptureCapability> capabilities = MediaDevices.getVideoCaptureCapabilities(camera);

// Print camera capabilities
for (VideoCaptureCapability capability : capabilities) {
    System.out.println("  Resolution: " + capability.width + "x" + capability.height);
    System.out.println("  Frame Rate: " + capability.frameRate + " fps");
}
```

## Hotplug Events

You can listen for device hotplug events to be notified when devices are connected or disconnected.
Listeners are called on a thread of the library, one change at a time and in order, so a listener may
query `MediaDevices` again, for example for the new default device:

```java
// Import required classes
import dev.onvoid.webrtc.media.Device;
import dev.onvoid.webrtc.media.DeviceChangeListener;
import dev.onvoid.webrtc.media.MediaDevices;
import dev.onvoid.webrtc.media.audio.AudioDevice;
import dev.onvoid.webrtc.media.video.VideoDevice;

// Create a device change listener
DeviceChangeListener listener = new DeviceChangeListener() {
	@Override
    public void deviceConnected(Device device) {
		System.out.println("Device connected: " + device.getName());
            
		if (device instanceof AudioDevice) {
			System.out.println("  Type: Audio Device");
		}
		else if (device instanceof VideoDevice) {
			System.out.println("  Type: Video Device");
		}
	}

	@Override
    public void deviceDisconnected(Device device) {
		System.out.println("Device disconnected: " + device.getName());
            
		if (device instanceof AudioDevice) {
			System.out.println("  Type: Audio Device");
		}
		else if (device instanceof VideoDevice) {
			System.out.println("  Type: Video Device");
		}
	}
};

// Register the listener
MediaDevices.addDeviceChangeListener(listener);

// ... later, when you're done listening for events
// Unregister the listener
MediaDevices.removeDeviceChangeListener(listener);
```

## Conclusion

This guide has provided a comprehensive overview of working with media devices in the WebRTC library.
When implementing media device handling in your applications, consider these best practices:

- Always check if devices exist before trying to use them
- Handle the case where no devices are available gracefully
- Provide users with the ability to select from available devices
- Implement hotplug listeners to dynamically update available devices
- Remember to unregister device listeners when they're no longer needed
- Query devices on worker threads, not on UI or render threads.

You can use the code examples in this guide as a starting point for applications that need to work with media devices in WebRTC scenarios like video conferencing, live streaming, or media recording.