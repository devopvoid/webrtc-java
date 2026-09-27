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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCEncodedFrame;
import dev.onvoid.webrtc.RTCEncodedFrameTransformer;
import dev.onvoid.webrtc.RTCEncodedVideoFrame;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.RTCRtpSender;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import dev.onvoid.webrtc.media.recorder.MediaRecorder;
import dev.onvoid.webrtc.media.recorder.MediaRecorderListener;
import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrack;

/**
 * Encrypts a call end to end with encoded frame transforms, and records what
 * the receiving side decrypted into a media file.
 * <p>
 * This example shows how to:
 * <ul>
 *   <li>Encrypt every encoded audio and video frame with AES-GCM in an
 *       {@link RTCEncodedFrameTransformer} on the senders, and decrypt it on
 *       the receivers, so that whatever relays the media (an SFU, a TURN
 *       server) never sees it in the clear</li>
 *   <li>Keep the first bytes of each frame in the clear, where the RTP
 *       packetizer and depacketizer read the codec header, and authenticate
 *       them instead</li>
 *   <li>Record the decrypted media of the receivers with a
 *       {@link MediaRecorder}, without decoding or re-encoding it</li>
 * </ul>
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


    public static void main(String[] args) throws Exception {
        Path file = Paths.get(args.length > 0 ? args[0] : "encrypted-call.mkv");

        // Pushed audio needs a factory whose audio layer does not capture.
        AudioDeviceModule audioModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
        PeerConnectionFactory factory = new PeerConnectionFactory(audioModule);

        CustomVideoSource videoSource = new CustomVideoSource();
        CustomAudioSource audioSource = new CustomAudioSource();
        VideoTrack videoTrack = factory.createVideoTrack("video", videoSource);
        AudioTrack audioTrack = factory.createAudioTrack("audio", audioSource);

        Peer caller = new Peer(factory);
        Peer callee = new Peer(factory);
        caller.remote = callee;
        callee.remote = caller;

        FrameCipher cipher = new FrameCipher(newKey());

        RTCRtpSender videoSender = caller.connection.addTrack(videoTrack, List.of("stream"));
        RTCRtpSender audioSender = caller.connection.addTrack(audioTrack, List.of("stream"));

        // Set before negotiating, so the encoder needs no restart.
        videoSender.setTransform(cipher::encrypt);
        audioSender.setTransform(cipher::encrypt);

        callee.setRemoteDescription(caller.createOffer());
        caller.setRemoteDescription(callee.createAnswer());

        // The callee's transceivers follow the order of the offer.
        RTCRtpTransceiver[] transceivers = callee.connection.getTransceivers();
        RTCRtpReceiver videoReceiver = transceivers[0].getReceiver();
        RTCRtpReceiver audioReceiver = transceivers[1].getReceiver();

        videoReceiver.setTransform(cipher::decrypt);
        audioReceiver.setTransform(cipher::decrypt);

        caller.connected.await(10, TimeUnit.SECONDS);
        callee.connected.await(10, TimeUnit.SECONDS);

        MediaGenerator generator = new MediaGenerator(videoSource, audioSource);
        generator.start();

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

            // The receivers hand the recorder what they decrypted.
            recorder.addTrack(videoReceiver);
            recorder.addTrack(audioReceiver);
            recorder.start();

            Thread.sleep(TimeUnit.SECONDS.toMillis(RECORD_SECONDS));

            if (recorder.stop()) {
                LOG.info("Recorded " + RECORD_SECONDS + " seconds into " + recorder.getFile());
            }
        }
        finally {
            generator.stop();

            LOG.info(String.format("Encrypted %d frames, decrypted %d, rejected %d",
                    cipher.encrypted.get(), cipher.decrypted.get(), cipher.rejected.get()));

            videoReceiver.dispose();
            audioReceiver.dispose();
            for (RTCRtpTransceiver transceiver : transceivers) {
                transceiver.dispose();
            }
            videoSender.dispose();
            audioSender.dispose();

            caller.connection.close();
            callee.connection.close();

            videoTrack.dispose();
            audioTrack.dispose();
            videoSource.dispose();
            audioSource.dispose();

            factory.dispose();
            audioModule.dispose();
        }
    }

    private static SecretKey newKey() {
        byte[] key = new byte[16];
        new SecureRandom().nextBytes(key);

        return new SecretKeySpec(key, "AES");
    }


    /**
     * AES-GCM over the payload of each frame. An encrypted frame is laid out
     * as the clear header, the ciphertext with its tag, then the 12-byte IV.
     * Each transform runs on a thread of its own, one per sender or receiver,
     * so each call makes its own Cipher rather than share one.
     */
    private static class FrameCipher {

        private static final int IV_SIZE = 12;
        private static final int TAG_BITS = 128;

        private final SecretKey key;
        private final SecureRandom random = new SecureRandom();

        // The audio and the video transform each run on a thread of their own.
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


    /**
     * Pushes a moving test picture at 30 frames and a tone in 10 ms chunks
     * per second, in real time.
     */
    private static class MediaGenerator {

        private final CustomVideoSource videoSource;
        private final CustomAudioSource audioSource;

        private volatile boolean running;
        private Thread thread;


        MediaGenerator(CustomVideoSource videoSource, CustomAudioSource audioSource) {
            this.videoSource = videoSource;
            this.audioSource = audioSource;
        }

        void start() {
            running = true;
            thread = new Thread(this::run, "media-generator");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() throws InterruptedException {
            running = false;
            thread.join();
        }

        private void run() {
            byte[] audio = new byte[480 * 2];
            long startNs = System.nanoTime();
            long chunks = 0;
            long frames = 0;

            while (running) {
                long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

                while (chunks * 10 <= elapsedMs) {
                    for (int i = 0; i < 480; i++) {
                        double t = (chunks * 480 + i) / 48000.0;
                        short sample = (short) (Math.sin(2 * Math.PI * 440 * t) * 6000);
                        audio[2 * i] = (byte) sample;
                        audio[2 * i + 1] = (byte) (sample >> 8);
                    }

                    audioSource.pushAudio(audio, 16, 48000, 1, 480);
                    chunks++;
                }

                if (frames * 1000 / 30 <= elapsedMs) {
                    NativeI420Buffer buffer = NativeI420Buffer.allocate(WIDTH, HEIGHT);
                    ByteBuffer y = buffer.getDataY();
                    int stride = buffer.getStrideY();
                    int offset = (int) (frames * 4);

                    // Diagonal stripes that move, so every frame differs.
                    for (int row = 0; row < HEIGHT; row++) {
                        for (int col = 0; col < WIDTH; col++) {
                            y.put(row * stride + col, (byte) (((row + col + offset) / 16 % 2) * 180 + 40));
                        }
                    }

                    VideoFrame frame = new VideoFrame(buffer, 0);
                    videoSource.pushFrame(frame);
                    frame.release();
                    frames++;
                }

                try {
                    Thread.sleep(2);
                }
                catch (InterruptedException e) {
                    return;
                }
            }
        }
    }


    /**
     * One end of the call, exchanging candidates with the other directly.
     */
    private static class Peer implements PeerConnectionObserver {

        final RTCPeerConnection connection;
        final CountDownLatch connected = new CountDownLatch(1);

        volatile Peer remote;


        Peer(PeerConnectionFactory factory) {
            connection = factory.createPeerConnection(new RTCConfiguration(), this);
        }

        @Override
        public void onIceCandidate(RTCIceCandidate candidate) {
            remote.connection.addIceCandidate(candidate);
        }

        @Override
        public void onConnectionChange(RTCPeerConnectionState state) {
            if (state == RTCPeerConnectionState.CONNECTED) {
                connected.countDown();
            }
        }

        RTCSessionDescription createOffer() throws Exception {
            CompletableFuture<RTCSessionDescription> created = new CompletableFuture<>();
            connection.createOffer(new RTCOfferOptions(), created(created));

            return setLocalDescription(created.get(10, TimeUnit.SECONDS));
        }

        RTCSessionDescription createAnswer() throws Exception {
            CompletableFuture<RTCSessionDescription> created = new CompletableFuture<>();
            connection.createAnswer(new RTCAnswerOptions(), created(created));

            return setLocalDescription(created.get(10, TimeUnit.SECONDS));
        }

        void setRemoteDescription(RTCSessionDescription description) throws Exception {
            CompletableFuture<Void> set = new CompletableFuture<>();
            connection.setRemoteDescription(description, set(set));
            set.get(10, TimeUnit.SECONDS);
        }

        private RTCSessionDescription setLocalDescription(RTCSessionDescription description) throws Exception {
            CompletableFuture<Void> set = new CompletableFuture<>();
            connection.setLocalDescription(description, set(set));
            set.get(10, TimeUnit.SECONDS);

            return description;
        }

        private static CreateSessionDescriptionObserver created(CompletableFuture<RTCSessionDescription> future) {
            return new CreateSessionDescriptionObserver() {

                @Override
                public void onSuccess(RTCSessionDescription description) {
                    future.complete(description);
                }

                @Override
                public void onFailure(String error) {
                    future.completeExceptionally(new IllegalStateException(error));
                }
            };
        }

        private static SetSessionDescriptionObserver set(CompletableFuture<Void> future) {
            return new SetSessionDescriptionObserver() {

                @Override
                public void onSuccess() {
                    future.complete(null);
                }

                @Override
                public void onFailure(String error) {
                    future.completeExceptionally(new IllegalStateException(error));
                }
            };
        }
    }
}
