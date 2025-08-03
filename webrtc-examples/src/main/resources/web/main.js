import { WebRTCClient } from './WebRTCClient.js';

// Initialize the WebRTC client when the page loads
document.addEventListener("DOMContentLoaded", () => {
    new WebRTCClient();
});