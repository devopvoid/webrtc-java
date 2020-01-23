/*
 *  Copyright 2014 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */
/*
 * Copyright 2019 Alex Andres
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

package dev.onvoid.webrtc.demo.apprtc;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.demo.apprtc.AppRTCMessage.Type;
import dev.onvoid.webrtc.demo.model.Contact;
import dev.onvoid.webrtc.demo.model.Room;
import dev.onvoid.webrtc.demo.model.RoomParameters;
import dev.onvoid.webrtc.demo.net.SignalingClient;
import dev.onvoid.webrtc.demo.net.SignalingListener;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * Negotiates signaling for chatting with https://appr.tc "rooms". Uses the
 * client<->server specifics of the apprtc AppEngine webapp.
 */
public class AppRTCWebSocketClient implements SignalingClient, WebSocket.Listener {

	private static final Logger LOGGER = System.getLogger(AppRTCWebSocketClient.class.getName());

	private static final String SERVER_URL = "https://appr.tc";

	private static final String JOIN_URL = "%s/join/%s";

	private static final String MESSAGE_URL = "%s/message/%s/%s";

	private static final String LEAVE_URL = "%s/leave/%s/%s";



	private enum WebSocketConnectionState {
		NEW,
		CONNECTED,
		REGISTERED,
		CLOSED,
		ERROR
	}



	private enum ConnectionState {
		NEW,
		CONNECTED,
		CLOSED,
		ERROR
	}



	private enum MessageType {
		MESSAGE,
		LEAVE
	}



	private final List<AppRTCMessage> wsSendQueue;

	private final AppRTCJsonCodec codec;

	private final HttpClient client;

	private ConnectionState roomState;

	private WebSocketConnectionState state;

	private WebSocket webSocket;

	private AppRTCConnectionParameters connectionParameters;

	private AppRTCSignalingParameters signalingParameters;

	private SignalingListener listener;


	public AppRTCWebSocketClient() {
		wsSendQueue = new ArrayList<>();
		codec = new AppRTCJsonCodec();
		client = HttpClient.newBuilder().build();
		state = WebSocketConnectionState.NEW;
		roomState = ConnectionState.NEW;
	}

	@Override
	public Set<Contact> getContacts() {
		return Set.of();
	}

	@Override
	public void joinRoom(Contact asContact, Room room) {
		connect(SERVER_URL, room);
	}

