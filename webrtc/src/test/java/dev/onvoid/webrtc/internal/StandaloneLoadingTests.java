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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Uses each class that applications may use on their own in a JVM of its
 * own. In the JVM the other tests share, some earlier test has always loaded
 * the native library, which hides a class that needs it and does not load it.
 */
@Execution(ExecutionMode.SAME_THREAD)
class StandaloneLoadingTests {

	private static final long TIMEOUT_SECONDS = 60;


	@TestFactory
	Stream<DynamicTest> loadsLibraryOnItsOwn() {
		return Arrays.stream(StandaloneUse.CLASSES)
				.map(name -> DynamicTest.dynamicTest(name, () -> runAlone(name)));
	}

	private static void runAlone(String name) throws Exception {
		List<String> command = new ArrayList<>();
		command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
		command.add("-cp");
		command.add(classPath());
		command.add(StandaloneUse.class.getName());
		command.add(name);

		Process process = new ProcessBuilder(command)
				.redirectErrorStream(true)
				.start();

		// Read while it runs, so that a full pipe cannot stall it.
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		Thread reader = new Thread(() -> copy(process.getInputStream(), output));
		reader.start();

		assertTrue(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS), () -> {
			process.destroyForcibly();
			return name + " did not finish within " + TIMEOUT_SECONDS + " s";
		});
		reader.join();

		String log = new String(output.toByteArray(), StandardCharsets.UTF_8);
		assertEquals(0, process.exitValue(), () -> name + " failed in a fresh JVM:\n" + log);
	}

	/**
	 * The classes and libraries this JVM runs with, whether they are on the
	 * class path or, as for a module, on the module path.
	 */
	private static String classPath() {
		Set<String> entries = new LinkedHashSet<>();

		for (String property : new String[] { "jdk.module.path", "java.class.path" }) {
			String value = System.getProperty(property);

			if (value != null && !value.isEmpty()) {
				entries.addAll(Arrays.asList(value.split(File.pathSeparator)));
			}
		}

		return String.join(File.pathSeparator, entries);
	}

	private static void copy(InputStream input, ByteArrayOutputStream output) {
		byte[] buffer = new byte[8192];
		int read;

		try {
			while ((read = input.read(buffer)) != -1) {
				output.write(buffer, 0, read);
			}
		}
		catch (IOException e) {
			// The process ended; what was read is kept.
		}
	}
}
