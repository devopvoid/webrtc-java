/*
 * Copyright 2019 Alex Andres
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

package dev.onvoid.webrtc.logging;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.logging.Logging.Severity;

import org.junit.jupiter.api.Test;

class LoggingTests {

	@Test
	void logInfo() throws Exception {
		CountDownLatch latch = new CountDownLatch(3);

		LogSink sink = (severity, message) -> {
			assertTrue(severity.ordinal() > Severity.VERBOSE.ordinal());
			assertNotNull(message);

			latch.countDown();
		};

		Logging.addLogSink(Logging.Severity.INFO, sink);

		PeerConnectionFactory factory = new PeerConnectionFactory();

		latch.await();

		factory.dispose();
	}

}
