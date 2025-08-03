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

package dev.onvoid.webrtc.examples.web.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.onvoid.webrtc.examples.web.model.SignalingMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A WebSocket client implementation using JDK internal WebSockets (java.net.http.WebSocket).
 * This client supports secure WebSocket connections (WSS), message sending/receiving,
 * and automatic heartbeat to keep the connection alive.
 *
 * @author Alex Andres
 */
public class WebSocketClient {

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketClient.class);

    /** The interval between heartbeat messages in seconds to keep the WebSocket connection alive. */
    private static final long HEARTBEAT_INTERVAL_SECONDS = 15;

	/** JSON serializer/deserializer for processing WebSocket messages. */
	private final ObjectMapper jsonMapper = new ObjectMapper();

    private final URI serverUri;
    private final List<String> subprotocols;
    private final List<Consumer<String>> messageListeners;
    private final List<Runnable> closeListeners;
    private final List<Consumer<Throwable>> errorListeners;

    private WebSocket webSocket;
    private ScheduledExecutorService heartbeatExecutor;
    private boolean connected = false;


	/**
	 * Creates a new WebSocket client.
	 *
	 * @param serverUri    The URI of the WebSocket server (e.g., "wss://localhost:8443/ws").
	 * @param subprotocols The WebSocket subprotocols to use (e.g., "ws-signaling").
	 */
	public WebSocketClient(URI serverUri, List<String> subprotocols) {
		this.serverUri = serverUri;
		this.subprotocols = subprotocols;
		this.messageListeners = new CopyOnWriteArrayList<>();
		this.closeListeners = new CopyOnWriteArrayList<>();
		this.errorListeners = new CopyOnWriteArrayList<>();
	}

	/**
	 * Creates a new WebSocket client with a single subprotocol.
	 *
	 * @param serverUri   The URI of the WebSocket server.
	 * @param subprotocol The WebSocket subprotocol to use.
	 */
	public WebSocketClient(URI serverUri, String subprotocol) {
		this(serverUri, List.of(subprotocol));
	}

	/**
	 * Connects to the WebSocket server.
	 *
	 * @return A CompletableFuture that completes when the connection is established.
	 */
	public CompletableFuture<WebSocket> connect() {
		if (connected) {
			return CompletableFuture.completedFuture(webSocket);
		}

		WebSocket.Listener listener = new WebSocketListener();

		// Create an HTTP client that accepts all SSL certificates (useful for self-signed certs in development).
		HttpClient client = HttpClient.newBuilder()
				.sslContext(TrustAllCertificates.createSslContext())
				.connectTimeout(Duration.ofSeconds(10))
				.build();

		// Build the WebSocket with our listener and subprotocols.
		WebSocket.Builder wsBuilder = client.newWebSocketBuilder()
				.connectTimeout(Duration.ofSeconds(10));

		// Add subprotocols if provided.
		if (subprotocols != null && !subprotocols.isEmpty()) {
			for (String protocol : subprotocols) {
				wsBuilder.subprotocols(protocol);
			}
		}
        
        LOG.info("Connecting to WebSocket server: {}", serverUri);

		return wsBuilder.buildAsync(serverUri, listener)
				.thenApply(ws -> {
					this.webSocket = ws;
					this.connected = true;

					// Start heartbeat to keep connection alive.
					startHeartbeat();

					LOG.info("Connected to WebSocket server: {}", serverUri);
					return ws;
				})
				.exceptionally(ex -> {
					LOG.error("Failed to connect to WebSocket server: {}", ex.getMessage());
					notifyErrorListeners(ex);
					return null;
				});
	}

	/**
	 * Sends a text message to the WebSocket server.
	 *
	 * @param message The message to send.
	 *
	 * @return A CompletableFuture that completes when the message is sent.
	 */
	public CompletableFuture<WebSocket> sendMessage(String message) {
		if (!connected || webSocket == null) {
			return CompletableFuture.failedFuture(
					new IllegalStateException("Not connected to WebSocket server"));
		}

		LOG.debug("Sending message: {}", message);

		return webSocket.sendText(message, true);
	}

	/**
	 * Closes the WebSocket connection.
	 *
	 * @param statusCode The WebSocket close status code.
	 * @param reason     The reason for closing.
	 *
	 * @return A CompletableFuture that completes when the connection is closed.
	 */
	public CompletableFuture<WebSocket> disconnect(int statusCode, String reason) {
		if (!connected || webSocket == null) {
			return CompletableFuture.completedFuture(null);
		}

		stopHeartbeat();

		LOG.info("Disconnecting from WebSocket server: {}", serverUri);

		return webSocket.sendClose(statusCode, reason)
				.thenApply(ws -> {
					this.connected = false;
					this.webSocket = null;
					return ws;
				});
	}

	/**
	 * Adds a listener for incoming text messages.
	 *
	 * @param listener The message listener.
	 */
	public void addMessageListener(Consumer<String> listener) {
		messageListeners.add(listener);
	}

	/**
	 * Removes a message listener.
	 *
	 * @param listener The message listener to remove.
	 */
	public void removeMessageListener(Consumer<String> listener) {
		messageListeners.remove(listener);
	}

	/**
	 * Adds a listener for connection close events.
	 *
	 * @param listener The close listener.
	 */
	public void addCloseListener(Runnable listener) {
		closeListeners.add(listener);
	}

    /**
     * Removes a close listener.
     * 
     * @param listener The close listener to remove.
     */
	public void removeCloseListener(Runnable listener) {
		closeListeners.remove(listener);
	}

	/**
	 * Adds a listener for connection errors.
	 *
	 * @param listener The error listener.
	 */
	public void addErrorListener(Consumer<Throwable> listener) {
		errorListeners.add(listener);
	}

	/**
	 * Removes an error listener.
	 *
	 * @param listener The error listener to remove.
	 */
	public void removeErrorListener(Consumer<Throwable> listener) {
		errorListeners.remove(listener);
	}

	/**
	 * Checks if the client is connected to the WebSocket server.
	 *
	 * @return true if connected, false otherwise.
	 */
	public boolean isConnected() {
		return connected;
	}

	/**
	 * Starts the heartbeat to keep the WebSocket connection alive.
	 */
	private void startHeartbeat() {
		// Ensure any existing heartbeat is stopped.
		stopHeartbeat();

		heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "websocket-heartbeat");
			t.setDaemon(true);
			return t;
		});

		heartbeatExecutor.scheduleAtFixedRate(() -> {
			if (connected && webSocket != null) {
				LOG.debug("Sending heartbeat");

				sendHeartbeat();
			}
		}, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
	}

	/**
	 * Sends a heartbeat message to the WebSocket server to keep the connection alive.
	 */
	private void sendHeartbeat() {
		SignalingMessage heartbeat = new SignalingMessage("heartbeat");
		String heartbeatJson;

		try {
			heartbeatJson = jsonMapper.writeValueAsString(heartbeat);
		}
		catch (JsonProcessingException e) {
			LOG.error("Failed to send heartbeat", e);
			return;
		}

		sendMessage(heartbeatJson)
				.exceptionally(ex -> {
					LOG.error("Failed to send heartbeat: {}", ex.getMessage());
					return null;
				});
	}

	/**
	 * Stops the heartbeat.
	 */
    private void stopHeartbeat() {
        if (heartbeatExecutor != null && !heartbeatExecutor.isShutdown()) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
		}
	}

	/**
	 * Notifies all registered message listeners with the received message.
	 * Any exceptions thrown by listeners are caught and logged to prevent
	 * them from affecting other listeners or the WebSocket connection.
	 *
	 * @param message The message received from the WebSocket server.
	 */
	private void notifyMessageListeners(String message) {
		for (Consumer<String> listener : messageListeners) {
			try {
				listener.accept(message);
			}
			catch (Exception e) {
				LOG.error("Error in message listener", e);
			}
		}
	}

	/**
	 * Notifies all registered close listeners that the WebSocket connection has closed.
	 * Any exceptions thrown by listeners are caught and logged to prevent
	 * them from affecting other listeners.
	 */
	private void notifyCloseListeners() {
		for (Runnable listener : closeListeners) {
			try {
				listener.run();
			}
			catch (Exception e) {
				LOG.error("Error in close listener", e);
			}
		}
	}

	/**
	 * Notifies all registered error listeners about a WebSocket error.
	 * Any exceptions thrown by listeners are caught and logged to prevent
	 * them from affecting other listeners.
	 *
	 * @param error The error that occurred during WebSocket communication.
	 */
	private void notifyErrorListeners(Throwable error) {
		for (Consumer<Throwable> listener : errorListeners) {
			try {
				listener.accept(error);
			}
			catch (Exception e) {
				LOG.error("Error in error listener", e);
			}
		}
	}



	/**
	 * WebSocket listener implementation.
	 */
	private class WebSocketListener implements WebSocket.Listener {

		private final StringBuilder messageBuilder = new StringBuilder();


        @Override
        public void onOpen(WebSocket webSocket) {
            LOG.debug("WebSocket connection opened");
            WebSocket.Listener.super.onOpen(webSocket);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            messageBuilder.append(data);
            
            if (last) {
                String message = messageBuilder.toString();
                messageBuilder.setLength(0);
                
                LOG.debug("Received message: {}", message);
                notifyMessageListeners(message);
            }
            
            return WebSocket.Listener.super.onText(webSocket, data, last);
		}

		@Override
		public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
			LOG.debug("Received binary data, size: {}", data.remaining());
			return WebSocket.Listener.super.onBinary(webSocket, data, last);
		}

		@Override
		public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
			LOG.debug("Received ping");
			return WebSocket.Listener.super.onPing(webSocket, message);
		}

		@Override
		public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
			LOG.debug("Received pong");
			return WebSocket.Listener.super.onPong(webSocket, message);
		}

		@Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            LOG.info("WebSocket connection closed: {} - {}", statusCode, reason);
            connected = false;
            WebSocketClient.this.webSocket = null;
            stopHeartbeat();
            notifyCloseListeners();
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }
        
        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            LOG.error("WebSocket error", error);
            notifyErrorListeners(error);
            WebSocket.Listener.super.onError(webSocket, error);
        }
    }



    /**
     * Utility class to create an SSL context that trusts all certificates.
     * This is useful for development with self-signed certificates.
     */
    private static class TrustAllCertificates {

		public static javax.net.ssl.SSLContext createSslContext() {
			try {
				javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
				sslContext.init(null, new javax.net.ssl.TrustManager[] {
						new javax.net.ssl.X509TrustManager() {
							public java.security.cert.X509Certificate[] getAcceptedIssuers() {
								return new java.security.cert.X509Certificate[0];
							}

							public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {
							}

							public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {
							}
						}
				}, new java.security.SecureRandom());

				return sslContext;
			}
			catch (Exception e) {
				throw new RuntimeException("Failed to create SSL context", e);
			}
		}
	}
}
