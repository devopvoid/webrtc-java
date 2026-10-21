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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Gives each test a headless runtime of its own, which needs no audio
 * hardware, and collects what reaches its error handler.
 */
@Execution(ExecutionMode.SAME_THREAD)
abstract class ClientTestBase {

	static final long TIMEOUT_SECONDS = 15;

	final List<Throwable> errors = new CopyOnWriteArrayList<>();

	WebRtc rtc;


	@BeforeEach
	void createRuntime(TestInfo test) throws IOException {
		// A native crash ends the test JVM before any report names the test
		// that ran; the last line of this file does.
		Path log = Paths.get("target", "surefire-reports", "client-tests.log");
		Files.createDirectories(log.getParent());
		Files.write(log, Collections.singletonList(LocalTime.now() + " " + getClass().getSimpleName()
				+ "." + test.getTestMethod().map(Method::getName).orElse("?")),
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);

		rtc = WebRtc.builder()
				.audio(AudioMode.HEADLESS)
				.errorHandler(errors::add)
				.build();
	}

	@AfterEach
	void closeRuntime() {
		rtc.close();

		// Includes what closing reported, such as native objects that did
		// not go away.
		assertTrue(errors.isEmpty(), () -> "Errors: " + errors);
	}

	/**
	 * Creates two peers linked by a loopback signaling. The second one is
	 * polite.
	 */
	Peer[] pair() {
		return pair(UnaryOperator.identity());
	}

	/**
	 * Like {@link #pair()}, with options both peers share.
	 */
	Peer[] pair(UnaryOperator<PeerOptions.Builder> options) {
		SignalingPair link = Signaling.loopback();

		Peer caller = rtc.createPeer(options.apply(PeerOptions.builder())
				.name("caller")
				.signaling(link.first())
				.build());
		Peer callee = rtc.createPeer(options.apply(PeerOptions.builder())
				.name("callee")
				.signaling(link.second())
				.polite(true)
				.build());

		return new Peer[] { caller, callee };
	}

	static <T> T await(CompletableFuture<T> future) throws Exception {
		return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

}
