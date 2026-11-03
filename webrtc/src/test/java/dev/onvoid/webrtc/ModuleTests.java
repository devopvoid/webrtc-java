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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.module.ModuleDescriptor;

import org.junit.jupiter.api.Test;

class ModuleTests {

	@Test
	void needsNoDesktopModule() {
		// The tests themselves read java.desktop, so the descriptor tells what
		// applications get, not the module the tests run in.
		ModuleDescriptor descriptor = PeerConnectionFactory.class.getModule().getDescriptor();

		assertNotNull(descriptor, "Not run as a named module");
		assertFalse(descriptor.requires().stream().anyMatch(r -> r.name().equals("java.desktop")),
				"webrtc.java requires java.desktop");
		assertFalse(descriptor.exports().stream()
				.anyMatch(e -> e.source().equals("dev.onvoid.webrtc.client.swing")),
				"The Swing view belongs to webrtc.java.swing");
	}

}
