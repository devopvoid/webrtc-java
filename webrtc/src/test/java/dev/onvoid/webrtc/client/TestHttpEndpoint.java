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

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;

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

	/** Holds the answers to DELETEs while set, until it is released. */
	private volatile CountDownLatch deleteGate;

	/** How POSTs are answered. */
	private volatile Answer answerMode = Answer.WHOLE;

	/** Whether the head of the answer to a DELETE trickles in. */
	private volatile boolean trickleDeletes;

	/** How many POSTs are still refused with {@link #refusalStatus}. */
	private int refusals;

	private volatile int refusalStatus;

	/** The Retry-After of a refusal, or null for none. */
	private volatile String refusalRetryAfter;

	/** Where POSTs are redirected to, or null. */
	private volatile String redirectLocation;

	private volatile int redirectStatus;

	/** Counted down once a client hung up on an answer it did not get whole. */
	private final CountDownLatch hungUp = new CountDownLatch(1);

	private int answered;

	private final ServerSocket server;

	private final Function<String, String> answerer;

	private final int status;


	/**
	 * @param answerer turns an offer into an answer.
	 * @param status   the status of the answer, 201 for success.
	 */
	TestHttpEndpoint(Function<String, String> answerer, int status) throws IOException {
		this(answerer, status, null);
	}

	/**
	 * @param answerer turns an offer into an answer.
	 * @param status   the status of the answer, 201 for success.
	 * @param tls      the TLS the endpoint serves https with, or null for
	 *                 plain http.
	 */
	TestHttpEndpoint(Function<String, String> answerer, int status, SSLContext tls) throws IOException {
		this.answerer = answerer;
		this.status = status;
		this.server = tls == null
				? new ServerSocket(0, 10, InetAddress.getLoopbackAddress())
				: tls.getServerSocketFactory().createServerSocket(0, 10, InetAddress.getLoopbackAddress());

		Thread thread = new Thread(this::serve, "test-http-endpoint");
		thread.setDaemon(true);
		thread.start();
	}

	URI uri() {
		String scheme = server instanceof SSLServerSocket ? "https" : "http";

		return URI.create(scheme + "://127.0.0.1:" + server.getLocalPort() + "/live/endpoint");
	}

	/**
	 * Refuses the next POSTs, then answers as before.
	 *
	 * @param times      how many POSTs to refuse.
	 * @param status     the status, such as 503.
	 * @param retryAfter the Retry-After to send along, or null for none.
	 */
	synchronized void refuseAnswers(int times, int status, String retryAfter) {
		refusals = times;
		refusalStatus = status;
		refusalRetryAfter = retryAfter;
	}

	/**
	 * Answers every POST from now on with a redirect.
	 *
	 * @param status   the status, such as 307.
	 * @param location where the redirect points.
	 */
	void redirectAnswers(int status, String location) {
		redirectStatus = status;
		redirectLocation = location;
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
	 * Holds the answer to every DELETE from now on, after the request has been
	 * recorded, until the returned latch is counted down.
	 */
	CountDownLatch holdDeletes() {
		CountDownLatch gate = new CountDownLatch(1);
		deleteGate = gate;
		return gate;
	}

	/**
	 * Answers no POST from now on: the endpoint waits until the client hangs
	 * up, which {@link #awaitHangUp(long)} tells.
	 */
	void stallAnswers() {
		answerMode = Answer.NONE;
	}

	/**
	 * Answers every POST from now on with its head and the first half of its
	 * body, then waits until the client hangs up.
	 */
	void stallBodies() {
		answerMode = Answer.HALF;
	}

	/**
	 * Answers every POST from now on with its head, then sends a body of a
	 * megabyte a byte at a time, one every 20 ms, until the client hangs up.
	 */
	void trickleAnswers() {
		answerMode = Answer.TRICKLE;
	}

	/**
	 * Answers every POST from now on with the body in two chunks.
	 */
	void chunkAnswers() {
		answerMode = Answer.CHUNKED;
	}

	/**
	 * Answers every POST from now on with a body of no stated length, which
	 * ends where the endpoint closes the connection.
	 */
	void answerUntilClose() {
		answerMode = Answer.UNTIL_CLOSE;
	}

	/**
	 * Answers every DELETE from now on with a status line, then a header a
	 * byte at a time, one every 20 ms, that never ends, until the client
	 * hangs up.
	 */
	void trickleDeletes() {
		trickleDeletes = true;
	}

	/**
	 * Waits until a client hung up on an answer that was stalled or trickled.
	 */
	void awaitHangUp(long timeoutSeconds) throws InterruptedException {
		if (!hungUp.await(timeoutSeconds, TimeUnit.SECONDS)) {
			throw new AssertionError("The client did not hang up within " + timeoutSeconds + " s");
		}
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
			boolean refused;

			synchronized (this) {
				refused = refusals > 0;

				if (refused) {
					refusals--;
				}
			}

			if (refused) {
				String retryAfter = refusalRetryAfter;
				respond(out, refusalStatus + " Refused", retryAfter != null ? "Retry-After: " + retryAfter + "\r\n" : "",
						"come back later");
				return;
			}
			if (redirectLocation != null) {
				respond(out, redirectStatus + " Redirect", "Location: " + redirectLocation + "\r\n", "");
				return;
			}
			if (status != 201) {
				respond(out, status + " Refused", "", "not allowed");
				return;
			}

			if (answerMode != Answer.WHOLE) {
				answerInPart(socket, answerer.apply(new String(body, StandardCharsets.UTF_8)));
				return;
			}

			String answer = answerer.apply(new String(body, StandardCharsets.UTF_8));
			pass(answerGate);

			synchronized (this) {
				answered++;
			}

			respond(out, "201 Created", "Location: " + location + "\r\n", answer);
			return;
		}

		if (trickleDeletes) {
			out.write("HTTP/1.1 200 OK\r\nX-Padding: ".getBytes(StandardCharsets.US_ASCII));
			trickle(out);
			return;
		}

		pass(deleteGate);

		if (deleteStatus / 100 == 3) {
			respond(out, deleteStatus + " Moved", "Location: " + redirect + "\r\n", "");
		}
		else {
			respond(out, deleteStatus + " Status", "", "");
		}
	}

	private void answerInPart(Socket socket, String answer) throws IOException {
		OutputStream out = socket.getOutputStream();
		byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
		Answer mode = answerMode;

		if (mode == Answer.CHUNKED || mode == Answer.UNTIL_CLOSE) {
			String body = new String(bytes, StandardCharsets.UTF_8);
			String head = "HTTP/1.1 201 Created\r\nLocation: " + location + "\r\nConnection: close\r\n";

			if (mode == Answer.CHUNKED) {
				int half = body.length() / 2;
				head += "Transfer-Encoding: chunked\r\n";
				body = Integer.toHexString(half) + ";part=1\r\n" + body.substring(0, half) + "\r\n"
						+ Integer.toHexString(body.length() - half) + "\r\n" + body.substring(half) + "\r\n"
						+ "0\r\n\r\n";
			}

			out.write((head + "\r\n" + body).getBytes(StandardCharsets.UTF_8));
			out.flush();
			return;
		}

		if (mode != Answer.NONE) {
			int length = mode == Answer.TRICKLE ? 1 << 20 : bytes.length;
			String head = "HTTP/1.1 201 Created\r\nContent-Type: application/sdp\r\nLocation: " + location
					+ "\r\nContent-Length: " + length + "\r\nConnection: close\r\n\r\n";

			out.write(head.getBytes(StandardCharsets.US_ASCII));
			out.write(bytes, 0, mode == Answer.HALF ? bytes.length / 2 : 0);
			out.flush();
		}

		if (mode == Answer.TRICKLE) {
			trickle(out);
			return;
		}

		try {
			// Bounded, so that a client that never hangs up frees the endpoint.
			socket.setSoTimeout(60_000);

			while (socket.getInputStream().read() != -1) {
				// The client sends nothing more.
			}
		}
		catch (IOException e) {
			// The client hung up.
		}

		hungUp.countDown();
	}

	/**
	 * Writes a byte every 20 ms until the client hangs up, for at most a
	 * megabyte.
	 */
	private void trickle(OutputStream out) {
		try {
			// A write fails once the client has closed the connection.
			for (int i = 0; i < 1 << 20; i++) {
				out.write('a');
				out.flush();
				Thread.sleep(20);
			}
		}
		catch (IOException e) {
			// The client hung up.
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		}

		hungUp.countDown();
	}

	private static void pass(CountDownLatch gate) throws IOException {
		if (gate != null) {
			try {
				gate.await();
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException(e);
			}
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



	private enum Answer {

		/** The whole answer. */
		WHOLE,

		/** Nothing at all. */
		NONE,

		/** The head and half the body. */
		HALF,

		/** The head and a body that trickles in. */
		TRICKLE,

		/** The answer in chunks. */
		CHUNKED,

		/** The answer, ended by closing the connection. */
		UNTIL_CLOSE
	}
}
