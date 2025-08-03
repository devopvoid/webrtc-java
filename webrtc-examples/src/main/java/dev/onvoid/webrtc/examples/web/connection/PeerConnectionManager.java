/*
 * Copyright 2025 Alex Andres
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

package dev.onvoid.webrtc.examples.web.connection;

import java.util.List;
import java.util.function.Consumer;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceConnectionState;
import dev.onvoid.webrtc.RTCIceGatheringState;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.RTCSignalingState;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.examples.web.model.IceCandidateMessage;
import dev.onvoid.webrtc.examples.web.model.SessionDescriptionMessage;
import dev.onvoid.webrtc.media.MediaStream;
import dev.onvoid.webrtc.media.MediaStreamTrack;

import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages WebRTC peer connections, including creation, configuration,
 * and session description handling. Also handles signaling messages related to
 * peer connections such as offers, answers, and ICE candidates.
 *
 * @author Alex Andres
 */
public class PeerConnectionManager implements PeerConnectionSignalingHandler {

    private static final Logger LOG = LoggerFactory.getLogger(PeerConnectionManager.class);

    private final PeerConnectionFactory factory;
    private final RTCPeerConnection peerConnection;

    private Consumer<RTCSessionDescription> onLocalDescriptionCreated;
    private Consumer<RTCIceCandidate> onIceCandidateGenerated;
    private Consumer<MediaStreamTrack> onTrackReceived;

    private boolean isInitiator = false;


    /**
     * Creates a new PeerConnectionManager.
     */
    public PeerConnectionManager() {
        factory = new PeerConnectionFactory();

        // Create peer connection with default configuration.
        RTCIceServer iceServer = new RTCIceServer();
        iceServer.urls.add("stun:stun.l.google.com:19302");

        RTCConfiguration config = new RTCConfiguration();
        config.iceServers.add(iceServer);

        peerConnection = factory.createPeerConnection(config, new PeerConnectionObserverImpl());

        LOG.info("PeerConnectionManager created");
    }

    /**
     * Adds a media track to the peer connection.
     *
     * @param track The media track to add.
     * @param streamIds The stream IDs to associate with the track.
     */
    public void addTrack(MediaStreamTrack track, List<String> streamIds) {
        peerConnection.addTrack(track, streamIds);

        LOG.info("Added track: {}", track.getKind());
    }

    /**
     * Creates an audio track with the specified options and label.
     *
     * @param options Configuration options for the audio track source.
     * @param label   A unique identifier for the audio track.
     *
     * @return A new AudioTrack instance configured with the provided options.
     */
    public AudioTrack createAudioTrack(AudioOptions options, String label) {
        AudioTrackSource audioSource = factory.createAudioSource(options);

        return factory.createAudioTrack(label, audioSource);
    }

    /**
     * Creates an audio track with a custom audio source that can push audio frames.
     *
     * @param source The custom audio source providing the audio frames.
     * @param label  A unique identifier for the audio track.
     *
     * @return A new AudioTrack instance connected to the provided custom source.
     */
    public AudioTrack createAudioTrack(CustomAudioSource source, String label) {
        return factory.createAudioTrack(label, source);
    }

    /**
     * Creates a video track with the specified source and label.
     *
     * @param source The video track source providing the video frames.
     * @param label  A unique identifier for the video track.
     *
     * @return A new VideoTrack instance connected to the provided source.
     */
    public VideoTrack createVideoTrack(VideoTrackSource source, String label) {
        return factory.createVideoTrack(label, source);
    }

    /**
     * Closes the peer connection.
     */
    public void close() {
        if (peerConnection != null) {
            peerConnection.close();
        }

        if (factory != null) {
            factory.dispose();
        }

        LOG.info("Peer connection closed");
    }

