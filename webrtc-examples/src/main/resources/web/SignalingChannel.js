/**
 * SignalingChannel handles WebSocket communication for WebRTC signaling.
 * It includes a heartbeat mechanism to keep the connection alive and detect disconnections early.
 */
class SignalingChannel {

    /**
     * Creates a new SignalingChannel instance.
     *
     * @param {string} serverUrl - The WebSocket server URL.
     * @param {function} onMessage - Callback for received messages.
     * @param {function} onOpen - Callback for connection open.
     * @param {function} onClose - Callback for connection close.
     * @param {function} onError - Callback for connection errors.
     */
    constructor(serverUrl, onOpen, onClose, onMessage, onError) {
        this.serverUrl = serverUrl;
        this.onMessageCallback = onMessage;
        this.onOpenCallback = onOpen;
        this.onCloseCallback = onClose;
        this.onErrorCallback = onError;
        this.socket = null;
        this.heartbeatInterval = 10000; // 10 seconds
        this.heartbeatTimer = null;
    }

    /**
     * Establishes a WebSocket connection to the signaling server.
     * Automatically starts the heartbeat mechanism when connected.
     */
    connect() {
        this.socket = new WebSocket(this.serverUrl, "ws-signaling");
        
        this.socket.onopen = () => {
            this.startHeartbeat();

            if (this.onOpenCallback) {
                this.onOpenCallback();
            }
        };
        
        this.socket.onmessage = (event) => {
            console.log("Received message:", event.data);
            const message = JSON.parse(event.data);
            
            // Handle heartbeat response.
            if (message.type === "heartbeat-ack") {
                // Heartbeat acknowledged.
                return;
            }
            
            if (this.onMessageCallback) {
                this.onMessageCallback(message);
            }
        };
        
        this.socket.onclose = () => {
            this.stopHeartbeat();

            if (this.onCloseCallback) {
                this.onCloseCallback();
            }
        };
        
        this.socket.onerror = (error) => {
            this.stopHeartbeat();

            if (this.onErrorCallback) {
                this.onErrorCallback(error);
            }
        };
    }
    
    /**
     * Starts the heartbeat mechanism to keep the WebSocket connection alive.
     * Sends a heartbeat message every this.heartbeatInterval millisecond.
     */
    startHeartbeat() {
        // Clear any existing timer.
        this.stopHeartbeat();
        
        this.heartbeatTimer = setInterval(() => {
            if (this.isConnected()) {
                this.send({ 
                    type: "heartbeat",
                    from: this.getUserId()
                });
            }
            else {
                this.stopHeartbeat();
            }
        }, this.heartbeatInterval);
    }
    
    // Helper method to get the current user ID
    getUserId() {
        // In a real application, this would be a unique identifier for the user
        return "web-client";
    }
    
    /**
     * Stops the heartbeat mechanism.
     * Called automatically when the connection is closed or on error.
     */
    stopHeartbeat() {
        if (this.heartbeatTimer) {
            clearInterval(this.heartbeatTimer);
            this.heartbeatTimer = null;
        }
    }

    send(message) {
        if (this.isConnected()) {
            this.socket.send(JSON.stringify(message));
        }
        else {
            console.error("WebSocket is not open. Cannot send message:", message);
        }
    }

    /**
     * Closes the WebSocket connection and stops the heartbeat mechanism.
     */
    close() {
        this.stopHeartbeat();

        if (this.isConnected()) {
            this.socket.close();
        }
    }

    /**
     * Checks if the WebSocket connection is currently open.
     * 
     * @returns {boolean} True if the connection is open.
     */
    isConnected() {
        return this.socket && this.socket.readyState === WebSocket.OPEN;
    }
}

export { SignalingChannel };