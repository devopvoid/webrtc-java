package dev.onvoid.webrtc.media.audio;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.MediaStreamTrack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
public class HeadlessADMIntegrationTest {

    @Test
    void audioReceivedOnSink() throws Exception {
		AudioDeviceModule receiverAdm = new HeadlessAudioDeviceModule();
		PeerConnectionFactory senderFactory = new PeerConnectionFactory();
		PeerConnectionFactory receiverFactory = new PeerConnectionFactory(receiverAdm);

		// Ensure the playout pipeline is started (headless output).
		receiverAdm.initPlayout();
		receiverAdm.startPlayout();

        RTCConfiguration cfg = new RTCConfiguration();

        // Latches and flags for coordination.
        CountDownLatch connectedLatch = new CountDownLatch(1);
        AtomicInteger receivedFrames = new AtomicInteger(0);
        AtomicBoolean sinkAdded = new AtomicBoolean(false);

        final AtomicReference<RTCPeerConnection> senderPcRef = new AtomicReference<>();
        final AtomicReference<RTCPeerConnection> receiverPcRef = new AtomicReference<>();

        // Receiver observer: handle ICE and attach AudioTrack sink on onTrack.
        PeerConnectionObserver receiverObserver = new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
                // Forward to sender
                RTCPeerConnection spc = senderPcRef.get();
                if (spc != null) {
                    spc.addIceCandidate(candidate);
                }
            }

            @Override
            public void onConnectionChange(RTCPeerConnectionState state) {
                if (state == RTCPeerConnectionState.CONNECTED) {
                    connectedLatch.countDown();
                }
            }

            @Override
            public void onTrack(RTCRtpTransceiver transceiver) {
                MediaStreamTrack track = transceiver.getReceiver().getTrack();
                if (MediaStreamTrack.AUDIO_TRACK_KIND.equals(track.getKind())) {
                    AudioTrack audioTrack = (AudioTrack) track;
                    AudioTrackSink sink = (data, bitsPerSample, sampleRate, channels, frames) -> {
                        if (frames > 0) {
                            receivedFrames.addAndGet(frames);
                        }
                    };
                    audioTrack.addSink(sink);
                    sinkAdded.set(true);


                }
            }
        };

        // Sender observer: forward ICE to receiver.
        PeerConnectionObserver senderObserver = candidate -> {
            RTCPeerConnection rpc = receiverPcRef.get();
            if (rpc != null) {
                rpc.addIceCandidate(candidate);
            }
        };

        RTCPeerConnection senderPc = senderFactory.createPeerConnection(cfg, senderObserver);
        RTCPeerConnection receiverPc = receiverFactory.createPeerConnection(cfg, receiverObserver);
        senderPcRef.set(senderPc);
        receiverPcRef.set(receiverPc);

        // Add an explicit receive-only audio transceiver on the receiver side.
        AudioTrackSource rxSource = receiverFactory.createAudioSource(new AudioOptions());
        AudioTrack receiverTrack = receiverFactory.createAudioTrack("rx-audio", rxSource);
        RTCRtpTransceiverInit recvOnlyInit = new RTCRtpTransceiverInit();
        recvOnlyInit.direction = RTCRtpTransceiverDirection.RECV_ONLY;
        receiverPc.addTransceiver(receiverTrack, recvOnlyInit);

        // Create sender audio from CustomAudioSource and add to PC.
        CustomAudioSource customSource = new CustomAudioSource();
        AudioTrack senderTrack = senderFactory.createAudioTrack("audio0", customSource);
        senderPc.addTrack(senderTrack, Collections.singletonList("stream0"));

        // SDP offer/answer exchange.
        LocalCreateDescObserver createOfferObs = new LocalCreateDescObserver();
        senderPc.createOffer(new RTCOfferOptions(), createOfferObs);
        RTCSessionDescription offer = createOfferObs.get();

        LocalSetDescObserver setLocalOfferObs = new LocalSetDescObserver();
        senderPc.setLocalDescription(offer, setLocalOfferObs);
        setLocalOfferObs.get();

        LocalSetDescObserver setRemoteOfferObs = new LocalSetDescObserver();
        receiverPc.setRemoteDescription(offer, setRemoteOfferObs);
        setRemoteOfferObs.get();

        LocalCreateDescObserver createAnswerObs = new LocalCreateDescObserver();
        receiverPc.createAnswer(new RTCAnswerOptions(), createAnswerObs);
        RTCSessionDescription answer = createAnswerObs.get();

        LocalSetDescObserver setLocalAnswerObs = new LocalSetDescObserver();
        receiverPc.setLocalDescription(answer, setLocalAnswerObs);
        setLocalAnswerObs.get();

        LocalSetDescObserver setRemoteAnswerObs = new LocalSetDescObserver();
        senderPc.setRemoteDescription(answer, setRemoteAnswerObs);
        setRemoteAnswerObs.get();

        // Wait for connection established, but also ensure sink was installed.
        assertTrue(connectedLatch.await(10, TimeUnit.SECONDS),
				"Peer connection did not reach CONNECTED state in time");

        // Push a few frames of audio data via CustomAudioSource.
        final int bitsPerSample = 16;
        final int sampleRate = 48000;
        final int channels = 2;
        final int frameCount = 480; // 10ms @ 48kHz
        byte[] silence = new byte[frameCount * channels * (bitsPerSample / 8)];

        // Give a small grace period for onTrack to fire and sink to be added.
        for (int i = 0; i < 10 && !sinkAdded.get(); i++) {
            Thread.sleep(50);
        }

        for (int i = 0; i < 50; i++) { // ~500ms of audio
            customSource.pushAudio(silence, bitsPerSample, sampleRate, channels, frameCount);
            Thread.sleep(10);
        }

        // Validate that we received audio frames on the remote track sink.
        assertTrue(receivedFrames.get() > 0, "No audio frames received on remote AudioTrack sink");

        // Cleanup.
        senderPc.close();
        receiverPc.close();
//		senderTrack.dispose();
//		receiverTrack.dispose();
        customSource.dispose();

		receiverAdm.stopPlayout();
		receiverAdm.dispose();

		receiverFactory.dispose();
		senderFactory.dispose();
    }



    private static class LocalCreateDescObserver implements CreateSessionDescriptionObserver {

        private final CountDownLatch latch = new CountDownLatch(1);
        private RTCSessionDescription description;
        private String error;


        @Override
        public void onSuccess(RTCSessionDescription description) {
            this.description = description;
            latch.countDown();
        }

        @Override
        public void onFailure(String error) {
            this.error = error;
            latch.countDown();
        }

        RTCSessionDescription get() throws Exception {
            latch.await(10, TimeUnit.SECONDS);
            if (error != null) {
                throw new IllegalStateException("CreateSessionDescription failed: " + error);
            }
            return description;
        }
    }



    private static class LocalSetDescObserver implements SetSessionDescriptionObserver {

        private final CountDownLatch latch = new CountDownLatch(1);
        private String error;


        @Override
        public void onSuccess() {
            latch.countDown();
        }

        @Override
        public void onFailure(String error) {
            this.error = error;
            latch.countDown();
        }

        void get() throws Exception {
            latch.await(10, TimeUnit.SECONDS);
            if (error != null) {
                throw new IllegalStateException("SetSessionDescription failed: " + error);
            }
        }
    }
}
