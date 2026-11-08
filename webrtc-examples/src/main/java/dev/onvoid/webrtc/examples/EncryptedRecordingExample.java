/*
 * Copyright 2026 Alex Andres
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.onvoid.webrtc.examples;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;


import dev.onvoid.webrtc.client.AudioMode;
import dev.onvoid.webrtc.client.CustomAudio;
import dev.onvoid.webrtc.client.CustomVideo;
import dev.onvoid.webrtc.client.PcmFormat;
import dev.onvoid.webrtc.client.Peer;
import dev.onvoid.webrtc.client.PeerOptions;
import dev.onvoid.webrtc.client.RemoteTrack;
import dev.onvoid.webrtc.client.Signaling;
import dev.onvoid.webrtc.client.SignalingPair;
import dev.onvoid.webrtc.client.WebRtc;
import dev.onvoid.webrtc.e2ee.KeyProvider;
import dev.onvoid.webrtc.media.recorder.MediaRecorder;
import dev.onvoid.webrtc.media.recorder.MediaRecorderListener;
import dev.onvoid.webrtc.media.recorder.MediaRecorderResult;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;

/**
 * Encrypts a call end to end with encoded frame transforms, and records what
 * the receiving side decrypted into a media file.
 * <p>
 * This example shows how to:
 * <ul>
 *   <li>Encrypt every encoded audio and video frame end to end in the SFrame
 *       format, with a {@link KeyProvider} in the peer options, so that
 *       whatever relays the media (an SFU, a TURN server) never sees it in
 *       the clear</li>
 *   <li>Allow the receiving side to record what it decrypted, which a key
 *       provider refuses by default</li>
 *   <li>Record the decrypted media of the remote tracks with a
 *       {@link MediaRecorder}, without decoding or re-encoding it</li>
 * </ul>
 * <p>
 * The peer options put the transforms on each sender before negotiation and
 * on each receiver before its track is handed out, so no frame leaves
 * unencrypted and none reaches the decoder still encrypted.
 * <p>
 * Both peers live in this application and hand each other their keys
 * directly; a real application exchanges them over a channel the media
 * servers cannot read, or derives them with a key agreement such as MLS.
 * <p>
 * Run it with an optional output file, whose extension picks the container:
 * <pre>
 * java dev.onvoid.webrtc.examples.EncryptedRecordingExample call.mkv
 * </pre>
 *
 * @author Alex Andres
 */
public class EncryptedRecordingExample {

    private static final Logger LOG = Logger.getLogger(EncryptedRecordingExample.class.getName());

    private static final int RECORD_SECONDS = 10;

    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;

    private static final PcmFormat AUDIO_FORMAT = PcmFormat.of(48000, 1);


    public static void main(String[] args) throws Exception {
        Path file = Paths.get(args.length > 0 ? args[0] : "encrypted-call.mkv");

        // Each participant encrypts with a key of its own. The callee records
        // what it decrypts, which its key provider has to allow.
        KeyProvider callerKeys = KeyProvider.builder(1).build();
        KeyProvider calleeKeys = KeyProvider.builder(2).allowPlaintextRecording(true).build();
        exchangeKeys(callerKeys, calleeKeys);

        // A headless runtime sends pushed audio and needs no microphone or speakers.
        try (WebRtc rtc = WebRtc.builder().audio(AudioMode.HEADLESS).build()) {
            SignalingPair link = Signaling.loopback();

            // Every frame a peer sends is encrypted, and every frame it receives decrypted.
            Peer caller = rtc.createPeer(PeerOptions.builder()
                    .name("caller")
                    .signaling(link.first())
                    .e2ee(callerKeys)
                    .build());
            Peer callee = rtc.createPeer(PeerOptions.builder()
                    .name("callee")
                    .polite(true)
                    .signaling(link.second())
                    .e2ee(calleeKeys)
                    .build());

            CompletableFuture<RemoteTrack> remoteVideo = new CompletableFuture<>();
            CompletableFuture<RemoteTrack> remoteAudio = new CompletableFuture<>();

            callee.onTrack(track -> (track.isVideo() ? remoteVideo : remoteAudio).complete(track));

            CustomVideo video = rtc.media().customVideo("video");
            CustomAudio audio = rtc.media().customAudio("audio");
            caller.addTrack(video);
            caller.addTrack(audio);

            ScheduledExecutorService feeder = Executors.newSingleThreadScheduledExecutor();

            try {
                feed(feeder, video, audio);

                caller.whenConnected().get(10, TimeUnit.SECONDS);

                record(file, remoteVideo.get(10, TimeUnit.SECONDS), remoteAudio.get(10, TimeUnit.SECONDS));
            }
            finally {
                feeder.shutdownNow();

                LOG.info("Caller: " + callerKeys.getStats() + ", callee: " + calleeKeys.getStats());
            }
        }
        // The runtime closed the peers, the tracks and the factory.
    }

