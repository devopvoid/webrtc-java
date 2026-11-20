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

package dev.onvoid.webrtc.livekit;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.InvalidProtocolBufferException;

import dev.onvoid.webrtc.livekit.internal.proto.LivekitRtc.SignalRequest;
import dev.onvoid.webrtc.livekit.internal.proto.LivekitRtc.SignalResponse;

/**
 * The signaling connection to a LiveKit server: protobuf messages in binary
 * WebSocket frames. Incoming messages and the end of the connection are handed
 * to the room's executor, in order.
 */
final class SignalClient implements WebSocket.Listener {

	interface Handler {

		/** A message of the server, on the room's executor. */
		void onSignal(SignalResponse response);

		/** The connection ended without the room closing it, on the room's executor. */
		void onSignalClosed(String reason);

	}


	private static final System.Logger LOG = System.getLogger(SignalClient.class.getName());

	private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(2);

	private final Executor executor;

	private final Handler handler;

	/** Frames of the message being received. Only on WebSocket's listener thread. */
	private final ByteArrayOutputStream frames = new ByteArrayOutputStream();

	private volatile WebSocket socket;

	/** Sends one at a time, as WebSocket requires. Only on the room's executor. */
	private CompletableFuture<?> sending = CompletableFuture.completedFuture(null);

	private volatile boolean closed;


	SignalClient(Executor executor, Handler handler) {
		this.executor = executor;
		this.handler = handler;
	}

	/**
	 * The URL of the signaling endpoint for a server URL as LiveKit hands it
	 * out, such as wss://example.com, with https and http taken as wss and ws.
	 */
	static URI rtcUri(URI url, int protocol, boolean autoSubscribe) {
		String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
		switch (scheme) {
			case "https": scheme = "wss"; break;
			case "http": scheme = "ws"; break;
			case "wss":
			case "ws": break;
			default:
				throw new IllegalArgumentException("Not a LiveKit URL: " + url);
		}

		String path = url.getRawPath() == null ? "" : url.getRawPath();
		if (path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}

		String query = "protocol=" + protocol
				+ "&sdk=" + encode("java")
				+ "&version=" + encode(LiveKit.VERSION)
				+ "&os=" + encode(System.getProperty("os.name", ""))
				+ "&auto_subscribe=" + (autoSubscribe ? 1 : 0);

		String authority = url.getRawAuthority();
		return URI.create(scheme + "://" + authority + path + "/rtc?" + query);
	}

	/**
	 * Opens the connection. The token goes in the Authorization header, not in
	 * the URL, which proxies and servers may log.
	 */
	CompletableFuture<Void> open(HttpClient client, URI uri, String token, Duration timeout) {
		return client.newWebSocketBuilder()
				.header("Authorization", "Bearer " + token)
				.connectTimeout(timeout)
				.buildAsync(uri, this)
				.handle((webSocket, error) -> {
					if (error != null) {
						throw new LiveKitException("Could not open the signaling connection to "
								+ uri.getHost() + describe(error), error);
					}
					if (closed) {
						webSocket.abort();
						throw new LiveKitException("The room was closed");
					}
					socket = webSocket;
					return null;
				});
	}

	/**
	 * Sends a request after those sent before. Only on the room's executor.
	 */
	void send(SignalRequest request) {
		WebSocket webSocket = socket;

		if (closed || webSocket == null) {
			return;
		}

		ByteBuffer data = ByteBuffer.wrap(request.toByteArray());

		sending = sending.handle((r, e) -> null)
				.thenCompose(v -> webSocket.sendBinary(data, true))
				.whenComplete((r, e) -> {
					if (e != null && !closed) {
						LOG.log(System.Logger.Level.WARNING, "Sending " + request.getMessageCase() + " failed", e);
					}
				});
	}

	/**
	 * Closes the connection once what was sent before is out, and cuts it off
	 * if the server does not answer the close. Only on the room's executor.
	 */
	void close() {
		if (closed) {
			return;
		}
		closed = true;

		WebSocket webSocket = socket;
		if (webSocket == null) {
			return;
		}

		sending.handle((r, e) -> null)
				.thenCompose(v -> webSocket.sendClose(WebSocket.NORMAL_CLOSURE, ""))
				.whenComplete((r, e) -> CompletableFuture.delayedExecutor(CLOSE_TIMEOUT.toMillis(),
						TimeUnit.MILLISECONDS).execute(webSocket::abort));
	}

	@Override
	public void onOpen(WebSocket webSocket) {
		webSocket.request(1);
	}

	@Override
	public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
		byte[] chunk = new byte[data.remaining()];
		data.get(chunk);
		frames.write(chunk, 0, chunk.length);

		if (last) {
			byte[] message = frames.toByteArray();
			frames.reset();

			try {
				SignalResponse response = SignalResponse.parseFrom(message);
				post(() -> handler.onSignal(response));
			}
			catch (InvalidProtocolBufferException e) {
				LOG.log(System.Logger.Level.WARNING, "Dropped a signal message that does not parse", e);
			}
		}

		webSocket.request(1);
		return null;
	}

	@Override
	public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
		webSocket.request(1);
		return null;
	}

	@Override
	public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
		if (!closed) {
			post(() -> handler.onSignalClosed("The server closed the signaling connection (" + statusCode
					+ (reason.isEmpty() ? "" : ", " + reason) + ")"));
		}
		return null;
	}

	@Override
	public void onError(WebSocket webSocket, Throwable error) {
		if (!closed) {
			post(() -> handler.onSignalClosed("The signaling connection failed: " + error));
		}
	}

	private void post(Runnable task) {
		try {
			executor.execute(task);
		}
		catch (RuntimeException e) {
			// The room is gone.
		}
	}

	private static String describe(Throwable error) {
		Throwable cause = error;
		while (cause.getCause() != null && !(cause instanceof WebSocketHandshakeException)) {
			cause = cause.getCause();
		}
		if (cause instanceof WebSocketHandshakeException) {
			var response = ((WebSocketHandshakeException) cause).getResponse();
			Object body = response.body();
			if (body instanceof byte[]) {
				body = new String((byte[]) body, StandardCharsets.UTF_8);
			}
			String text = body == null ? "" : body.toString().trim();
			return ": HTTP " + response.statusCode() + (text.isEmpty() ? "" : " " + text);
		}
		return ": " + cause;
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

}
