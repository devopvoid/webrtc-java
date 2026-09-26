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

package dev.onvoid.webrtc.media.player;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Just enough of an RTSP server to play a live stream to the media module: it
 * answers OPTIONS, DESCRIBE, SETUP and PLAY, and then streams 48 kHz mono
 * 16-bit PCM (L16) in 10 ms RTP packets, interleaved on the RTSP connection.
 * <p>
 * It refuses RTP over UDP with 461, as a server behind a firewall often has
 * to, which is what makes the client fall back to TCP, the way most real
 * deployments end up.
 *
 * @author Alex Andres
 */
final class TestRtspServer implements AutoCloseable {

	/** What the server does once it has sent its packets, or instead. */
	enum Behavior {

		/** Closes the connection, as a stream that ends. */
		END,

		/** Keeps the connection open and sends nothing more. */
		STALL,

		/** Accepts connections and never answers anything on them. */
		SILENT
	}

	static final int SAMPLE_RATE = 48000;

	/** 10 ms of samples per packet. */
	private static final int SAMPLES_PER_PACKET = SAMPLE_RATE / 100;

	private static final int PAYLOAD_TYPE = 96;

	private final ServerSocket server;

	private final Behavior behavior;

	private final int packets;

	private final List<Socket> sockets = new CopyOnWriteArrayList<>();

	private final AtomicInteger connections = new AtomicInteger();

	private final CountDownLatch closed = new CountDownLatch(1);


	/**
	 * Starts serving on a free port of the loopback address.
	 *
	 * @param behavior What to do once the packets are sent.
	 * @param packets  How many 10 ms packets to stream after PLAY.
	 */
	TestRtspServer(Behavior behavior, int packets) throws IOException {
		this.behavior = behavior;
		this.packets = packets;
		this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());