    /**
     * Records the remote tracks for a while. They hand the recorder what
     * they decrypted.
     */
    private static void record(Path file, RemoteTrack video, RemoteTrack audio) throws Exception {
        // Replaces what an earlier run recorded, once this one is finished.
        try (MediaRecorder recorder = new MediaRecorder(file, true)) {
            recorder.setListener(new MediaRecorderListener() {

                @Override
                public void onStarted() {
                    LOG.info("Recording into " + recorder.getFile());
                }

                @Override
                public void onWarning(String message) {
                    LOG.warning(message);
                }

                @Override
                public void onError(String message) {
                    LOG.severe(message);
                }
            });

            recorder.addTrack(video);
            recorder.addTrack(audio);
            recorder.start();

            Thread.sleep(TimeUnit.SECONDS.toMillis(RECORD_SECONDS));

            if (recorder.stop()) {
                LOG.info("Recorded " + RECORD_SECONDS + " seconds into " + recorder.getFile()
                        + (recorder.getResult() == MediaRecorderResult.PARTIAL ? ", incomplete" : ""));
            }
        }
    }

    /**
     * Hands each participant's key to the other. Both live in this
     * application; a real one sends them over a channel the media servers
     * cannot read, such as an MLS group.
     */
    private static void exchangeKeys(KeyProvider caller, KeyProvider callee) {
        byte[] callerKey = caller.generateKey();
        byte[] calleeKey = callee.generateKey();

        caller.setEncryptionKey(0, callerKey);
        callee.setEncryptionKey(0, calleeKey);
        caller.setDecryptionKey(callee.getParticipant(), 0, calleeKey);
        callee.setDecryptionKey(caller.getParticipant(), 0, callerKey);
    }

    /**
     * Pushes a moving test picture at 30 frames per second, and a 440 Hz tone
     * a second at a time.
     */
    private static void feed(ScheduledExecutorService feeder, CustomVideo video, CustomAudio audio) {
        AtomicInteger frames = new AtomicInteger();

        feeder.scheduleAtFixedRate(() -> {
            NativeI420Buffer buffer = NativeI420Buffer.allocate(WIDTH, HEIGHT);
            ByteBuffer y = buffer.getDataY();
            int stride = buffer.getStrideY();
            int offset = frames.getAndIncrement() * 4;

            // Diagonal stripes that move, so every frame differs.
            for (int row = 0; row < HEIGHT; row++) {
                for (int col = 0; col < WIDTH; col++) {
                    y.put(row * stride + col, (byte) (((row + col + offset) / 16 % 2) * 180 + 40));
                }
            }

            VideoFrame frame = new VideoFrame(buffer, System.nanoTime());
            video.push(frame);
            frame.release();
        }, 0, 33, TimeUnit.MILLISECONDS);

        // Audio may be pushed in pieces of any length; the track sends it in 10 ms chunks.
        byte[] second = tone(AUDIO_FORMAT.sampleRate());

        feeder.scheduleAtFixedRate(() -> audio.push(second, AUDIO_FORMAT), 0, 1, TimeUnit.SECONDS);
    }

    private static byte[] tone(int sampleRate) {
        byte[] pcm = new byte[sampleRate * 2];

        for (int i = 0; i < sampleRate; i++) {
            short sample = (short) (Math.sin(2 * Math.PI * 440 * i / sampleRate) * 6000);

            pcm[2 * i] = (byte) sample;
            pcm[2 * i + 1] = (byte) (sample >> 8);
        }

        return pcm;
    }
}
