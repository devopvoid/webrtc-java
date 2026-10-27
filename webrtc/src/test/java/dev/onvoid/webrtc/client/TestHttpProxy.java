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

package dev.onvoid.webrtc.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Just enough of an HTTP proxy for tests: it sends plain requests on, and
 * tunnels with CONNECT. It can ask for credentials, and records what it was
 * asked for.
 */
final class TestHttpProxy implements AutoCloseable {

	/** A request the proxy received. */
	static final class Request {

		final String method;

		final String target;

		final String authorization;


		Request(String method, String target, String authorization) {
			this.method = method;
			this.target = target;
			this.authorization = authorization;
		}

		@Override
		public String toString() {
			return method + " " + target;
		}
	}

	final List<Request> requests = new CopyOnWriteArrayList<>();

	/**
	 * The Proxy-Authorization the proxy asks for, or null for none. One that
	 * ends with {@code *} takes any value that starts with what precedes it.
	 */
	volatile String requiredAuthorization;

	/** The challenge the proxy asks for credentials with. */
	volatile String challenge = "Basic realm=\"test\"";

	private final ServerSocket server;


	TestHttpProxy() throws IOException {
		server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());

		Thread thread = new Thread(this::serve, "test-http-proxy");
		thread.setDaemon(true);
		thread.start();
	}

	Proxy proxy() {
		return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort()));
	}

	@Override
	public void close() throws IOException {
		server.close();
	}

	private void serve() {
		while (!server.isClosed()) {
			try {
				Socket socket = server.accept();
				Thread thread = new Thread(() -> handle(socket), "test-http-proxy-connection");
				thread.setDaemon(true);
				thread.start();
			}
			catch (IOException e) {
				// Closed.
			}
		}
	}

	private void handle(Socket socket) {
		try (Socket client = socket) {
			InputStream in = client.getInputStream();
			OutputStream out = client.getOutputStream();
			String[] requestLine;
			StringBuilder forwarded;
			int length;

			// After a 407 the client may ask again on the same connection.
			while (true) {
				String first = readLine(in);

				if (first.isEmpty()) {
					return;
				}

				requestLine = first.split(" ");
				forwarded = new StringBuilder();
				length = 0;

				String authorization = null;
				String line;

				while (!(line = readLine(in)).isEmpty()) {
					String name = line.substring(0, line.indexOf(':')).trim().toLowerCase(Locale.ROOT);
					String value = line.substring(line.indexOf(':') + 1).trim();

					if (name.equals("proxy-authorization")) {
						authorization = value;
						continue;
					}
					if (name.equals("content-length")) {
						length = Integer.parseInt(value);
					}

					forwarded.append(line).append("\r\n");
				}

				requests.add(new Request(requestLine[0], requestLine[1], authorization));

				if (authorized(authorization)) {
					break;
				}

				for (int i = 0; i < length; i++) {
					in.read();
				}

				out.write(("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: " + challenge
						+ "\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
				out.flush();
			}

			if (requestLine[0].equals("CONNECT")) {
				int colon = requestLine[1].lastIndexOf(':');

				try (Socket upstream = new Socket(requestLine[1].substring(0, colon),
						Integer.parseInt(requestLine[1].substring(colon + 1)))) {
					out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
					out.flush();

					Thread outward = new Thread(() -> pipe(in, upstream), "test-http-proxy-tunnel");
					outward.setDaemon(true);
					outward.start();

					pipe(upstream.getInputStream(), client);
				}
				return;
			}

			URI target = URI.create(requestLine[1]);
			String path = target.getRawPath() + (target.getRawQuery() != null ? "?" + target.getRawQuery() : "");

			try (Socket upstream = new Socket(target.getHost(), target.getPort())) {
				OutputStream up = upstream.getOutputStream();
				up.write((requestLine[0] + " " + path + " HTTP/1.1\r\n" + forwarded + "\r\n")
						.getBytes(StandardCharsets.ISO_8859_1));

				for (int i = 0; i < length; i++) {
					up.write(in.read());
				}

				up.flush();
				pipe(upstream.getInputStream(), client);
			}
		}
		catch (IOException e) {
			// The client or the server went away.
		}
	}

	private boolean authorized(String authorization) {
		String required = requiredAuthorization;

		if (required == null) {
			return true;
		}
		if (authorization == null) {
			return false;
		}
		if (required.endsWith("*")) {
			return authorization.startsWith(required.substring(0, required.length() - 1));
		}

		return required.equals(authorization);
	}

	/**
	 * Copies until the source ends, then ends the output of the destination.
	 */
	private static void pipe(InputStream source, Socket destination) {
		try {
			OutputStream out = destination.getOutputStream();
			byte[] buffer = new byte[8192];
			int count;

			while ((count = source.read(buffer)) != -1) {
				out.write(buffer, 0, count);
				out.flush();
			}

			destination.shutdownOutput();
		}
		catch (IOException e) {
			// Closed.
		}
	}

	private static String readLine(InputStream in) throws IOException {
		ByteArrayOutputStream line = new ByteArrayOutputStream();
		int c;

		while ((c = in.read()) != -1 && c != '\n') {
			if (c != '\r') {
				line.write(c);
			}
		}

		return new String(line.toByteArray(), StandardCharsets.US_ASCII);
	}
}
