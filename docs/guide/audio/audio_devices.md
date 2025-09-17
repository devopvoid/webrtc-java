# Audio Device Selection

This guide focuses on setting up a peer connection with audio device selection capabilities, which allows you to choose specific microphones and speakers for your WebRTC connection.

## Audio Device Selection

To enable audio device selection, you need to:

1. List available audio devices
2. Create and configure an AudioDeviceModule
3. Pass the AudioDeviceModule to the PeerConnectionFactory

### Listing Available Audio Devices

The `MediaDevices` class provides methods to list available audio devices:

```java
import dev.onvoid.webrtc.media.MediaDevices;
import dev.onvoid.webrtc.media.audio.AudioDevice;
import java.util.List;

// Get available microphones (capture devices)
List<AudioDevice> captureDevices = MediaDevices.getAudioCaptureDevices();
System.out.println("Available microphones:");
for (AudioDevice device : captureDevices) {
    System.out.println(" - " + device.getName());
}

// Get available speakers (render devices)
List<AudioDevice> renderDevices = MediaDevices.getAudioRenderDevices();
System.out.println("Available speakers:");
for (AudioDevice device : renderDevices) {
    System.out.println(" - " + device.getName());
}

// Get default devices
AudioDevice defaultMicrophone = MediaDevices.getDefaultAudioCaptureDevice();
AudioDevice defaultSpeaker = MediaDevices.getDefaultAudioRenderDevice();
```

### Creating and Configuring an AudioDeviceModule

The `AudioDeviceModule` class allows you to select specific audio devices:

```java
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;

// Create an AudioDeviceModule
AudioDeviceModule audioModule = new AudioDeviceModule();

// Select specific devices
AudioDevice selectedMicrophone = captureDevices.get(0); // Choose the first microphone
AudioDevice selectedSpeaker = renderDevices.get(0);     // Choose the first speaker

audioModule.setRecordingDevice(selectedMicrophone);
audioModule.setPlayoutDevice(selectedSpeaker);

// Initialize recording and playback
audioModule.initRecording();
audioModule.initPlayout();
```

### Integrating with PeerConnectionFactory

Pass the configured AudioDeviceModule to the PeerConnectionFactory constructor:

```java
// Create a PeerConnectionFactory with the custom AudioDeviceModule
PeerConnectionFactory factory = new PeerConnectionFactory(audioModule);
```


## Disabling Audio

If you want to completely disable audio processing in your WebRTC application, you can create an AudioDeviceModule with the `kDummyAudio` layer:

```java
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;

// Create an AudioDeviceModule with dummy audio (no real audio processing)
AudioDeviceModule audioModule = new AudioDeviceModule(AudioLayer.kDummyAudio);

// Pass it to the PeerConnectionFactory
PeerConnectionFactory factory = new PeerConnectionFactory(audioModule);
```

This is useful for applications that only need video functionality, when you want to implement your own custom audio handling, or for headless modes where neither audio nor video is required. Using the dummy audio layer is particularly valuable in server-side or automated testing environments where no physical audio devices are available.

## Additional Features

The `AudioDeviceModule` provides additional methods for controlling audio:

### Volume Control
```java
// Get current volume levels
int micVolume = audioModule.getMicrophoneVolume();
int speakerVolume = audioModule.getSpeakerVolume();

// Get volume ranges
int minMicVolume = audioModule.getMinMicrophoneVolume();
int maxMicVolume = audioModule.getMaxMicrophoneVolume();
int minSpeakerVolume = audioModule.getMinSpeakerVolume();
int maxSpeakerVolume = audioModule.getMaxSpeakerVolume();

// Set volume levels
audioModule.setMicrophoneVolume(75); // Set to 75% of max
audioModule.setSpeakerVolume(80);    // Set to 80% of max
```

### Mute Control
```java
// Check mute status
boolean isMicMuted = audioModule.isMicrophoneMuted();
boolean isSpeakerMuted = audioModule.isSpeakerMuted();

// Set mute status
audioModule.setMicrophoneMute(true);  // Mute microphone
audioModule.setSpeakerMute(false);    // Unmute speaker
```

### Device Change Handling
```java
// Add a device change listener to handle device hot-plugging
MediaDevices.addDeviceChangeListener(event -> {
    System.out.println("Audio devices changed. Refreshing device list...");
    
    // Refresh device lists
    List<AudioDevice> newCaptureDevices = MediaDevices.getAudioCaptureDevices();
    List<AudioDevice> newRenderDevices = MediaDevices.getAudioRenderDevices();
    
    // Update UI or device selection as needed
});
```

---

## Conclusion

This guide has walked you through the process of implementing audio device selection in your WebRTC application.
By implementing these capabilities, your application can provide users with greater control over their audio experience, accommodating different hardware setups and preferences.
For more advanced audio processing options, consider exploring the audio processing APIs available in this documentation.