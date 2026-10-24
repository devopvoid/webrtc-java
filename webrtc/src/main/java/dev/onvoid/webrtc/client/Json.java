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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON for signaling messages, so that the module needs no JSON
 * library. Objects become {@code Map}s, arrays {@code List}s, numbers {@code
 * Double}s.
 * <p>
 * The grammar is that of RFC 8259 and nothing more lenient: no control
 * characters inside strings, numbers without leading zeros or bare points,
 * only the four whitespace characters JSON has. A member name must not appear
 * twice in an object, which would leave open which of the values counts, and
 * a number must fit a {@code double}, since JSON has no infinities.
 * <p>
 * Text comes from the remote side, so it is bounded: in length, like the
 * answers of WHIP and WHEP endpoints, and in nesting, which a signaling
 * message needs only a few levels of and the recursive parser would otherwise
 * run out of stack on.
 */
final class Json {

	/** The longest text read, in chars. */
	static final int MAX_LENGTH = 1 << 20;

	/** The deepest nesting of arrays and objects read. */
	static final int MAX_DEPTH = 64;

	private final String text;

	private int pos;

	private int depth;


	private Json(String text) {
		this.text = text;
	}

	static Object parse(String text) {
		if (text.length() > MAX_LENGTH) {
			throw new IllegalArgumentException("The JSON is longer than " + MAX_LENGTH + " chars");
		}

		Json json = new Json(text);
		json.skipWhitespace();

		Object value = json.readValue();

		json.skipWhitespace();

		if (json.pos != text.length()) {
			throw json.error("Unexpected text after the JSON value");
		}

		return value;
	}

	static void writeString(StringBuilder out, String value) {
		out.append('"');

		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);

