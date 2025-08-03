import { SignalingChannel } from './SignalingChannel.js';
import { PeerConnectionManager } from './PeerConnectionManager.js';

class WebRTCClient {

    constructor() {
        // Configuration.
        this.serverUrl = "wss://localhost:8443/ws";
        this.iceServers = [
            { urls: "stun:stun.l.google.com:19302" }
        ];
        
        // DOM elements.
        this.remoteVideo = document.getElementById("remoteVideo");
        this.videoContainer = document.getElementById("videoContainer");
        this.stopButton = document.getElementById("stopButton");
        this.statusElement = document.getElementById("status");
        this.participantsList = document.getElementById("participantsList");
        
        // Initialize components.
        this.signalingChannel = null;
        this.peerConnectionManager = null;
        this.participants = new Map();
        
        // Set up event listeners.
        this.stopButton.addEventListener("click", () => this.disconnect());
        
        // Set the initial status.
        this.setStatus("Click \"Start Connection\" to begin");

        this.connect();
    }

    setStatus(status) {
        this.statusElement.textContent = status;

        console.log(status);
    }

    connect() {
        this.setStatus("Connecting to the signaling server...");
        
        // Initialize signaling channel.
        this.signalingChannel = new SignalingChannel(
            this.serverUrl,
            () => this.handleSignalingOpen(),
            () => this.handleSignalingClose(),
            (message) => this.handleSignalingMessage(message),
            (error) => this.handleSignalingError(error)
        );
        
        // Connect to the signaling server.
        this.signalingChannel.connect();
    }

    disconnect() {
        this.setStatus("Closing connection");

        // Close peer connection
        if (this.peerConnectionManager) {
            this.peerConnectionManager.close();
            this.peerConnectionManager = null;
        }

        // Close signaling channel
        if (this.signalingChannel) {
            this.signalingChannel.close();
            this.signalingChannel = null;
        }

        // Clear remote video
        if (this.remoteVideo.srcObject) {
            this.remoteVideo.srcObject.getTracks().forEach(track => track.stop());
            this.remoteVideo.srcObject = null;
        }

        // Hide video container
        this.videoContainer.style.display = 'none';

        // Clear participants list
        this.participants.clear();
        this.updateParticipantsList();

        // Update UI
        this.stopButton.disabled = true;
        this.setStatus("Connection closed");
    }

    joinRoom(roomName) {
        // First, send a join message to the room.
        const joinMessage = {
            type: "join",
            from: this.peerConnectionManager.getUserId(),
            data: {
                room: roomName,
                userInfo: {
                    name: "Web Client"
                }
            }
        };
        this.signalingChannel.send(joinMessage);
        this.setStatus('Joining room: default-room');
    }

    handleSignalingOpen() {
        this.setStatus("Connected to the signaling server");

        // Initialize peer connection manager.
        this.peerConnectionManager = new PeerConnectionManager(
            this.iceServers,
            (event) => this.handleTrack(event),
            (candidate) => this.handleLocalCandidate(candidate),
            (state) => this.handleIceConnectionStateChange(state),
            (state) => this.handleConnectionStateChange(state)
        );

        // Add ourselves to the participant list.
        const myUserId = this.peerConnectionManager.getUserId();
        this.participants.set(myUserId, { name: "Me (Local)" });
        this.updateParticipantsList();

        this.joinRoom("default-room");

        // Update UI.
        this.stopButton.disabled = false;
    }

    handleSignalingMessage(message) {
        switch(message.type) {
            case 'answer':
                this.handleAnswer(message);
                break;
            case 'ice-candidate':
                this.handleRemoteCandidate(message);
                break;
            case 'offer':
                this.handleOffer(message);
                break;
            case 'join':
                this.handleUserJoined(message);
                break;
            case 'leave':
                this.handleUserLeft(message);
                break;
            default:
                console.log('Unknown message type:', message.type);
        }
    }

    handleUserJoined(message) {
        const userId = message.data.userInfo.userId;
        this.setStatus('User joined: ' + userId);

        // Add user to participants map if not already present.
        if (!this.participants.has(userId)) {
            const userInfo = message.data.userInfo || { userId: userId };
            this.participants.set(userId, userInfo);

            // Update the participant list in the UI.
            this.updateParticipantsList();
        }
    }

    handleUserLeft(message) {
        const userId = message.from;
        this.setStatus('User left: ' + userId);
        
        // Remove user from participants map if present
        if (this.participants.has(userId)) {
            this.participants.delete(userId);
            
            // Update the participant list in the UI
            this.updateParticipantsList();
        }
    }
    
