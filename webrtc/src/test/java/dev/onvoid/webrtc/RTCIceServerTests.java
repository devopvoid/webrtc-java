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

package dev.onvoid.webrtc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RTCIceServerTests {

	@Test
	void toStringHidesPassword() {
		RTCIceServer server = new RTCIceServer();
		server.urls.add("turn:127.0.0.1:3478");
		server.username = "user";
		server.password = "s3cr3t-t0k3n";

		String text = server.toString();

		assertFalse(text.contains(server.password), text);
		// Computed from the password, so a guess could be checked against it.
		assertFalse(text.contains(String.valueOf(server.hashCode())), text);
		assertTrue(text.contains("password=***"), text);
		assertTrue(text.contains("username=user"), text);
		assertTrue(text.contains("turn:127.0.0.1:3478"), text);
	}

	@Test
	void toStringTellsNoPassword() {
		String text = new RTCIceServer().toString();

		assertTrue(text.contains("password=null"), text);
	}
}