			switch (c) {
				case '"':
					out.append("\\\"");
					break;
				case '\\':
					out.append("\\\\");
					break;
				case '\n':
					out.append("\\n");
					break;
				case '\r':
					out.append("\\r");
					break;
				case '\t':
					out.append("\\t");
					break;
				case '\b':
					out.append("\\b");
					break;
				case '\f':
					out.append("\\f");
					break;
				default:
					if (c < 0x20) {
						out.append(String.format("\\u%04x", (int) c));
					}
					else {
						out.append(c);
					}
			}
		}

		out.append('"');
	}

	private Object readValue() {
		if (pos >= text.length()) {
			throw error("Unexpected end of JSON");
		}

		char c = text.charAt(pos);

		switch (c) {
			case '{':
				enter();
				Map<String, Object> object = readObject();
				depth--;
				return object;
			case '[':
				enter();
				List<Object> array = readArray();
				depth--;
				return array;
			case '"':
				return readString();
			case 't':
				expect("true");
				return Boolean.TRUE;
			case 'f':
				expect("false");
				return Boolean.FALSE;
			case 'n':
				expect("null");
				return null;
			default:
				if (c == '-' || (c >= '0' && c <= '9')) {
					return readNumber();
				}
				throw error("Unexpected character " + describe(c));
		}
	}

	private Map<String, Object> readObject() {
		Map<String, Object> object = new LinkedHashMap<>();
		pos++;
		skipWhitespace();

		if (peek() == '}') {
			pos++;
			return object;
		}

		while (true) {
			skipWhitespace();

			if (peek() != '"') {
				throw error("Expected a member name");
			}

			int start = pos;
			String name = readString();

			if (object.containsKey(name)) {
				throw error("Duplicate member name " + quote(name), start);
			}

			skipWhitespace();

			if (peek() != ':') {
				throw error("Expected ':'");
			}

			pos++;
			skipWhitespace();
			object.put(name, readValue());
			skipWhitespace();

			char c = peek();
			pos++;

			if (c == '}') {
				return object;
			}
			if (c != ',') {
				throw error("Expected ',' or '}'");
			}
		}
	}

	private List<Object> readArray() {
		List<Object> array = new ArrayList<>();
		pos++;
		skipWhitespace();

		if (peek() == ']') {
			pos++;
			return array;
		}

		while (true) {
			skipWhitespace();
			array.add(readValue());
			skipWhitespace();

			char c = peek();
			pos++;

			if (c == ']') {
				return array;
			}
			if (c != ',') {
				throw error("Expected ',' or ']'");
			}
		}
	}

	private String readString() {
		StringBuilder out = new StringBuilder();
		pos++;

		while (true) {
			if (pos >= text.length()) {
				throw error("Unterminated string");
			}

			char c = text.charAt(pos++);

			if (c == '"') {
				return out.toString();
			}
			if (c < 0x20) {
				throw error("Unescaped control character " + describe(c) + " in a string", pos - 1);
			}
			if (c != '\\') {
				out.append(c);
				continue;
			}
			if (pos >= text.length()) {
				throw error("Unterminated escape");
			}

			char e = text.charAt(pos++);

			switch (e) {
				case '"':
				case '\\':
				case '/':
					out.append(e);
					break;
				case 'n':
					out.append('\n');
					break;
				case 'r':
					out.append('\r');
					break;
				case 't':
					out.append('\t');
					break;
				case 'b':
					out.append('\b');
					break;
				case 'f':
					out.append('\f');
					break;
				case 'u':
					if (pos + 4 > text.length()) {
						throw error("Incomplete unicode escape");
					}

					int code = 0;

					for (int i = 0; i < 4; i++) {
						int digit = hexDigit(text.charAt(pos + i));

						if (digit < 0) {
							throw error("Invalid unicode escape", pos + i);
						}

						code = code << 4 | digit;
					}

					out.append((char) code);
					pos += 4;
					break;
				default:
					throw error("Invalid escape '\\" + e + "'");
			}
		}
	}

	private Double readNumber() {
		int start = pos;

		if (next('-')) {
			pos++;
		}
		if (next('0')) {
			pos++;

			if (nextIsDigit()) {
				throw error("Leading zero in a number");
			}
		}
		else {
			readDigits("Expected a digit");
		}
		if (next('.')) {
			pos++;
			readDigits("Expected a digit after the decimal point");
		}
		if (next('e') || next('E')) {
			pos++;

			if (next('+') || next('-')) {
				pos++;
			}

			readDigits("Expected a digit in the exponent");
		}

		// The grammar above is a subset of what Double.valueOf reads.
		Double value = Double.valueOf(text.substring(start, pos));

		if (value.isInfinite()) {
			throw error("Number out of range", start);
		}

		return value;
	}

	private void readDigits(String message) {
		if (!nextIsDigit()) {
			throw error(message);
		}

		while (nextIsDigit()) {
			pos++;
		}
	}

	private boolean next(char c) {
		return pos < text.length() && text.charAt(pos) == c;
	}

	private boolean nextIsDigit() {
		return pos < text.length() && text.charAt(pos) >= '0' && text.charAt(pos) <= '9';
	}

	private void enter() {
		if (++depth > MAX_DEPTH) {
			throw error("Nested deeper than " + MAX_DEPTH + " levels");
		}
	}

	private void expect(String word) {
		if (!text.startsWith(word, pos)) {
			throw error("Expected '" + word + "'");
		}

		pos += word.length();
	}

	private char peek() {
		if (pos >= text.length()) {
			throw error("Unexpected end of JSON");
		}

		return text.charAt(pos);
	}

	private void skipWhitespace() {
		while (pos < text.length() && isWhitespace(text.charAt(pos))) {
			pos++;
		}
	}

	private IllegalArgumentException error(String message) {
		return error(message, pos);
	}

	private static IllegalArgumentException error(String message, int position) {
		return new IllegalArgumentException(message + " at position " + position);
	}

	private static boolean isWhitespace(char c) {
		return c == ' ' || c == '\t' || c == '\n' || c == '\r';
	}

	private static int hexDigit(char c) {
		if (c >= '0' && c <= '9') {
			return c - '0';
		}
		if (c >= 'a' && c <= 'f') {
			return c - 'a' + 10;
		}
		if (c >= 'A' && c <= 'F') {
			return c - 'A' + 10;
		}

		return -1;
	}

	/**
	 * @return the character for a message, readable whatever it is.
	 */
	private static String describe(char c) {
		if (c >= 0x20 && c < 0x7f) {
			return "'" + c + "'";
		}

		return String.format("U+%04X", (int) c);
	}

	/**
	 * @return the text as a JSON string for a message, cut short if long.
	 */
	private static String quote(String text) {
		StringBuilder out = new StringBuilder();
		writeString(out, text.length() > 32 ? text.substring(0, 32) + "..." : text);

		return out.toString();
	}
}
