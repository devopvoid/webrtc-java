package consumer;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.client.WebRtc;
import dev.onvoid.webrtc.client.javafx.VideoView;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import dev.onvoid.webrtc.media.player.FFmpeg;
import dev.onvoid.webrtc.media.player.MediaPlayer;
import dev.onvoid.webrtc.media.recorder.MediaRecorderState;

/** A real consumer: compiled and run without test dependencies or add-exports. */
public final class Smoke {
    public static void main(String[] args) {
        // Resolve all three public modules, including JavaFX, without opening a UI.
        System.out.println(MediaPlayer.class.getName());
        System.out.println(VideoView.class.getName());
        System.out.println(MediaRecorderState.values().length);
        WebRtc.builder();

        // Exercise JNI on the minimum supported runtime without audio hardware.
        AudioDeviceModule audio = new AudioDeviceModule(AudioLayer.kDummyAudio);
        try {
            PeerConnectionFactory factory = new PeerConnectionFactory(audio);
            try {
                String version = FFmpeg.version();
                if (version == null || version.isEmpty()) {
                    throw new AssertionError("FFmpeg did not report its version");
                }
                System.out.println("FFmpeg " + version);
            }
            finally {
                factory.dispose();
            }
        }
        finally {
            audio.dispose();
        }
    }
}
