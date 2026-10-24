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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class JsonTests {

	/** The number grammar of RFC 8259, section 6. */
	private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

	@TestFactory
	List<DynamicTest> invalidJsonRejected() {
		// Input, then the start of the message it fails with.
		return rejected(
				"\"a\nb\"", "Unescaped control character U+000A in a string at position 2",
				"\"\r\n\"", "Unescaped control character U+000D",
				"\"\t\"", "Unescaped control character U+0009",
				"\"\u0000\"", "Unescaped control character U+0000",
				"\"\u001F\"", "Unescaped control character U+001F",
				"{\"a\nb\":1}", "Unescaped control character U+000A",
				"\"\\u+041\"", "Invalid unicode escape at position 3",
				"\"\\u-041\"", "Invalid unicode escape",
				"\"\\u00G0\"", "Invalid unicode escape at position 5",
				"\"\\u\uFF10041\"", "Invalid unicode escape",
				"\"\\u12\"", "Incomplete unicode escape",
				"\"\\x\"", "Invalid escape '\\x'",
				"\"abc", "Unterminated string",
				"01", "Leading zero in a number at position 1",
				"-01", "Leading zero in a number",
				"00", "Leading zero in a number",
				"1.", "Expected a digit after the decimal point at position 2",
				"1.e5", "Expected a digit after the decimal point",
				".5", "Unexpected character '.'",
				"+1", "Unexpected character '+'",
				"-", "Expected a digit at position 1",
				"--1", "Expected a digit",
				"-a", "Expected a digit",
				"1e", "Expected a digit in the exponent",
				"1e+", "Expected a digit in the exponent",
				"1E-", "Expected a digit in the exponent",
				"[0,1e999]", "Number out of range at position 3",
				"-1e999", "Number out of range",
				"Infinity", "Unexpected character 'I'",
				"NaN", "Unexpected character 'N'",
				"0x10", "Unexpected text after the JSON value",
				"\f1", "Unexpected character U+000C",
				"\u000B1", "Unexpected character U+000B",
				"\u00A01", "Unexpected character U+00A0",
				"\u20281", "Unexpected character U+2028",
				"[1,\u001C2]", "Unexpected character U+001C",
				"{\"a\":1,\"a\":2}", "Duplicate member name \"a\" at position 7",
				"{\"\\u0061\":1,\"a\":2}", "Duplicate member name \"a\"",
				"{\"a\":1,\"b\":{},\"a\":null}", "Duplicate member name \"a\"",
				"[1,]", "Unexpected character ']'",
				"{\"a\":1,}", "Expected a member name",
				"{'a':1}", "Expected a member name",
				"{\"a\" 1}", "Expected ':'",
				"[1 2]", "Expected ',' or ']'",
				"[1]//", "Unexpected text after the JSON value",
				"tru", "Expected 'true'",
				"", "Unexpected end of JSON",
				" ", "Unexpected end of JSON");
	}

	@TestFactory
	List<DynamicTest> validJsonAccepted() {
		Map<String, Object> object = new LinkedHashMap<>();
		object.put("a", Collections.singletonMap("a", 1.0));

		// Input, then the value it is read as.
		return accepted(
				"0", 0.0,
				"-0", -0.0,
				"1.5e3", 1500.0,
				"1E-2", 0.01,
				"-12.25E+1", -122.5,
				"1e-400", 0.0,
				"1.7976931348623157e308", Double.MAX_VALUE,
				"\"\\u0041\\u00e9\\uD83D\\uDE00\\/\"", "A\u00e9\uD83D\uDE00/",
				"\"\u007F\u00e4\u2028\"", "\u007F\u00e4\u2028",
				" \t\n\r[ 1 , {} ] \r\n", Arrays.asList(1.0, Collections.emptyMap()),
				"{\"a\":{\"a\":1}}", object,
				"null", null);
	}

	@Test
	void numbersFollowGrammar() {
		// Every text of up to six of these chars, which make up every part of
		// the grammar and most of the ways to get it wrong.
		char[] alphabet = "-+.eE019".toCharArray();
		List<String> texts = new ArrayList<>();
		texts.add("");

		for (int length = 1, from = 0; length <= 6; length++) {
			int to = texts.size();

			for (int i = from; i < to; i++) {
				for (char c : alphabet) {
					texts.add(texts.get(i) + c);
				}
			}

			from = to;
		}

		// And longer ones, which reach the exponents that overflow a double.
		Random random = new Random(1);

		for (int i = 0; i < 20_000; i++) {
			char[] text = new char[7 + random.nextInt(18)];

			for (int j = 0; j < text.length; j++) {
				text[j] = alphabet[random.nextInt(alphabet.length)];
			}

			texts.add(new String(text));
		}

		for (String text : texts) {
			if (NUMBER.matcher(text).matches() && Double.isFinite(Double.parseDouble(text))) {
				assertEquals(Double.valueOf(text), Json.parse(text), show(text));
			}
			else {
				assertRejected(text);
			}
		}
	}

	@Test
	void stringCharsFollowGrammar() {
		for (int i = 0; i <= Character.MAX_VALUE; i++) {
			char c = (char) i;
			String raw = "\"" + c + "\"";

			if (c >= 0x20 && c != '"' && c != '\\') {
				assertEquals(String.valueOf(c), Json.parse(raw), show(raw));
			}
			else {
				assertRejected(raw);
			}

			assertEquals(String.valueOf(c), Json.parse(write(String.valueOf(c))), show(raw));
		}
	}

	@Test
	void escapesFollowGrammar() {
		for (int i = 0; i <= Character.MAX_VALUE; i++) {
			char c = (char) i;
			String escape = "\"\\" + c + "\"";

			if ("\"\\/bfnrt".indexOf(c) >= 0) {
				assertEquals(1, ((String) Json.parse(escape)).length(), show(escape));
			}
			else {
				assertRejected(escape);
			}

			// Only ASCII hex digits, not signs nor digits of other scripts.
			String unicode = "\"\\u00" + c + "1\"";
			int digit = "0123456789abcdef".indexOf(Character.toLowerCase(c));

			if (c < 0x80 && digit >= 0) {
				assertEquals(String.valueOf((char) (digit << 4 | 1)), Json.parse(unicode), show(unicode));
			}
			else {
				assertRejected(unicode);
			}
		}
	}

	@Test
	void whitespaceFollowsGrammar() {
		for (int i = 0; i <= Character.MAX_VALUE; i++) {
			char c = (char) i;
			String array = "[" + c + "]";

			if (" \t\n\r".indexOf(c) >= 0) {
				assertEquals(Collections.emptyList(), Json.parse(array), show(array));
			}
			else if (c >= '0' && c <= '9') {
				assertEquals(Collections.singletonList((double) (c - '0')), Json.parse(array), show(array));
			}
			else {
				assertRejected(array);
			}
		}
	}

	@Test
	void generatedDocumentsRoundTrip() {
		Random random = new Random(2);

		for (int i = 0; i < 2_000; i++) {
			Object value = randomValue(random, 0);
			StringBuilder out = new StringBuilder();
			write(out, value, random);

			assertEquals(value, Json.parse(out.toString()), show(out.toString()));
		}
	}

	private static List<DynamicTest> rejected(String... cases) {
		List<DynamicTest> tests = new ArrayList<>();

		for (int i = 0; i < cases.length; i += 2) {
			String text = cases[i];
			String message = cases[i + 1];

			tests.add(DynamicTest.dynamicTest("`" + show(text) + "`", () -> {
				IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Json.parse(text));

				assertTrue(e.getMessage().startsWith(message), e.getMessage());
			}));
		}

		return tests;
	}

	private static List<DynamicTest> accepted(Object... cases) {
		List<DynamicTest> tests = new ArrayList<>();

		for (int i = 0; i < cases.length; i += 2) {
			String text = (String) cases[i];
			Object value = cases[i + 1];

			tests.add(DynamicTest.dynamicTest("`" + show(text) + "`", () -> assertEquals(value, Json.parse(text))));
		}

		return tests;
	}

	private static void assertRejected(String text) {
		try {
			Object value = Json.parse(text);

			fail(show(text) + " was read as " + (value instanceof String ? show((String) value) : value));
		}
		catch (IllegalArgumentException e) {
			// Rejected, as it should be.
		}
	}

	private static Object randomValue(Random random, int depth) {
		switch (random.nextInt(depth < 4 ? 7 : 5)) {
			case 0:
				return null;
			case 1:
				return random.nextBoolean();
			case 2:
				double d = Double.longBitsToDouble(random.nextLong());
				return Double.isFinite(d) ? d : (double) random.nextInt();
			case 3:
				return (double) random.nextInt(1000) - 500;
			case 4:
				return randomString(random);
			case 5:
				List<Object> array = new ArrayList<>();
				for (int i = random.nextInt(4); i > 0; i--) {
					array.add(randomValue(random, depth + 1));
				}
				return array;
			default:
				Map<String, Object> object = new LinkedHashMap<>();
				for (int i = random.nextInt(4); i > 0; i--) {
					object.put(randomString(random), randomValue(random, depth + 1));
				}
				return object;
		}
	}

	private static String randomString(Random random) {
		// Half of them the chars that need care: controls, quotes, backslashes.
		String tricky = "\u0000\u001F\"\\/ \n\r\taZ\u007F\u2028\uD83D";
		char[] chars = new char[random.nextInt(12)];

		for (int i = 0; i < chars.length; i++) {
			chars[i] = random.nextBoolean()
					? tricky.charAt(random.nextInt(tricky.length()))
					: (char) random.nextInt(Character.MAX_VALUE + 1);
		}

		return new String(chars);
	}

	private static void write(StringBuilder out, Object value, Random random) {
		space(out, random);

		if (value instanceof String) {
			Json.writeString(out, (String) value);
		}
		else if (value instanceof List) {
			out.append('[');
			String separator = "";

			for (Object item : (List<?>) value) {
				out.append(separator);
				write(out, item, random);
				separator = ",";
			}

			space(out, random);
			out.append(']');
		}
		else if (value instanceof Map) {
			out.append('{');
			String separator = "";

			for (Map.Entry<?, ?> member : ((Map<?, ?>) value).entrySet()) {
				out.append(separator);
				space(out, random);
				Json.writeString(out, (String) member.getKey());
				space(out, random);
				out.append(':');
				write(out, member.getValue(), random);
				separator = ",";
			}

			space(out, random);
			out.append('}');
		}
		else {
			// Double.toString writes valid JSON numbers for finite values,
			// such as -1.0E-5.
			out.append(value);
		}

		space(out, random);
	}

	private static void space(StringBuilder out, Random random) {
		for (int i = random.nextInt(3); i > 0; i--) {
			out.append(" \t\n\r".charAt(random.nextInt(4)));
		}
	}

	private static String write(String value) {
		StringBuilder out = new StringBuilder();
		Json.writeString(out, value);

		return out.toString();
	}

	/**
	 * @return the text, readable in a test name or message.
	 */
	private static String show(String text) {
		StringBuilder out = new StringBuilder();

		for (char c : text.toCharArray()) {
			if (c >= 0x20 && c < 0x7f) {
				out.append(c);
			}
			else {
				out.append(String.format("\\u%04X", (int) c));
			}
		}

		return out.toString();
	}

}
