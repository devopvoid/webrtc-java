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

package dev.onvoid.webrtc.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Loads the real native library in a JVM of its own from each place the
 * loader looks in. The library is taken out of the native JAR into a directory
 * of the test, and the JAR left off the class path of the JVM where it is to
 * be found elsewhere.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class LibraryLocationTests {

	private static final long TIMEOUT_SECONDS = 60;

	/** Something that needs the library, and nothing else, to work. */
	private static final String USE = "AudioResampler";

	/** Shared by all tests, so that the library is taken out only once. */
	@TempDir
	static Path root;

	/** A directory holding just the library. */
	private Path libraryDir;

	/** The class path of this JVM without the native JAR. */
	private String classPathWithoutNatives;


	@BeforeAll
	void extractLibrary() throws Exception {
		String fileName = System.mapLibraryName("webrtc-java-" + NativeLoader.platform());
		URL url = NativeLoader.class.getClassLoader().getResource(fileName);
		assertNotNull(url, "no " + fileName + " on the class path");

		libraryDir = Files.createDirectory(root.resolve("installed"));

		try (InputStream input = url.openStream()) {
			Files.copy(input, libraryDir.resolve(fileName));
		}

		Path nativeEntry = classPathEntry(url, fileName);

		classPathWithoutNatives = Arrays.stream(StandaloneLoadingTests.classPath().split(File.pathSeparator))
				.filter(entry -> !Paths.get(entry).toAbsolutePath().equals(nativeEntry))
				.collect(Collectors.joining(File.pathSeparator));

		assertNotEquals(StandaloneLoadingTests.classPath(), classPathWithoutNatives,
				"the native JAR " + nativeEntry + " is not on the class path");
	}

	@Test
	void loadsFromLibraryDir() throws Exception {
		Result result = run(classPathWithoutNatives,
				"-D" + NativeLoader.LIBRARY_DIR_PROPERTY + "=" + libraryDir);

		assertEquals(0, result.exitValue, result.output);
	}

	@Test
	void loadsFromLibraryPath() throws Exception {
		Result result = run(classPathWithoutNatives, "-Djava.library.path=" + libraryDir);

		assertEquals(0, result.exitValue, result.output);
	}

	@Test
	void extractsIntoExtractDir() throws Exception {
		Path extractDir = root.resolve("extract");

		Result result = run(StandaloneLoadingTests.classPath(),
				"-D" + NativeLoader.EXTRACT_DIR_PROPERTY + "=" + extractDir);

		assertEquals(0, result.exitValue, result.output);
		assertTrue(Files.isDirectory(extractDir), "nothing extracted into " + extractDir);
	}

	@Test
	void failureNamesPlacesTried() throws Exception {
		Result result = run(classPathWithoutNatives,
				"-D" + NativeLoader.LIBRARY_DIR_PROPERTY + "=" + root.resolve("absent"),
				"-Djava.library.path=" + root.resolve("absent"));

		assertNotEquals(0, result.exitValue, result.output);
		assertTrue(result.output.contains("Tried:"), result.output);
		assertTrue(result.output.contains(NativeLoader.LIBRARY_DIR_PROPERTY), result.output);
		assertTrue(result.output.contains("java.library.path"), result.output);
	}

	/**
	 * The class path entry, a JAR or a directory, that the given resource is
	 * found in.
	 */
	private static Path classPathEntry(URL url, String fileName) throws Exception {
		URLConnection connection = url.openConnection();

		if (connection instanceof JarURLConnection) {
			return Paths.get(((JarURLConnection) connection).getJarFileURL().toURI()).toAbsolutePath();
		}

		Path file = Paths.get(url.toURI()).toAbsolutePath();

		// A directory holds the library at its top, as the JAR does.
		assertEquals(fileName, file.getFileName().toString());

		return file.getParent();
	}

	private static Result run(String classPath, String... properties) throws Exception {
		List<String> command = new ArrayList<>();
		command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
		command.addAll(Arrays.asList(properties));
		command.add("-cp");
		command.add(classPath);
		command.add(StandaloneUse.class.getName());
		command.add(USE);

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

		return new Result(process.exitValue(), new String(output.toByteArray(), StandardCharsets.UTF_8));
	}



	private static final class Result {

		final int exitValue;

		final String output;


		Result(int exitValue, String output) {
			this.exitValue = exitValue;
			this.output = output;
		}
	}

}
