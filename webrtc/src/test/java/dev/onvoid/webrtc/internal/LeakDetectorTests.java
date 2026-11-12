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

package dev.onvoid.webrtc.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.onvoid.webrtc.media.audio.AudioResampler;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

class LeakDetectorTests {

	private static final long TIMEOUT_SECONDS = 60;


	@Test
	void offWatchesNothing() {
		// This JVM runs without the property.
		AudioResampler resampler = new AudioResampler(48000, 16000, 1);

		assertNull(LeakDetectionUse.record(resampler));

		resampler.dispose();
	}

	@Test
	void unreleasedIsReported() {
		LeakDetector.Record record = new LeakDetector.Record("Example", null);

		List<String> reports = runChecked(record);

		assertEquals(List.of("Example was not disposed before it became unreachable; its native object leaks"),
				reports);
		assertTrue(record.checked);
	}

	@Test
	void releasedIsNotReported() {
		LeakDetector.Record record = new LeakDetector.Record("Example", null);
		record.released = true;

		assertEquals(List.of(), runChecked(record));
		assertTrue(record.checked);
	}

	@Test
	void reportNamesOrigin() {
		LeakDetector.Record record = new LeakDetector.Record("Example", new Throwable("Created here"));

		List<String> reports = runChecked(record);

		assertEquals(1, reports.size());
		assertTrue(reports.get(0).contains("Created here"), reports.get(0));
		assertTrue(reports.get(0).contains(LeakDetectorTests.class.getName()), reports.get(0));
	}

	@Test
	void forgottenObjectsReported() throws Exception {
		Result result = run("-D" + LeakDetector.PROPERTY + "=true");

		assertEquals(0, result.exitValue, result.output);
	}

	@Test
	void reportedWithLoggingFirst() throws Exception {
		Result result = run("-D" + LeakDetector.PROPERTY + "=true",
				"-D" + LeakDetectionUse.LOGGING_FIRST_PROPERTY + "=true");

		assertEquals(0, result.exitValue, result.output);
	}

	@Test
	void traceNamesWhereCreated() throws Exception {
		Result result = run("-D" + LeakDetector.PROPERTY + "=true", "-D" + LeakDetector.TRACE_PROPERTY + "=true");

		assertEquals(0, result.exitValue, result.output);
		assertTrue(result.output.contains("Created here"), result.output);
		assertTrue(result.output.contains("LeakDetectionUse.forgetResampler"), result.output);
	}

	/** Runs the cleaner's work of the record here, with its reports taken. */
	private static List<String> runChecked(LeakDetector.Record record) {
		List<String> reports = new ArrayList<>();
		Consumer<String> reporter = LeakDetector.reporter;

		LeakDetector.reporter = reports::add;

		try {
			record.run();
		}
		finally {
			LeakDetector.reporter = reporter;
		}

		return reports;
	}

	private static Result run(String... properties) throws Exception {
		List<String> command = new ArrayList<>();
		command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
		command.addAll(Arrays.asList(properties));
		command.add("-cp");
		command.add(StandaloneLoadingTests.classPath());
		command.add(LeakDetectionUse.class.getName());

		Process process = new ProcessBuilder(command)
				.redirectErrorStream(true)
				.start();

		// Read while it runs, so that a full pipe cannot stall it.
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		Thread reader = new Thread(() -> StandaloneLoadingTests.copy(process.getInputStream(), output));
		reader.start();

		assertTrue(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS), () -> {
			process.destroyForcibly();
			return "did not finish within " + TIMEOUT_SECONDS + " s";
		});
		reader.join();

		return new Result(process.exitValue(), output.toString(StandardCharsets.UTF_8));
	}



	private static class Result {

		final int exitValue;

		final String output;


		Result(int exitValue, String output) {
			this.exitValue = exitValue;
			this.output = output;
		}
	}

}