    async handleOffer(message) {
        this.setStatus('Received offer from ' + message.from);
        console.log('Received offer:', message);

        // Initialize WebRTC.
        this.startWebRTC();
        
        try {
            // Set the offer to the peer connection.
            await this.peerConnectionManager.handleOffer(message);
            this.setStatus('Remote offer set successfully, creating answer');
            
            // Create and send answer
            const answer = await this.peerConnectionManager.createAnswer();
            this.setStatus('Sending answer to ' + message.from);
            this.signalingChannel.send(answer);
        }
        catch (error) {
            this.setStatus('Failed to process offer: ' + error);
            console.error('Error handling offer:', error);
        }
    }
    
    updateParticipantsList() {
        // Clear the current list
        this.participantsList.innerHTML = '';
        
        // Add each participant to the list
        this.participants.forEach((userInfo, userId) => {
            const listItem = document.createElement('li');
            listItem.textContent = userInfo.name || userId;
            listItem.setAttribute('data-user-id', userId);
            this.participantsList.appendChild(listItem);
        });
    }

    handleSignalingClose() {
        this.setStatus('Disconnected from signaling server');
        this.disconnect();
    }

    handleSignalingError(error) {
        this.setStatus('Error connecting to signaling server');
        console.error('WebSocket error:', error);
    }

    async startWebRTC() {
        this.peerConnectionManager.initialize();
        
        // Add local media if available
        // const mediaAdded = await this.peerConnectionManager.addLocalMedia();
        // if (!mediaAdded) {
        //     this.setStatus('Failed to access camera/microphone. Creating offer without local media.');
        // }
        
        // Create and send offer
        // try {
        //     const offer = await this.peerConnectionManager.createOffer();
        //     this.setStatus('Sending offer to Java server');
        //     this.signalingChannel.send(offer);
        // }
        // catch (error) {
        //     this.setStatus('Failed to create offer: ' + error);
        // }
    }

    handleTrack(event) {
        this.setStatus('Received remote track');
        console.log('Track event:', event);

        if (event.streams && event.streams[0]) {
            // If remoteVideo already has a srcObject, we need to add the new track to it
            // instead of replacing the entire stream.
            if (this.remoteVideo.srcObject) {
                // Check if this track already exists in the current stream.
                const existingTrack = this.remoteVideo.srcObject.getTracks().find(
                    track => track.id === event.track.id
                );
                
                // Only add the track if it doesn't already exist.
                if (!existingTrack) {
                    // Add the new track to the existing stream.
                    this.remoteVideo.srcObject.addTrack(event.track);
                    this.setStatus(`Added ${event.track.kind} track to existing stream`);
                }
            }
            else {
                // First track, set the stream directly.
                this.remoteVideo.srcObject = event.streams[0];
                this.setStatus(`Set initial ${event.track.kind} stream`);
            }
            
            // Show the video container when we receive a track.
            this.videoContainer.style.display = 'block';
            
            // Ensure the video plays.
            this.remoteVideo.play().catch(error => {
                console.error('Error playing video:', error);
                this.setStatus('Error playing video: ' + error.message);
            });
        }
        else {
            console.warn('Received track event without streams');
            this.setStatus('Received track without stream data');
        }
    }

    handleLocalCandidate(candidate) {
        this.signalingChannel.send(candidate);
    }

    handleIceConnectionStateChange(state) {
        this.setStatus('ICE connection state: ' + state);
        
        // Hide video if connection is disconnected, failed, or closed
        if (['disconnected', 'failed', 'closed'].includes(state)) {
            this.videoContainer.style.display = 'none';
        }
    }

    handleConnectionStateChange(state) {
        this.setStatus('Connection state: ' + state);
        
        // Show video only when connected
        if (state === 'connected') {
            this.videoContainer.style.display = 'block';
        } else if (['disconnected', 'failed', 'closed'].includes(state)) {
            this.videoContainer.style.display = 'none';
        }
    }

    async handleAnswer(message) {
        this.setStatus('Received answer from ' + message.from + ', setting remote description');

        try {
            await this.peerConnectionManager.handleAnswer(message);
            this.setStatus('Remote description set successfully');
        }
        catch (error) {
            this.setStatus('Failed to set remote description: ' + error);
        }
    }

    async handleRemoteCandidate(message) {
        try {
            this.setStatus('Received ICE candidate from ' + message.from);

            await this.peerConnectionManager.addIceCandidate(message);
        }
        catch (error) {
            console.error("Error handling remote candidate:", error);
        }
    }
}

export { WebRTCClient };