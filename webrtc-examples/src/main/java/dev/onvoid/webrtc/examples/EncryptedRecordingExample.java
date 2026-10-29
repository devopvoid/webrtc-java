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
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import dev.onvoid.webrtc.RTCEncodedFrame;
import dev.onvoid.webrtc.RTCEncodedVideoFrame;
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
 *   <li>Encrypt every encoded audio and video frame with AES-GCM in the send
 *       transform of the peer options, and decrypt it in the receive
 *       transform, so that whatever relays the media (an SFU, a TURN server)
 *       never sees it in the clear</li>
 *   <li>Keep the first bytes of each frame in the clear, where the RTP
 *       packetizer and depacketizer read the codec header, and authenticate
 *       them instead</li>
 *   <li>Record the decrypted media of the remote tracks with a
 *       {@link MediaRecorder}, without decoding or re-encoding it</li>
 * </ul>
 * <p>
 * The peer options put the transforms on each sender before negotiation and
 * on each receiver before its track is handed out, so no frame leaves
 * unencrypted and none reaches the decoder still encrypted.
 * <p>
 * Both peers live in this application and the key is simply shared between
 * them; a real application exchanges it over its signaling channel, or better
 * derives it with a key agreement such as MLS.
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

        FrameCipher cipher = new FrameCipher(newKey());

        // A headless runtime sends pushed audio and needs no microphone or speakers.
        try (WebRtc rtc = WebRtc.builder().audio(AudioMode.HEADLESS).build()) {
            SignalingPair link = Signaling.loopback();

            // Every frame a peer sends is encrypted, and every frame it receives decrypted.
            Peer caller = rtc.createPeer(PeerOptions.builder()
                    .name("caller")
                    .signaling(link.first())
                    .sendTransform(cipher::encrypt)
                    .receiveTransform(cipher::decrypt)
                    .build());
            Peer callee = rtc.createPeer(PeerOptions.builder()
                    .name("callee")
                    .polite(true)
                    .signaling(link.second())
                    .sendTransform(cipher::encrypt)
                    .receiveTransform(cipher::decrypt)
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

                LOG.info(String.format("Encrypted %d frames, decrypted %d, rejected %d",
                        cipher.encrypted.get(), cipher.decrypted.get(), cipher.rejected.get()));
            }
        }
        // The runtime closed the peers, the tracks and the factory.
    }

    /**
     * Records the remote tracks for a while. They hand the recorder what
     * they decrypted.
     */
    private static void record(Path file, RemoteTrack video, RemoteTrack audio) throws Exception {
        try (MediaRecorder recorder = new MediaRecorder(file)) {
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

    private static SecretKey newKey() {
        byte[] key = new byte[16];
        new SecureRandom().nextBytes(key);

        return new SecretKeySpec(key, "AES");
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



    /**
     * AES-GCM over the payload of each frame. An encrypted frame is laid out
     * as the clear header, the ciphertext with its tag, then the 12-byte IV.
     * One instance serves every sender and receiver, each of which runs its
     * transform on a thread of its own, so each call makes its own Cipher
     * rather than share one.
     */
    private static class FrameCipher {

        private static final int IV_SIZE = 12;
        private static final int TAG_BITS = 128;

        private final SecretKey key;
        private final SecureRandom random = new SecureRandom();

        // Counted from the threads of every sender and receiver.
        final AtomicLong encrypted = new AtomicLong();
        final AtomicLong decrypted = new AtomicLong();
        final AtomicLong rejected = new AtomicLong();


        FrameCipher(SecretKey key) {
            this.key = key;
        }

        void encrypt(RTCEncodedFrame frame) {
            ByteBuffer data = frame.getData();
            int clear = clearBytes(frame, data.remaining());

            byte[] iv = new byte[IV_SIZE];
            random.nextBytes(iv);

            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));

                ByteBuffer header = data.duplicate();
                header.limit(clear);
                cipher.updateAAD(header);

                ByteBuffer out = ByteBuffer.allocate(clear + cipher.getOutputSize(data.remaining() - clear) + IV_SIZE);
                ByteBuffer headerCopy = data.duplicate();
                headerCopy.limit(clear);
                out.put(headerCopy);

                data.position(clear);
                cipher.doFinal(data, out);
                out.put(iv);
                out.flip();

                frame.setData(out);
                encrypted.incrementAndGet();
            }
            catch (GeneralSecurityException e) {
                // Never send a frame that failed to encrypt.
                frame.drop();
                LOG.log(Level.WARNING, "Encrypting a frame failed", e);
            }
        }

        void decrypt(RTCEncodedFrame frame) {
            ByteBuffer data = frame.getData();
            int size = data.remaining();
            int clear = clearBytes(frame, size);

            if (size < clear + TAG_BITS / 8 + IV_SIZE) {
                frame.drop();
                rejected.incrementAndGet();
                return;
            }

            byte[] iv = new byte[IV_SIZE];
            data.position(size - IV_SIZE);
            data.get(iv);

            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));

                ByteBuffer header = data.duplicate();
                header.position(0);
                header.limit(clear);
                cipher.updateAAD(header);

                ByteBuffer out = ByteBuffer.allocate(size);
                ByteBuffer headerCopy = data.duplicate();
                headerCopy.position(0);
                headerCopy.limit(clear);
                out.put(headerCopy);

                ByteBuffer body = data.duplicate();
                body.position(clear);
                body.limit(size - IV_SIZE);
                cipher.doFinal(body, out);
                out.flip();

                frame.setData(out);
                decrypted.incrementAndGet();
            }
            catch (GeneralSecurityException e) {
                // A frame that does not authenticate was tampered with, or
                // encrypted with another key: it must not reach the decoder.
                frame.drop();
                rejected.incrementAndGet();
            }
        }

        /**
         * The bytes left in the clear: the VP8 payload header, which the
         * receiver's depacketizer reads to find key frames and the frame
         * size (10 bytes on a key frame, 3 otherwise), and the Opus TOC byte.
         */
        private static int clearBytes(RTCEncodedFrame frame, int size) {
            int clear = 1;

            if (frame instanceof RTCEncodedVideoFrame video) {
                clear = video.isKeyFrame() ? 10 : 3;
            }

            return Math.min(clear, size);
        }
    }
}