    /**
     * Creates an offer to initiate a connection.
     */
    public void createOffer() {
        RTCOfferOptions options = new RTCOfferOptions();

        peerConnection.createOffer(options, new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription sdp) {
                setLocalDescription(sdp);
            }

            @Override
            public void onFailure(String error) {
                LOG.error("Failed to create offer: {}", error);
            }
        });
    }

    /**
     * Creates an answer in response to an offer.
     */
    public void createAnswer() {
        RTCAnswerOptions options = new RTCAnswerOptions();
        peerConnection.createAnswer(options, new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription answer) {
                setLocalDescription(answer);
            }

            @Override
            public void onFailure(String error) {
                LOG.error("Failed to create answer: {}", error);
            }
        });
    }

    /**
     * Sets the local session description.
     *
     * @param sdp The session description to set.
     */
    private void setLocalDescription(RTCSessionDescription sdp) {
        peerConnection.setLocalDescription(sdp, new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                LOG.info("Local description set successfully");
                
                if (onLocalDescriptionCreated != null) {
                    onLocalDescriptionCreated.accept(sdp);
                }
            }

            @Override
            public void onFailure(String error) {
                LOG.error("Failed to set local session description: {}", error);
            }
        });
    }

    /**
     * Sets the remote session description.
     *
     * @param sdp The session description to set.
     * @param isOffer True if the description is an offer, false otherwise.
     */
    public void setRemoteDescription(RTCSessionDescription sdp, boolean isOffer) {
        peerConnection.setRemoteDescription(sdp, new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                LOG.info("Remote description set successfully");

                if (isOffer) {
                    createAnswer();
                }
            }

            @Override
            public void onFailure(String error) {
                LOG.error("Failed to set remote description: {}", error);
            }
        });
    }

    /**
     * Adds an ICE candidate to the peer connection.
     *
     * @param candidate The ICE candidate to add.
     */
    public void addIceCandidate(RTCIceCandidate candidate) {
        peerConnection.addIceCandidate(candidate);
        LOG.info("Added ICE candidate: {}", candidate.sdp);
    }

    /**
     * Sets a callback to be invoked when a local session description is created.
     *
     * @param callback The callback to invoke.
     */
    public void setOnLocalDescriptionCreated(Consumer<RTCSessionDescription> callback) {
        this.onLocalDescriptionCreated = callback;
    }

    /**
     * Sets a callback to be invoked when an ICE candidate is generated.
     *
     * @param callback The callback to invoke.
     */
    public void setOnIceCandidateGenerated(Consumer<RTCIceCandidate> callback) {
        this.onIceCandidateGenerated = callback;
    }

    /**
     * Sets a callback to be invoked when a media track is received.
     *
     * @param callback The callback to invoke.
     */
    public void setOnTrackReceived(Consumer<MediaStreamTrack> callback) {
        this.onTrackReceived = callback;
    }
    
    /**
     * Sets whether this peer is the initiator of the connection.
     *
     * @param isInitiator true if this peer is the initiator, false otherwise.
     */
    public void setInitiator(boolean isInitiator) {
        this.isInitiator = isInitiator;
    }

    /**
     * Gets whether this peer is the initiator of the connection.
     *
     * @return true if this peer is the initiator, false otherwise.
     */
    public boolean isInitiator() {
        return isInitiator;
    }

    @Override
    public void handleOffer(SessionDescriptionMessage message) {
        LOG.info("Received offer");

        try {
            String sdpString = message.getSdp();
            String fromPeer = message.getFrom();

            LOG.info("Parsed offer from: {}", fromPeer);

            // Create remote session description.
            RTCSessionDescription sdp = new RTCSessionDescription(RTCSdpType.OFFER, sdpString);

            // Set remote description and create answer
            setRemoteDescription(sdp, true);
        }
        catch (Exception e) {
            LOG.error("Error parsing offer JSON", e);
        }
    }

    @Override
    public void handleAnswer(SessionDescriptionMessage message) {
        LOG.info("Received answer");

        try {
            String sdpString = message.getSdp();
            
            LOG.info("Successfully parsed answer JSON");
            
            // Create remote session description.
            RTCSessionDescription sdp = new RTCSessionDescription(RTCSdpType.ANSWER, sdpString);

            // Set remote description
            setRemoteDescription(sdp, false);
        }
        catch (Exception e) {
            LOG.error("Error parsing answer JSON", e);
        }
    }

    @Override
    public void handleCandidate(IceCandidateMessage message) {
        LOG.info("Received ICE candidate");

        try {
            IceCandidateMessage.IceCandidateData data = message.getData();
            String sdpMid = data.getSdpMid();
            int sdpMLineIndex = data.getSdpMLineIndex();
            String candidate = data.getCandidate();

            LOG.info("Successfully parsed ICE candidate JSON");

            RTCIceCandidate iceCandidate = new RTCIceCandidate(sdpMid, sdpMLineIndex, candidate);
            addIceCandidate(iceCandidate);
        }
        catch (Exception e) {
            LOG.error("Error parsing ICE candidate JSON", e);
        }
    }



    /**
     * Implementation of PeerConnectionObserver to handle WebRTC events.
     */
    private class PeerConnectionObserverImpl implements PeerConnectionObserver {

        @Override
        public void onIceCandidate(RTCIceCandidate candidate) {
            LOG.info("New ICE candidate: {}", candidate.sdp);

            if (onIceCandidateGenerated != null) {
                onIceCandidateGenerated.accept(candidate);
            }
        }

        @Override
        public void onConnectionChange(RTCPeerConnectionState state) {
            LOG.info("Connection state changed to: {}", state);
        }

        @Override
        public void onIceConnectionChange(RTCIceConnectionState state) {
            LOG.info("ICE connection state changed to: {}", state);
        }

        @Override
        public void onIceGatheringChange(RTCIceGatheringState state) {
            LOG.info("ICE gathering state changed to: {}", state);
        }

        @Override
        public void onSignalingChange(RTCSignalingState state) {
            LOG.info("Signaling state changed to: {}", state);
        }

        @Override
        public void onDataChannel(RTCDataChannel dataChannel) {
            LOG.info("Data channel created: {}", dataChannel.getLabel());
        }
        
        @Override
        public void onRenegotiationNeeded() {
            LOG.info("Renegotiation needed");
            createOffer();
        }
        
        @Override
        public void onAddTrack(RTCRtpReceiver receiver, MediaStream[] mediaStreams) {
            LOG.info("Track added: {}", receiver.getTrack().getKind());
        }
        
        @Override
        public void onRemoveTrack(RTCRtpReceiver receiver) {
            LOG.info("Track removed: {}", receiver.getTrack().getKind());
        }
        
        @Override
        public void onTrack(RTCRtpTransceiver transceiver) {
            MediaStreamTrack track = transceiver.getReceiver().getTrack();
            String kind = track.getKind();
            
            LOG.info("{} track added to transceiver", kind);
            
            if (onTrackReceived != null) {
                onTrackReceived.accept(track);
            }
        }
    }
}