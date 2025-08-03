/**
 * Manages WebRTC peer connections for real-time communication.
 *
 * This class encapsulates the WebRTC API functionality including
 * - Connection initialization
 * - Local media stream management
 * - SDP offer/answer creation and handling
 * - ICE candidate processing
 * - Connection state monitoring
 *
 * @class
 * @property {Object} configuration - The RTCPeerConnection configuration
 * @property {function} onTrackCallback - Callback for track events
 * @property {function} onIceCandidateCallback - Callback for ICE candidate events
 * @property {function} onIceConnectionStateChangeCallback - Callback for ICE connection state changes
 * @property {function} onConnectionStateChangeCallback - Callback for connection state changes
 * @property {RTCPeerConnection|null} peerConnection - The WebRTC peer connection object
 * @property {MediaStream|null} localStream - The local media stream
 */
class PeerConnectionManager {

    /**
     * Creates a new PeerConnectionManager instance.
     *
     * @param {Array|Object} iceServers - ICE server configuration for the RTCPeerConnection
     * @param {function} onTrack - Callback function that handles track events
     * @param {function} onIceCandidate - Callback function for ICE candidate events
     * @param {function} onIceConnectionStateChange - Callback function for ICE connection state changes
     * @param {function} onConnectionStateChange - Callback function for connection state changes
     */
    constructor(iceServers, onTrack, onIceCandidate, onIceConnectionStateChange, onConnectionStateChange) {
        this.configuration = { iceServers };
        this.onTrackCallback = onTrack;
        this.onIceCandidateCallback = onIceCandidate;
        this.onIceConnectionStateChangeCallback = onIceConnectionStateChange;
        this.onConnectionStateChangeCallback = onConnectionStateChange;
        this.peerConnection = null;
        this.localStream = null;
    }
    
    // Helper method to get the current user ID
    getUserId() {
        // In a real application, this would be a unique identifier for the user
        return "web-client";
    }
    
    // Helper method to get the remote peer ID
    getRemotePeerId() {
        // In a real application, this would be the ID of the peer we're communicating with
        // For simplicity, we're using a fixed value here
        return "java-client";
    }

    initialize() {
        this.peerConnection = new RTCPeerConnection(this.configuration);
        
        this.peerConnection.ontrack = (event) => {
            if (this.onTrackCallback) {
                this.onTrackCallback(event);
            }
        };
        
        this.peerConnection.onicecandidate = (event) => {
            if (event.candidate && this.onIceCandidateCallback) {
                const candidate = {
                    type: "ice-candidate",
                    from: this.getUserId(),
                    to: this.getRemotePeerId(),
                    data: {
                        candidate: event.candidate.candidate,
                        sdpMid: event.candidate.sdpMid,
                        sdpMLineIndex: event.candidate.sdpMLineIndex
                    }
                };
                this.onIceCandidateCallback(candidate);
            }
        };
        
        this.peerConnection.oniceconnectionstatechange = () => {
            if (this.onIceConnectionStateChangeCallback) {
                this.onIceConnectionStateChangeCallback(this.peerConnection.iceConnectionState);
            }
        };
        
        this.peerConnection.onconnectionstatechange = () => {
            if (this.onConnectionStateChangeCallback) {
                this.onConnectionStateChangeCallback(this.peerConnection.connectionState);
            }
        };
    }

    async addLocalMedia() {
        try {
            this.localStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: true });
            this.localStream.getTracks().forEach(track => {
                this.peerConnection.addTrack(track, this.localStream);
            });
            return true;
        }
        catch (error) {
            console.error("Error accessing media devices:", error);
            return false;
        }
    }

    async createOffer() {
        try {
            const offer = await this.peerConnection.createOffer();
            await this.peerConnection.setLocalDescription(offer);
            return {
                type: "offer",
                from: this.getUserId(),
                to: this.getRemotePeerId(),
                data: {
                    sdp: this.peerConnection.localDescription.sdp
                }
            };
        }
        catch (error) {
            console.error("Error creating offer:", error);
            throw error;
        }
    }

    async handleOffer(message) {
        try {
            // Extract SDP from the data object in the message format
            const sdp = message.data.sdp.replace(/\\n/g, '\n');
            const sessionDescription = new RTCSessionDescription({
                type: "offer",
                sdp: sdp
            });
            await this.peerConnection.setRemoteDescription(sessionDescription);
            return true;
        }
        catch (error) {
            console.error("Error setting remote description for offer:", error);
            throw error;
        }
    }
    
    async createAnswer() {
        try {
            const answer = await this.peerConnection.createAnswer();
            await this.peerConnection.setLocalDescription(answer);

            console.log("Local answer:", answer);
            console.log("Local description:", this.peerConnection.localDescription);

            return {
                type: "answer",
                from: this.getUserId(),
                to: this.getRemotePeerId(),
                data: {
                    sdp: this.peerConnection.localDescription.sdp
                }
            };
        }
        catch (error) {
            console.error("Error creating answer:", error);
            throw error;
        }
    }

    async handleAnswer(message) {
        try {
            // Extract SDP from the data object in the new message format
            const sdp = message.data.sdp.replace(/\\n/g, '\n');
            const sessionDescription = new RTCSessionDescription({
                type: "answer",
                sdp: sdp
            });
            await this.peerConnection.setRemoteDescription(sessionDescription);
        }
        catch (error) {
            console.error("Error setting remote description:", error);
            throw error;
        }
    }

    async addIceCandidate(message) {
        try {
            // Extract candidate information from the data object in the new message format
            const candidate = new RTCIceCandidate({
                sdpMid: message.data.sdpMid,
                sdpMLineIndex: message.data.sdpMLineIndex,
                candidate: message.data.candidate
            });
            await this.peerConnection.addIceCandidate(candidate);

            console.log('ICE candidate added successfully');
        }
        catch (error) {
            console.error('Error adding ICE candidate:', error);
            throw error;
        }
    }

    close() {
        if (this.localStream) {
            this.localStream.getTracks().forEach(track => track.stop());
            this.localStream = null;
        }
        
        if (this.peerConnection) {
            this.peerConnection.close();
            this.peerConnection = null;
        }
    }
}

export { PeerConnectionManager };