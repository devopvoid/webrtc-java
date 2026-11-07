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

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * A message received on a {@link DataChannel}. It holds a copy of the data and
 * may be kept as long as needed.
 */
public final class Message {

	private final byte[] data;

	private final boolean binary;


	Message(byte[] data, boolean binary) {
		this.data = data;
		this.binary = binary;
	}

	/**
	 * @return true when the message was sent as text.
	 */
	public boolean isText() {
		return !binary;
	}

	/**
	 * @return the message decoded as UTF-8.
	 */
	public String text() {
		return new String(data, StandardCharsets.UTF_8);
	}

	/**
	 * @return a copy of the message's bytes.
	 */
	public byte[] bytes() {
		return data.clone();
	}

	/**
	 * @return the number of bytes.
	 */
	public int size() {
		return data.length;
	}

	/**
	 * Returns message type and byte length, without exposing payload data.
	 * Use {@link #text()}, {@link #bytes()} or {@link #preview(int)} explicitly
	 * when content is needed.
	 */
	@Override
	public String toString() {
		return "Message[type=" + (binary ? "binary" : "text") + ", length=" + data.length + " bytes]";
	}

	/**
	 * Returns metadata with an escaped, bounded payload preview. Text uses
	 * UTF-8; binary data uses hexadecimal. An incomplete trailing UTF-8
	 * character is omitted. Control characters are escaped for single-line logs.
	 *
	 * @param maxBytes maximum input bytes to inspect; must be nonnegative.
	 * @return the diagnostic representation.
	 */
	public String preview(int maxBytes) {
		return preview(maxBytes, UnaryOperator.identity());
	}

	/**
	 * Returns a bounded preview with application-defined masking, applied before
	 * escaping. The mask receives only the bounded prefix, so masking rules must
	 * also handle secrets cut off at its end. Its result is limited to twice
	 * {@code maxBytes} UTF-16 units (enough for hexadecimal), then escaped.
	 * At most six output characters are used per UTF-16 unit.
	 *
	 * @param maxBytes maximum input bytes to inspect; must be nonnegative.
	 * @param mask transforms the prefix to remove sensitive values; must return
	 *             a non-null string.
	 * @return metadata and the masked preview, with a truncation indicator.
	 * @throws IllegalArgumentException if maxBytes is negative.
	 * @throws NullPointerException if mask or its result is null.
	 */
	public String preview(int maxBytes, UnaryOperator<String> mask) {
		if (maxBytes < 0) {
			throw new IllegalArgumentException("Negative preview limit");
		}
		Objects.requireNonNull(mask, "mask");
		int count = Math.min(data.length, maxBytes);
		String prefix;
		boolean truncated = count < data.length;
		if (binary) {
			StringBuilder hex = new StringBuilder();
			for (int i = 0; i < count; i++) {
				int value = data[i] & 0xff;
				hex.append(Character.forDigit(value >>> 4, 16));
				hex.append(Character.forDigit(value & 15, 16));
			}
			prefix = hex.toString();
		}
		else {
			ByteBuffer input = ByteBuffer.wrap(data, 0, count);
			CharBuffer output = CharBuffer.allocate(count);
			StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPLACE)
					.onUnmappableCharacter(CodingErrorAction.REPLACE)
					.decode(input, output, count == data.length);
			truncated |= input.hasRemaining();
			output.flip();
			prefix = output.toString();
		}
		String masked = Objects.requireNonNull(mask.apply(prefix), "mask result");
		int end = (int) Math.min(masked.length(), 2L * maxBytes);
		if (end > 0 && end < masked.length() && Character.isHighSurrogate(masked.charAt(end - 1))) {
			end--;
		}
		truncated |= end < masked.length();
		StringBuilder escaped = new StringBuilder();
		for (int i = 0; i < end; i++) {
			char ch = masked.charAt(i);
			if (ch == '\\' || ch == '"') {
				escaped.append('\\').append(ch);
			}
			else if (Character.isISOControl(ch) || Character.getType(ch) == Character.FORMAT
					|| ch == '\u2028' || ch == '\u2029') {
				escaped.append("\\u");
				for (int shift = 12; shift >= 0; shift -= 4) {
					escaped.append(Character.forDigit((ch >>> shift) & 15, 16));
				}
			}
			else {
				escaped.append(ch);
			}
		}
		return toString() + " preview=\"" + escaped + "\"" + (truncated ? " (truncated)" : "");
	}
}
