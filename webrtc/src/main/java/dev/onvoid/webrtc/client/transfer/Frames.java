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

package dev.onvoid.webrtc.client.transfer;

import java.net.ProtocolException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * The frames of the transfer protocol, one binary data channel message each.
 * All numbers are big-endian. Every frame starts with its type and the 16
 * bytes of the transfer id:
 * <pre>
 * OFFER  1  id  version:u8  size:u64  name:text
 * ACCEPT 2  id  offset:u64  window:u32
 * REJECT 3  id  reason:text
 * DATA   4  id  offset:u64  payload (the rest of the message)
 * ACK    5  id  offset:u64
 * END    6  id  sha256:32 bytes
 * DONE   7  id
 * CANCEL 8  id  failed:u8  reason:text
 * </pre>
 * A text is a u16 length followed by that many bytes of UTF-8, at most
 * {@link #MAX_TEXT}.
 */
final class Frames {

	static final int VERSION = 1;

	static final byte OFFER = 1;
	static final byte ACCEPT = 2;
	static final byte REJECT = 3;
	static final byte DATA = 4;
	static final byte ACK = 5;
	static final byte END = 6;
	static final byte DONE = 7;
	static final byte CANCEL = 8;

	static final int ID_LENGTH = 16;

	static final int DIGEST_LENGTH = 32;

	/** Bytes of a DATA frame before its payload. */
	static final int DATA_HEADER = 1 + ID_LENGTH + 8;

	/** The most bytes of UTF-8 a name or a reason may take. */
	static final int MAX_TEXT = 1024;


	private Frames() {
	}

	static byte[] offer(String id, long size, String name) {
		byte[] text = utf8(name);
		ByteBuffer frame = start(OFFER, id, 1 + 8 + 2 + text.length);
		frame.put((byte) VERSION).putLong(size).putShort((short) text.length).put(text);
		return frame.array();
	}

	static byte[] accept(String id, long offset, int window) {
		return start(ACCEPT, id, 8 + 4).putLong(offset).putInt(window).array();
	}

	static byte[] reject(String id, String reason) {
		byte[] text = truncated(reason);
		return start(REJECT, id, 2 + text.length).putShort((short) text.length).put(text).array();
	}

	/**
	 * @return a DATA frame whose payload, {@code length} bytes from {@link
	 * #DATA_HEADER} on, the caller fills.
	 */
	static byte[] data(String id, long offset, int length) {
		return start(DATA, id, 8 + length).putLong(offset).array();
	}

	static byte[] ack(String id, long offset) {
		return start(ACK, id, 8).putLong(offset).array();
	}

	static byte[] end(String id, byte[] digest) {
		return start(END, id, DIGEST_LENGTH).put(digest).array();
	}

	static byte[] done(String id) {
		return start(DONE, id, 0).array();
	}

	static byte[] cancel(String id, boolean failed, String reason) {
		byte[] text = truncated(reason);
		ByteBuffer frame = start(CANCEL, id, 1 + 2 + text.length);
		frame.put((byte) (failed ? 1 : 0)).putShort((short) text.length).put(text);
		return frame.array();
	}

	/**
	 * @param message a whole binary message.
	 *
	 * @return the frame it holds.
	 *
	 * @throws ProtocolException if it holds none.
	 */
	static Frame decode(byte[] message) throws ProtocolException {
		ByteBuffer in = ByteBuffer.wrap(message);
		Frame frame = new Frame();

		try {
			frame.type = in.get();

			byte[] id = new byte[ID_LENGTH];
			in.get(id);
			frame.id = hex(id);

			switch (frame.type) {
				case OFFER:
					frame.version = in.get() & 0xff;
					// A later version may lay out the rest differently.
					if (frame.version == VERSION) {
						frame.value = in.getLong();
						frame.text = text(in);
					}
					break;
				case ACCEPT:
					frame.value = in.getLong();
					frame.window = in.getInt();
					break;
				case REJECT:
					frame.text = text(in);
					break;
				case DATA:
					frame.value = in.getLong();
					frame.payload = message;
					frame.payloadOffset = DATA_HEADER;
					frame.payloadLength = message.length - DATA_HEADER;
					in.position(message.length);
					break;
				case ACK:
					frame.value = in.getLong();
					break;
				case END:
					frame.digest = new byte[DIGEST_LENGTH];
					in.get(frame.digest);
					break;
				case DONE:
					break;
				case CANCEL:
					frame.failed = in.get() != 0;
					frame.text = text(in);
					break;
				default:
					throw new ProtocolException("Unknown frame type " + frame.type);
			}
		}
		catch (BufferUnderflowException e) {
			throw new ProtocolException("A frame of type " + frame.type + " ends early");
		}

		if (in.hasRemaining() && !(frame.type == OFFER && frame.version != VERSION)) {
			throw new ProtocolException("A frame of type " + frame.type + " has " + in.remaining()
					+ " bytes too many");
		}

		return frame;
	}

	/**
	 * @return the UTF-8 of the text.
	 *
	 * @throws IllegalArgumentException if it takes more than {@link #MAX_TEXT}
	 *                                  bytes.
	 */
	static byte[] utf8(String text) {
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

		if (bytes.length > MAX_TEXT) {
			throw new IllegalArgumentException("The name takes " + bytes.length + " bytes of UTF-8, more than "
					+ MAX_TEXT);
		}

		return bytes;
	}

	/**
	 * Escapes what the remote peer wrote before it goes into an exception
	 * message, so that it cannot forge log lines.
	 */
	static String printable(String text) {
		StringBuilder printable = new StringBuilder(text.length());

		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);

			if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT
					|| c == '\u2028' || c == '\u2029') {
				printable.append(String.format("\\u%04x", (int) c));
			}
			else {
				printable.append(c);
			}
		}

		return printable.toString();
	}

	static String hex(byte[] bytes) {
		StringBuilder hex = new StringBuilder(bytes.length * 2);

		for (byte b : bytes) {
			hex.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
		}

		return hex.toString();
	}

	private static ByteBuffer start(byte type, String id, int length) {
		ByteBuffer frame = ByteBuffer.allocate(1 + ID_LENGTH + length);
		frame.put(type);

		for (int i = 0; i < ID_LENGTH; i++) {
			frame.put((byte) Integer.parseInt(id.substring(2 * i, 2 * i + 2), 16));
		}

		return frame;
	}

	/** At most {@link #MAX_TEXT} bytes of UTF-8, cut at a character. */
	private static byte[] truncated(String text) {
		CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
				.onMalformedInput(CodingErrorAction.REPLACE)
				.onUnmappableCharacter(CodingErrorAction.REPLACE);
		ByteBuffer out = ByteBuffer.allocate(MAX_TEXT);
		encoder.encode(CharBuffer.wrap(text == null ? "" : text), out, true);

		byte[] bytes = new byte[out.position()];
		out.flip();
		out.get(bytes);
		return bytes;
	}

	private static String text(ByteBuffer in) throws ProtocolException {
		int length = in.getShort() & 0xffff;

		if (length > MAX_TEXT) {
			throw new ProtocolException("A text of " + length + " bytes, more than " + MAX_TEXT);
		}

		byte[] bytes = new byte[length];
		in.get(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}



	/** A decoded frame. Which fields are set depends on the type. */
	static final class Frame {

		byte type;

		String id;

		int version;

		/** The size of an OFFER, or the offset of ACCEPT, DATA and ACK. */
		long value;

		int window;

		String text;

		byte[] digest;

		boolean failed;

		/** The message of a DATA frame, whose payload starts at {@link #payloadOffset}. */
		byte[] payload;

		int payloadOffset;

		int payloadLength;

	}
}