		Thread acceptor = new Thread(this::accept, "test-rtsp-accept");
		acceptor.setDaemon(true);
		acceptor.start();
	}

	/**
	 * @return The URL of the stream.
	 */
	String url() {
		return "rtsp://127.0.0.1:" + server.getLocalPort() + "/stream";
	}

	/**
	 * @return The port the server listens on.
	 */
	int port() {
		return server.getLocalPort();
	}

	/**
	 * @return How many connections the server has accepted.
	 */
	int connections() {
		return connections.get();
	}

	@Override
	public void close() throws IOException {
		closed.countDown();
		server.close();

		for (Socket socket : sockets) {
			socket.close();
		}
	}

	private void accept() {
		try {
			while (true) {
				Socket socket = server.accept();

				connections.incrementAndGet();
				sockets.add(socket);

				Thread handler = new Thread(() -> handle(socket), "test-rtsp-connection");
				handler.setDaemon(true);
				handler.start();
			}
		}
		catch (IOException e) {
			// Closed.
		}
	}

	private void handle(Socket socket) {
		try {
			if (behavior == Behavior.SILENT) {
				awaitClose();
				return;
			}

			InputStream in = socket.getInputStream();
			OutputStream out = socket.getOutputStream();

			while (true) {
				String requestLine = readLine(in);

				if (requestLine == null) {
					return;
				}
				if (requestLine.isEmpty()) {
					continue;
				}

				String method = requestLine.split(" ")[0];
				String cseq = "0";
				String transport = "";
				int contentLength = 0;

				for (String header = readLine(in); header != null && !header.isEmpty();
						header = readLine(in)) {
					String name = header.substring(0, Math.max(0, header.indexOf(':')))
							.trim().toLowerCase(Locale.ROOT);
					String value = header.substring(header.indexOf(':') + 1).trim();

					if (name.equals("cseq")) {
						cseq = value;
					}
					else if (name.equals("transport")) {
						transport = value;
					}
					else if (name.equals("content-length")) {
						contentLength = Integer.parseInt(value);
					}
				}

				skip(in, contentLength);

				switch (method) {
					case "OPTIONS":
						respond(out, 200, "OK", cseq,
								"Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n", "");
						break;

					case "DESCRIBE":
						String sdp = "v=0\r\n"
								+ "o=- 0 0 IN IP4 127.0.0.1\r\n"
								+ "s=Test\r\n"
								+ "c=IN IP4 127.0.0.1\r\n"
								+ "t=0 0\r\n"
								+ "m=audio 0 RTP/AVP " + PAYLOAD_TYPE + "\r\n"
								+ "a=rtpmap:" + PAYLOAD_TYPE + " L16/" + SAMPLE_RATE + "/1\r\n"
								+ "a=control:track1\r\n";

						respond(out, 200, "OK", cseq,
								"Content-Base: " + url() + "/\r\n"
										+ "Content-Type: application/sdp\r\n", sdp);
						break;

					case "SETUP":
						if (!transport.contains("TCP")) {
							respond(out, 461, "Unsupported Transport", cseq, "", "");
						}
						else {
							respond(out, 200, "OK", cseq,
									"Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n"
											+ "Session: 12345678\r\n", "");
						}
						break;

					case "PLAY":
						respond(out, 200, "OK", cseq, "Session: 12345678\r\n", "");

						stream(out);

						if (behavior == Behavior.END) {
							socket.close();
						}
						else {
							awaitClose();
						}
						return;

					default:
						// TEARDOWN, keep-alives and whatever else.
						respond(out, 200, "OK", cseq, "Session: 12345678\r\n", "");
						break;
				}
			}
		}
		catch (IOException e) {
			// The client went away, or the server was closed.
		}
	}

	/**
	 * Sends the packets in real time, as a live source would: a sawtooth that
	 * is never zero, so that what arrives can be told apart from silence.
	 */
	private void stream(OutputStream out) throws IOException {
		long start = System.nanoTime();
		int sample = 0;

		for (int i = 0; i < packets; i++) {
			ByteArrayOutputStream packet = new ByteArrayOutputStream();

			// RTP version 2, no padding, extension or CSRCs; no marker.
			packet.write(0x80);
			packet.write(PAYLOAD_TYPE);
			writeInt(packet, i, 2);
			writeInt(packet, (long) i * SAMPLES_PER_PACKET, 4);
			writeInt(packet, 0x12345678L, 4);

			for (int n = 0; n < SAMPLES_PER_PACKET; n++, sample++) {
				// L16 is big-endian.
				writeInt(packet, (1000 + (sample * 37) % 16000) & 0xFFFF, 2);
			}

			byte[] rtp = packet.toByteArray();

			// Interleaved on channel 0.
			out.write('$');
			out.write(0);
			out.write((rtp.length >> 8) & 0xFF);
			out.write(rtp.length & 0xFF);
			out.write(rtp);
			out.flush();

			long due = start + TimeUnit.MILLISECONDS.toNanos(10L * (i + 1));
			long wait = due - System.nanoTime();

			if (wait > 0) {
				try {
					TimeUnit.NANOSECONDS.sleep(wait);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
	}

	private void awaitClose() {
		try {
			closed.await();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static void respond(OutputStream out, int status, String reason, String cseq,
			String headers, String body) throws IOException {
		byte[] content = body.getBytes(StandardCharsets.US_ASCII);
		String response = "RTSP/1.0 " + status + " " + reason + "\r\n"
				+ "CSeq: " + cseq + "\r\n"
				+ headers
				+ (content.length > 0 ? "Content-Length: " + content.length + "\r\n" : "")
				+ "\r\n";

		out.write(response.getBytes(StandardCharsets.US_ASCII));
		out.write(content);
		out.flush();
	}

	private static String readLine(InputStream in) throws IOException {
		StringBuilder line = new StringBuilder();

		for (int c = in.read(); c != -1; c = in.read()) {
			if (c == '\n') {
				int end = line.length();

				if (end > 0 && line.charAt(end - 1) == '\r') {
					line.setLength(end - 1);
				}
				return line.toString();
			}

			line.append((char) c);
		}

		return line.length() > 0 ? line.toString() : null;
	}

	private static void skip(InputStream in, int count) throws IOException {
		for (int i = 0; i < count; i++) {
			if (in.read() == -1) {
				return;
			}
		}
	}

	private static void writeInt(ByteArrayOutputStream out, long value, int bytes) {
		for (int shift = (bytes - 1) * 8; shift >= 0; shift -= 8) {
			out.write((int) (value >>> shift) & 0xFF);
		}
	}

}
