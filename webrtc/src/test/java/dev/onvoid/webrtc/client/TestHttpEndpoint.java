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
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Just enough HTTP for WHIP and WHEP tests. A POST is answered with what the
 * handler returns for the offer, and creates a resource; a DELETE of that
 * resource returns 200, unless the test sets another status. Requests are
 * served one at a time, in the order they arrive.
 */
final class TestHttpEndpoint implements AutoCloseable {

	/** A request the endpoint received. */
	static final class Request {

		final String method;

		final String path;

		final String authorization;


		Request(String method, String path, String authorization) {
			this.method = method;
			this.path = path;
			this.authorization = authorization;
		}

		@Override
		public String toString() {
			return method + " " + path;
		}
	}

	final List<Request> requests = new CopyOnWriteArrayList<>();

	/** The Location a POST is answered with. */
	volatile String location = "/live/session/1";

	/** The status a DELETE is answered with. */
	volatile int deleteStatus = 200;

	/** Where a DELETE answered with a redirect points. */
	volatile String redirect = "/live/moved";

	/** Holds the answers to POSTs while set, until it is released. */
	private volatile CountDownLatch answerGate;

	private int answered;

	private final ServerSocket server;

	private final Function<String, String> answerer;

	private final int status;


	/**
	 * @param answerer turns an offer into an answer.
	 * @param status   the status of the answer, 201 for success.
	 */
	TestHttpEndpoint(Function<String, String> answerer, int status) throws IOException {
		this.answerer = answerer;
		this.status = status;
		this.server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());

		Thread thread = new Thread(this::serve, "test-http-endpoint");
		thread.setDaemon(true);
		thread.start();
	}

	URI uri() {
		return URI.create("http://127.0.0.1:" + server.getLocalPort() + "/live/endpoint");
	}

	/**
	 * Holds the answer to every POST from now on, after the request has been
	 * recorded, until the returned latch is counted down.
	 */
	CountDownLatch holdAnswers() {
		CountDownLatch gate = new CountDownLatch(1);
		answerGate = gate;
		return gate;
	}

	/**
	 * @return how many POSTs have been answered, counted before the answer is
	 * written: once a client has read one, it is counted.
	 */
	synchronized int answered() {
		return answered;
	}

	/**
	 * Waits until the endpoint has received the request, such as "DELETE
	 * /live/session/1".
	 */
	synchronized void awaitRequest(String request, long timeoutSeconds) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);

		while (requests.stream().noneMatch(r -> r.toString().equals(request))) {
			long left = deadline - System.nanoTime();

			if (left <= 0) {
				throw new AssertionError("No " + request + " within " + timeoutSeconds + " s. Requests: " + requests);
			}

			TimeUnit.NANOSECONDS.timedWait(this, left);
		}
	}

	/**
	 * @return how many of the requests received are the given one.
	 */
	long count(String request) {
		return requests.stream().filter(r -> r.toString().equals(request)).count();
	}

	@Override
	public void close() throws IOException {
		server.close();
	}

	private void serve() {
		while (!server.isClosed()) {
			try (Socket socket = server.accept()) {
				handle(socket);
			}
			catch (IOException e) {
				// Closed.
			}
		}
	}

	private void handle(Socket socket) throws IOException {
		InputStream in = socket.getInputStream();
		String[] requestLine = readLine(in).split(" ");
		String authorization = null;
		int length = 0;
		String line;

		while (!(line = readLine(in)).isEmpty()) {
			String name = line.substring(0, line.indexOf(':')).trim().toLowerCase(Locale.ROOT);
			String value = line.substring(line.indexOf(':') + 1).trim();

			if (name.equals("content-length")) {
				length = Integer.parseInt(value);
			}
			else if (name.equals("authorization")) {
				authorization = value;
			}
		}

		byte[] body = new byte[length];
		int read = 0;
		while (read < length) {
			read += in.read(body, read, length - read);
		}

		synchronized (this) {
			requests.add(new Request(requestLine[0], requestLine[1], authorization));
			notifyAll();
		}

		OutputStream out = socket.getOutputStream();

		if (requestLine[0].equals("POST")) {
			if (status != 201) {
				respond(out, status + " Refused", "", "not allowed");
				return;
			}

			String answer = answerer.apply(new String(body, StandardCharsets.UTF_8));
			CountDownLatch gate = answerGate;

			if (gate != null) {
				try {
					gate.await();
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException(e);
				}
			}

			synchronized (this) {
				answered++;
			}

			respond(out, "201 Created", "Location: " + location + "\r\n", answer);
		}
		else if (deleteStatus / 100 == 3) {
			respond(out, deleteStatus + " Moved", "Location: " + redirect + "\r\n", "");
		}
		else {
			respond(out, deleteStatus + " Status", "", "");
		}
	}

	private static void respond(OutputStream out, String status, String headers, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		String head = "HTTP/1.1 " + status + "\r\nContent-Type: application/sdp\r\n" + headers
				+ "Content-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n";

		out.write(head.getBytes(StandardCharsets.US_ASCII));
		out.write(bytes);
		out.flush();
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
