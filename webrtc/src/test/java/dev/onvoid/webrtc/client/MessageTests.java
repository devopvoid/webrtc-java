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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class MessageTests {

	@Test
	void metadataOmitsContent() {
		assertEquals("Message[type=text, length=6 bytes]", text("secret").toString());
		assertEquals("Message[type=binary, length=3 bytes]",
				new Message(new byte[] { 1, 2, 3 }, true).toString());
		assertTrue(new Message(new byte[1_000_000], false).toString().length() < 64);
		assertEquals("secret", text("secret").text());
	}

	@Test
	void boundedPreview() {
		assertEquals("Message[type=text, length=6 bytes] preview=\"sec\" (truncated)",
				text("secret").preview(3));
		assertTrue(text("secret").preview(0).endsWith("preview=\"\" (truncated)"));
		assertTrue(text("").preview(0).endsWith("preview=\"\""));
		assertTrue(text("é😀!").preview(4).endsWith("preview=\"é\" (truncated)"));
		assertTrue(text("é😀!").preview(6).endsWith("preview=\"é😀\" (truncated)"));
		assertTrue(text("é😀!").preview(7).endsWith("preview=\"é😀!\""));
	}

	@Test
	void escapesLogControls() {
		String preview = text("a\n\r\t\u001b\u2028\u202e\"\\").preview(100);
		assertTrue(preview.endsWith("preview=\"a\\u000a\\u000d\\u0009\\u001b\\u2028\\u202e\\\"\\\\\""));
		assertFalse(preview.contains("\n"));
	}

	@Test
	void masksBeforeEscaping() {
		AtomicReference<String> inspected = new AtomicReference<>();
		String preview = text("token=secret and more").preview(12, prefix -> {
			inspected.set(prefix);
			return prefix.replace("secret", "[hidden]");
		});
		assertEquals("token=secret", inspected.get());
		assertFalse(preview.contains("secret"));
		assertTrue(preview.contains("token=[hidden]"));
		assertTrue(text("abc").preview(2, prefix -> "123456789").endsWith("preview=\"1234\" (truncated)"));
	}

	@Test
	void binaryPreview() {
		Message message = new Message(new byte[] { 0, (byte) 0xff, 42 }, true);
		assertTrue(message.preview(2).endsWith("preview=\"00ff\" (truncated)"));
		assertTrue(message.preview(3).endsWith("preview=\"00ff2a\""));
		assertTrue(message.preview(3, prefix -> "hidden").endsWith("preview=\"hidden\""));
	}

	@Test
	void rejectsInvalidPreview() {
		assertThrows(IllegalArgumentException.class, () -> text("x").preview(-1));
		assertThrows(NullPointerException.class, () -> text("x").preview(1, null));
		assertThrows(NullPointerException.class, () -> text("x").preview(1, prefix -> null));
	}

	private static Message text(String value) {
		return new Message(value.getBytes(StandardCharsets.UTF_8), false);
	}
}
