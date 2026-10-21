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
 */
final class Json {

	private final String text;

	private int pos;


	private Json(String text) {
		this.text = text;
	}

	static Object parse(String text) {
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
				return readObject();
			case '[':
				return readArray();
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
				throw error("Unexpected character '" + c + "'");
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

			String name = readString();
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
					try {
						out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
					}
					catch (NumberFormatException ex) {
						throw error("Invalid unicode escape");
					}
					pos += 4;
					break;
				default:
					throw error("Invalid escape '\\" + e + "'");
			}
		}
	}

	private Double readNumber() {
		int start = pos;

		while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
			pos++;
		}

		try {
			return Double.valueOf(text.substring(start, pos));
		}
		catch (NumberFormatException e) {
			throw error("Invalid number");
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
		while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
			pos++;
		}
	}

	private IllegalArgumentException error(String message) {
		return new IllegalArgumentException(message + " at position " + pos);
	}
}