	@Override
	public void leaveRoom() {
		LOGGER.log(Level.INFO, "Disconnect. Room state: " + roomState);

		if (roomState == ConnectionState.CONNECTED) {
			LOGGER.log(Level.INFO, "Closing room");

			sendPostMessage(MessageType.LEAVE, null);
		}

		roomState = ConnectionState.CLOSED;

		if (webSocket != null) {
			LOGGER.log(Level.INFO, "Disconnect WebSocket in state: " + state);

			if (state == WebSocketConnectionState.REGISTERED) {
				// Send "bye" to WebSocket server.
				send(new AppRTCMessage(Type.BYE));

				state = WebSocketConnectionState.CONNECTED;

				// Send http DELETE to http WebSocket server.
				sendWSSMessage("DELETE", null);
			}

			// Close WebSocket in CONNECTED or ERROR states only.
			if (state == WebSocketConnectionState.CONNECTED ||
				state == WebSocketConnectionState.ERROR) {
				webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "").join();

				state = WebSocketConnectionState.CLOSED;
			}

			LOGGER.log(Level.INFO, "Disconnecting WebSocket done");
		}
	}

	@Override
	public void send(Contact contact, Object obj) {
		System.out.println("send: " + obj.getClass());

		if (obj instanceof RTCSessionDescription) {
			RTCSessionDescription desc = (RTCSessionDescription) obj;

			if (desc.sdpType == RTCSdpType.ANSWER) {
				sendAnswerSdp(desc);
			}
			else if (desc.sdpType == RTCSdpType.OFFER) {
				sendOfferSdp(desc);
			}
		}
		else if (obj instanceof RTCIceCandidate) {
			sendIceCandidate((RTCIceCandidate) obj);
		}
	}

	@Override
	public void setSignalingListener(SignalingListener listener) {
		this.listener = listener;
	}

	StringBuilder text = new StringBuilder();

	@Override
	public void onOpen(WebSocket webSocket) {
		webSocket.request(1);

		LOGGER.log(Level.INFO, "WebSocket connection opened");

		state = WebSocketConnectionState.CONNECTED;
	}

	@Override
	public CompletionStage<?> onText(WebSocket webSocket, CharSequence data,
									 boolean last) {
		webSocket.request(1);

		if (state != WebSocketConnectionState.REGISTERED) {
			LOGGER.log(Level.ERROR, "Got WebSocket message in non registered state.");
			return null;
		}

		text.append(data);

		if (last) {
			String message = text.toString();

			LOGGER.log(Level.INFO, "WSS->C: " + message);

			try {
				handleMessage(message);
			}
			catch (Exception e) {
				reportError("WebSocket message JSON parsing error", e);
			}

			text = new StringBuilder();
		}

		return null;
	}

	@Override
	public CompletionStage<?> onClose(WebSocket webSocket, int statusCode,
									  String reason) {
		if (state != WebSocketConnectionState.CLOSED) {
			state = WebSocketConnectionState.CLOSED;

			listener.onRoomLeft();
		}
		return null;
	}

	@Override
	public void onError(WebSocket webSocket, Throwable error) {
		reportError("WebSocket error", error);
	}

	private void send(AppRTCMessage message) {
		switch (state) {
			case NEW:
			case CONNECTED:
				// Store outgoing messages and send them after WebSocket client
				// is registered.
				LOGGER.log(Level.INFO, "WS ACC: " + message);
				wsSendQueue.add(message);
				return;

			case ERROR:
			case CLOSED:
				LOGGER.log(Level.ERROR, "WebSocket send() in error or closed state : " + message);
				return;

			case REGISTERED:
				sendWebSocketCommand(new AppRTCSendCommand(codec.toJsonMessage(message)));
				break;
		}
	}

	private void sendWSSMessage(final String method, final String message) {
		String roomId = connectionParameters.roomId;
		String clientId = signalingParameters.clientId;
		String postUrl = signalingParameters.wssPostUrl + "/" + roomId + "/" + clientId;

		LOGGER.log(Level.INFO, "WS " + method + " : " + postUrl + " : " + message);

		HttpRequest.BodyPublisher publisher = isNull(message) ?
				HttpRequest.BodyPublishers.noBody() :
				HttpRequest.BodyPublishers.ofString(message);

		HttpRequest.Builder request = HttpRequest.newBuilder()
				.setHeader("REFERER", SERVER_URL)
				.uri(URI.create(postUrl));

		if (method.equals("POST")) {
			request.POST(publisher);
		}
		else if (method.equals("DELETE")) {
			request.DELETE();
		}

		client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
				.thenApply(HttpResponse::body)
				.exceptionally(throwable -> {
					reportError("WS " + method + " error", throwable);
					return null;
				});
	}

	private CompletableFuture<WebSocket> sendWebSocketCommand(AppRTCCommand command) {
		String json = codec.toJsonCommand(command);

		LOGGER.log(Level.INFO, "C->WSS: " + json);

		return webSocket.sendText(json, true)
				.exceptionally(e -> {
					reportError("Send message failed", e);
					return null;
				});
	}

	private void sendWebSocketMessage(AppRTCMessage message) {
		String json = codec.toJsonMessage(message);

		LOGGER.log(Level.INFO, "C->WSS: " + json);

		webSocket.sendText(json, true)
				.exceptionally(e -> {
					reportError("Send message failed", e);
					return null;
				});
	}

	private void signalingParametersReady(AppRTCSignalingParameters signalingParameters) {
		LOGGER.log(Level.INFO, "Room connection completed");
		LOGGER.log(Level.INFO, "ClientId: " + signalingParameters.clientId);
		LOGGER.log(Level.INFO, "Initiator: " + signalingParameters.initiator);

		for (RTCIceServer iceServer : signalingParameters.iceServers) {
			LOGGER.log(Level.INFO, "ICEServer: " + iceServer);
		}

		this.signalingParameters = signalingParameters;

		if (connectionParameters.loopback && (!signalingParameters.initiator
				|| signalingParameters.offer != null)) {
			reportError("Loopback room is busy");
			return;
		}
		if (!connectionParameters.loopback && !signalingParameters.initiator
				&& signalingParameters.offer == null) {
			LOGGER.log(Level.INFO, "No offer SDP in room response");
		}

		roomState = ConnectionState.CONNECTED;

		connectWebSocket();
		register();

		RoomParameters roomParameters = new RoomParameters(
				signalingParameters.initiator,
				signalingParameters.iceServers
		);

		listener.onRoomJoined(roomParameters);

		if (!signalingParameters.initiator) {
			if (nonNull(signalingParameters.offer)) {
				listener.onRemoteSessionDescription(new Contact(),
													signalingParameters.offer);
			}
			if (nonNull(signalingParameters.iceCandidates)) {
				// Add remote ICE candidates from room.
				for (RTCIceCandidate iceCandidate : signalingParameters.iceCandidates) {
					listener.onRemoteIceCandidate(new Contact(), iceCandidate);
				}
			}
		}
	}

	private void sendOfferSdp(final RTCSessionDescription sdp) {
		if (roomState != ConnectionState.CONNECTED) {
			reportError("Sending offer SDP in non connected state");
			return;
		}

		AppRTCMessage message = new AppRTCMessage(Type.OFFER, sdp);

		sendPostMessage(MessageType.MESSAGE, message);

		if (connectionParameters.loopback) {
			// In loopback mode rename this offer to answer and route it back.
			RTCSessionDescription sdpAnswer = new RTCSessionDescription(
					RTCSdpType.ANSWER, sdp.sdp);

			listener.onRemoteSessionDescription(new Contact(), sdpAnswer);
		}
	}

	// Send local answer SDP to the other participant.
	private void sendAnswerSdp(final RTCSessionDescription sdp) {
		if (connectionParameters.loopback) {
			LOGGER.log(Level.ERROR, "Sending answer in loopback mode.");
			return;
		}

		sendWebSocketMessage(new AppRTCMessage(Type.ANSWER, sdp));
	}

	private void sendIceCandidate(final RTCIceCandidate candidate) {
		AppRTCMessage message = new AppRTCMessage(Type.CANDIDATE, candidate);

		if (signalingParameters.initiator) {
			// Call initiator sends ice candidates to GAE server.
			if (roomState != ConnectionState.CONNECTED) {
				reportError("Sending ICE candidate in non connected state.");
				return;
			}

			sendPostMessage(MessageType.MESSAGE, message);

			if (connectionParameters.loopback) {
				listener.onRemoteIceCandidate(new Contact(), candidate);
			}
		}
		else {
			// Call receiver sends ice candidates to the WebSocket server.
			sendWebSocketMessage(message);
		}
	}

	private void sendLocalIceCandidateRemovals(final RTCIceCandidate[] candidates) {
		AppRTCMessage message = new AppRTCMessage(Type.REMOVE_CANDIDATES, candidates);

		if (signalingParameters.initiator) {
			// Call initiator sends ice candidates to GAE server.
			if (roomState != ConnectionState.CONNECTED) {
				reportError("Sending ICE candidate removals in non connected state.");
				return;
			}

			sendPostMessage(MessageType.MESSAGE, message);

			if (connectionParameters.loopback) {
				listener.onRemoteIceCandidatesRemoved(new Contact(), candidates);
			}
		}
		else {
			// Call receiver sends ice candidates to websocket server.
			sendWebSocketMessage(message);
		}
	}

	private void connect(String serverUrl, Room room) {
		roomState = ConnectionState.NEW;

		connectionParameters = new AppRTCConnectionParameters(serverUrl,
															  room.getName());

		String roomUrl = String.format(JOIN_URL,
									   connectionParameters.roomUrl,
									   connectionParameters.roomId);

		LOGGER.log(Level.INFO, "Connecting to room: " + roomUrl);

		HttpRequest.Builder request = HttpRequest.newBuilder()
				.setHeader("REFERER", connectionParameters.roomUrl)
				.POST(HttpRequest.BodyPublishers.noBody())
				.uri(URI.create(roomUrl));

		client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
				.thenApply(HttpResponse::body)
				.thenApply(this::parseConnectResponse)
				.thenAccept(this::signalingParametersReady)
				.exceptionally(throwable -> {
					reportError("Connect to room failed", throwable);
					return null;
				});
	}

	private void connectWebSocket() {
		String wssUrl = signalingParameters.wssUrl;

		LOGGER.log(Level.INFO, "Connecting WebSocket to: " + wssUrl);

		if (state != WebSocketConnectionState.NEW) {
			LOGGER.log(Level.ERROR, "WebSocket is already connected.");
			return;
		}

		webSocket = client.newWebSocketBuilder()
				.header("Origin", SERVER_URL)
				.buildAsync(URI.create(wssUrl), this)
				.join();
	}

	private void register() {
		LOGGER.log(Level.INFO, "Registering WebSocket");

		if (state != WebSocketConnectionState.CONNECTED) {
			LOGGER.log(Level.ERROR, "WebSocket register() in state " + state);
			return;
		}

		String roomId = connectionParameters.roomId;
		String clientId = signalingParameters.clientId;

		sendWebSocketCommand(new AppRTCRegisterCommand(roomId, clientId))
				.thenRun(() -> {
					state = WebSocketConnectionState.REGISTERED;

					// Send any previously accumulated messages.
					for (AppRTCMessage message : wsSendQueue) {
						send(message);
					}

					wsSendQueue.clear();
				});
	}

	private void reportError(final String message, final Throwable error) {
		LOGGER.log(Level.ERROR, message, error);

		if (roomState != ConnectionState.ERROR) {
			roomState = ConnectionState.ERROR;
			listener.onError(message);
		}
	}

	private void reportError(final String errorMessage) {
		LOGGER.log(Level.ERROR, errorMessage);

		if (roomState != ConnectionState.ERROR) {
			roomState = ConnectionState.ERROR;
			listener.onError(errorMessage);
		}
	}

	private AppRTCSignalingParameters parseConnectResponse(String response) {
		LOGGER.log(Level.INFO, "Room response: " + response);

		try {
			AppRTCSignalingParameters params = codec.toSignalingParameters(response);

			// Request TURN servers.
			boolean isTurnPresent = params.iceServers.stream()
					.anyMatch(s -> s.urls.stream()
					.anyMatch(url -> url.startsWith("turn:")));

			String iceServerUrl = params.iceServerUrl;

			if (!isTurnPresent && nonNull(iceServerUrl) && !iceServerUrl.isEmpty()) {
				params.iceServers.addAll(requestTurnServers(iceServerUrl));
			}

			return params;
		}
		catch (Exception e) {
			throw new CompletionException(e);
		}
	}

	private List<RTCIceServer> requestTurnServers(String url) throws Exception {
		LOGGER.log(Level.INFO, "Request TURN from: " + url);

		HttpRequest.Builder request = HttpRequest.newBuilder()
				.setHeader("REFERER", SERVER_URL)
				.timeout(Duration.ofMillis(5000))
				.POST(HttpRequest.BodyPublishers.noBody())
				.uri(URI.create(url));

		HttpResponse<String> response = client.send(request.build(),
													HttpResponse.BodyHandlers.ofString());

		int responseCode = response.statusCode();
		if (responseCode != 200) {
			LOGGER.log(Level.WARNING,
					   "Non-200 response when requesting TURN server from " +
						url + " : " + response.headers());
		}

		return codec.toJavaIceServers(response.body());
	}

	private void handleMessage(String message) {
		AppRTCMessage sigMessage = codec.toJavaMessage(message);

		switch (sigMessage.getType()) {
			case CANDIDATE:
				RTCIceCandidate candidate = (RTCIceCandidate) sigMessage.getObject();

				listener.onRemoteIceCandidate(new Contact(), candidate);
				break;

			case REMOVE_CANDIDATES:
				RTCIceCandidate[] candidates = (RTCIceCandidate[]) sigMessage.getObject();

				listener.onRemoteIceCandidatesRemoved(new Contact(), candidates);
				break;

			case ANSWER:
				RTCSessionDescription answer = (RTCSessionDescription) sigMessage.getObject();

				if (signalingParameters.initiator) {
					listener.onRemoteSessionDescription(new Contact(), answer);
				}
				else {
					reportError("Received answer for call initiator: " + message);
				}
				break;

			case OFFER:
				RTCSessionDescription offer = (RTCSessionDescription) sigMessage.getObject();

				if (!signalingParameters.initiator) {
					listener.onRemoteSessionDescription(new Contact(), offer);
				}
				else {
					reportError("Received offer for call receiver: " + message);
				}
				break;

			case BYE:
				listener.onRoomLeft();
				break;

			case ERROR:
				reportError((String) sigMessage.getObject());
				break;
		}
	}

	private void sendPostMessage(MessageType messageType, AppRTCMessage message) {
		String roomId = connectionParameters.roomId;
		String roomUrl = connectionParameters.roomUrl;
		String clientId = signalingParameters.clientId;

		String url = String.format(
				messageType == MessageType.MESSAGE ? MESSAGE_URL : LEAVE_URL,
				roomUrl, roomId, clientId);

		String logInfo = url;

		if (nonNull(message)) {
			logInfo += ". Message: " + codec.toJsonMessage(message);
		}

		LOGGER.log(Level.INFO, "C->GAE: " + logInfo);

		HttpRequest.BodyPublisher publisher = isNull(message) ?
				HttpRequest.BodyPublishers.noBody() :
				HttpRequest.BodyPublishers.ofString(codec.toJsonMessage(message));

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.setHeader("REFERER", SERVER_URL)
				.header("Content-Type", "application/json")
				.POST(publisher)
				.build();

		client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenApply(HttpResponse::body)
				.thenAccept(response -> {
					if (messageType == MessageType.MESSAGE) {
						try {
							String result = codec.toJavaPostResponse(response);

							if (!result.equals("SUCCESS")) {
								reportError("GAE POST error: " + result);
							}
						}
						catch (Exception e) {
							reportError("GAE POST JSON error: " + e.toString());
						}
					}
				})
				.exceptionally(throwable -> {
					reportError("GAE POST error", throwable);
					return null;
				});
	}

}
