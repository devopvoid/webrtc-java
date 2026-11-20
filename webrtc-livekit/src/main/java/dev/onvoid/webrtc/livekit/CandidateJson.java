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

import java.util.HashMap;
import java.util.Map;

import dev.onvoid.webrtc.RTCIceCandidate;

/**
 * The JSON form of an RTCIceCandidateInit, which LiveKit's trickle messages
 * carry as a string. Reads only flat objects of strings, numbers, booleans and
 * nulls, which is all a candidate init holds.
 */
final class CandidateJson {

	private CandidateJson() {
	}

	static String encode(RTCIceCandidate candidate) {
		StringBuilder json = new StringBuilder(candidate.sdp.length() + 64);
		json.append("{\"candidate\":");
		quote(json, candidate.sdp);
		json.append(",\"sdpMid\":");
		if (candidate.sdpMid == null) {
			json.append("null");
		}
		else {
			quote(json, candidate.sdpMid);
		}
		json.append(",\"sdpMLineIndex\":").append(candidate.sdpMLineIndex).append('}');
		return json.toString();
	}

	static RTCIceCandidate decode(String json) {
		Map<String, Object> fields = parse(json);
		Object candidate = fields.get("candidate");
		Object mid = fields.get("sdpMid");
		Object index = fields.get("sdpMLineIndex");

		if (!(candidate instanceof String)) {
			throw new IllegalArgumentException("No candidate in " + json);
		}

		return new RTCIceCandidate(mid instanceof String ? (String) mid : null,
				index instanceof Number ? ((Number) index).intValue() : 0, (String) candidate);
	}

	private static void quote(StringBuilder json, String value) {
		json.append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"': json.append("\\\""); break;
				case '\\': json.append("\\\\"); break;
				case '\n': json.append("\\n"); break;
				case '\r': json.append("\\r"); break;
				case '\t': json.append("\\t"); break;
				default:
					if (c < 0x20) {
						json.append(String.format("\\u%04x", (int) c));
					}
					else {
						json.append(c);
					}
			}
		}
		json.append('"');
	}

	private static Map<String, Object> parse(String json) {
		Reader reader = new Reader(json);
		Map<String, Object> fields = new HashMap<>();

		reader.expect('{');
		if (!reader.consume('}')) {
			do {
				String key = reader.string();
				reader.expect(':');
				fields.put(key, reader.value());
			}
			while (reader.consume(','));
			reader.expect('}');
		}

		return fields;
	}



	private static final class Reader {

		private final String text;

		private int pos;


		Reader(String text) {
			this.text = text;
		}

		void skipSpace() {
			while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
				pos++;
			}
		}

		boolean consume(char c) {
			skipSpace();
			if (pos < text.length() && text.charAt(pos) == c) {
				pos++;
				return true;
			}
			return false;
		}

		void expect(char c) {
			if (!consume(c)) {
				throw new IllegalArgumentException("Expected '" + c + "' at " + pos + " in " + text);
			}
		}

		Object value() {
			skipSpace();
			if (pos >= text.length()) {
				throw new IllegalArgumentException("Unexpected end of " + text);
			}

			char c = text.charAt(pos);
			if (c == '"') {
				return string();
			}
			if (text.startsWith("null", pos)) {
				pos += 4;
				return null;
			}
			if (text.startsWith("true", pos)) {
				pos += 4;
				return Boolean.TRUE;
			}
			if (text.startsWith("false", pos)) {
				pos += 5;
				return Boolean.FALSE;
			}

			int start = pos;
			while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
				pos++;
			}
			if (start == pos) {
				throw new IllegalArgumentException("Unexpected '" + c + "' at " + pos + " in " + text);
			}
			return Double.parseDouble(text.substring(start, pos));
		}

		String string() {
			expect('"');
			StringBuilder value = new StringBuilder();

			while (pos < text.length()) {
				char c = text.charAt(pos++);
				if (c == '"') {
					return value.toString();
				}
				if (c != '\\') {
					value.append(c);
					continue;
				}
				if (pos >= text.length()) {
					break;
				}

				char escaped = text.charAt(pos++);
				switch (escaped) {
					case 'n': value.append('\n'); break;
					case 'r': value.append('\r'); break;
					case 't': value.append('\t'); break;
					case 'b': value.append('\b'); break;
					case 'f': value.append('\f'); break;
					case 'u':
						if (pos + 4 > text.length()) {
							throw new IllegalArgumentException("Bad escape in " + text);
						}
						value.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
						pos += 4;
						break;
					default: value.append(escaped);
				}
			}

			throw new IllegalArgumentException("Unterminated string in " + text);
		}
	}

}
