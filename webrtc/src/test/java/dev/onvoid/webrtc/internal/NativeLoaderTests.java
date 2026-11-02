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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import dev.onvoid.webrtc.TestBase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers where native libraries are looked for and the cleanup of extracted
 * ones. What is swept is an isolated directory set up here, never the real
 * temporary directory, and the time a sweep runs at is given rather than read
 * from the clock. The lookup is driven with a linker that only records what it
 * is handed; {@link LibraryLocationTests} loads the real library.
 *
 * @author Alex Andres
 */
class NativeLoaderTests extends TestBase {

	/** Well past the age a sweep waits for. */
	private static final long LATER = NativeLoader.SWEEP_MIN_AGE_MS * 2;

	/** A library to look for, which none of the tests below really loads. */
	private static final String LIB = "test-lib";

	private static final String LIB_FILE = System.mapLibraryName(LIB);

	@TempDir
	Path root;


	@Test
	void sweepsEndedProcess() throws Exception {
		Path directory = extraction("ended", true);

		NativeLoader.sweep(root, now() + LATER);

		assertFalse(Files.exists(directory));
	}

	@Test
	void sweepsUnlockedDirectory() throws Exception {
		// A process that ended before it could create its lock file.
		Path directory = extraction("unlocked", false);

		NativeLoader.sweep(root, now() + LATER);

		assertFalse(Files.exists(directory));
	}

	@Test
	void keepsDirectoryInUse() throws Exception {
		Path directory = extraction("in-use", true);

		try (FileChannel channel = FileChannel.open(
				directory.resolve(NativeLoader.LOCK_FILE_NAME), StandardOpenOption.WRITE);
			 FileLock lock = channel.lock()) {
			NativeLoader.sweep(root, now() + LATER);

			assertTrue(Files.exists(directory.resolve("library.dll")));
		}
	}

	@Test
	void keepsYoungDirectory() throws Exception {
		// A process may not hold its lock yet right after creating it.
		Path directory = extraction("young", false);

		NativeLoader.sweep(root, now());

		assertTrue(Files.exists(directory));
	}

	@Test
	void sweepsLegacyFiles() throws Exception {
		Path legacy = Files.createFile(root.resolve("webrtc-java-windows-x86_641234567890.dll"));
		Path unrelated = Files.createFile(root.resolve("webrtc-java-windows-x86_64.dll"));

		NativeLoader.sweep(root, now() + LATER);

		assertFalse(Files.exists(legacy));
		assertTrue(Files.exists(unrelated));
	}

	@Test
	void holdsOwnDirectory() throws Exception {
		// Elsewhere nothing of a loaded library is left to hold.
		assumeFalse(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));

		// Made by the load TestBase caused, so this only returns it.
		Path directory = NativeLoader.processDirectory(
				Paths.get(System.getProperty("java.io.tmpdir")));

		try (Stream<Path> files = Files.list(directory)) {
			// The library, next to the lock file.
			assertTrue(files.count() > 1, "nothing extracted");
		}

		assertTrue(NativeLoader.isInUse(directory));
	}

	@Test
	void libraryDirWins() throws Exception {
		Path dir = Files.createDirectory(root.resolve("installed"));
		Files.createFile(dir.resolve("dep.so"));
		Files.createFile(dir.resolve(LIB_FILE));
		RecordingLinker linker = new RecordingLinker();

		NativeLoader.load(LIB, Collections.singletonList("dep.so"),
				locations(dir, Collections.emptyList()), resources(LIB_FILE, "dep.so"), linker);

		assertEquals(Arrays.asList(dir.resolve("dep.so"), dir.resolve(LIB_FILE)), linker.loaded);
		assertTrue(linker.libraries.isEmpty());
	}

	@Test
	void missingDependencyLeftToLinker() throws Exception {
		Path dir = Files.createDirectory(root.resolve("installed"));
		Files.createFile(dir.resolve(LIB_FILE));
		RecordingLinker linker = new RecordingLinker();

		NativeLoader.load(LIB, Collections.singletonList("dep.so"),
				locations(dir, Collections.emptyList()), resources(), linker);

		assertEquals(Collections.singletonList(dir.resolve(LIB_FILE)), linker.loaded);
	}

	@Test
	void libraryDirFailureReported() throws Exception {
		Path dir = Files.createDirectory(root.resolve("installed"));
		Files.createFile(dir.resolve(LIB_FILE));
		RecordingLinker linker = new RecordingLinker();
		linker.failLoad = true;

		UnsatisfiedLinkError error = assertThrows(UnsatisfiedLinkError.class,
				() -> NativeLoader.load(LIB, Collections.emptyList(),
						locations(dir, Collections.emptyList()), resources(LIB_FILE), linker));

		// The directory was named on purpose, so nothing else is tried.
		assertTrue(error.getMessage().contains(NativeLoader.LIBRARY_DIR_PROPERTY), error.getMessage());
		assertTrue(error.getMessage().contains(dir.resolve(LIB_FILE).toString()), error.getMessage());
		assertTrue(linker.libraries.isEmpty());
	}

	@Test
	void extractsIntoGivenRoot() throws Exception {
		// Elsewhere the process keeps the one directory it made at its first load.
		assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));

		Path extractRoot = root.resolve("extract").resolve("nested");
		RecordingLinker linker = new RecordingLinker();

		NativeLoader.load(LIB, Collections.singletonList("dep.so"),
				locations(root.resolve("absent"), extractRoot), resources(LIB_FILE, "dep.so"), linker);

		assertEquals(2, linker.loaded.size());
		assertEquals("dep.so", linker.loaded.get(0).getFileName().toString());
		assertEquals(LIB_FILE, linker.loaded.get(1).getFileName().toString());

		for (Path loaded : linker.loaded) {
			assertEquals(extractRoot, loaded.getParent().getParent());
		}

		// Gone once loaded, since a loaded library stays mapped.
		try (Stream<Path> left = Files.list(extractRoot)) {
			assertEquals(0, left.count());
		}
	}

	@Test
	void fallsBackToLibraryPath() throws Exception {
		Path dir = Files.createDirectory(root.resolve("library-path"));
		Files.createFile(dir.resolve("dep.so"));
		RecordingLinker linker = new RecordingLinker();

		NativeLoader.load(LIB, Collections.singletonList("dep.so"),
				locations(null, Collections.singletonList(dir)), resources(), linker);

		assertEquals(Collections.singletonList(dir.resolve("dep.so")), linker.loaded);
		assertEquals(Collections.singletonList(LIB), linker.libraries);
	}

	@Test
	void failedExtractionFallsBack() throws Exception {
		// As on a temporary directory mounted noexec.
		RecordingLinker linker = new RecordingLinker();
		linker.failLoad = true;

		NativeLoader.load(LIB, Collections.emptyList(),
				locations(null, root.resolve("extract")), resources(LIB_FILE), linker);

		assertEquals(Collections.singletonList(LIB), linker.libraries);
	}

	@Test
	void failureNamesPlacesTried() throws Exception {
		Path dir = root.resolve("installed");
		Path libraryPath = root.resolve("library-path");
		RecordingLinker linker = new RecordingLinker();
		linker.failLoadLibrary = true;

		UnsatisfiedLinkError error = assertThrows(UnsatisfiedLinkError.class,
				() -> NativeLoader.load(LIB, Collections.emptyList(),
						new NativeLoader.Locations(dir, root, Collections.singletonList(libraryPath)),
						resources(), linker));

		String message = error.getMessage();

		assertTrue(message.contains(LIB_FILE), message);
		assertTrue(message.contains(NativeLoader.LIBRARY_DIR_PROPERTY + " " + dir), message);
		assertTrue(message.contains("class path"), message);
		assertTrue(message.contains("System.loadLibrary: no " + LIB + " in java.library.path"), message);
		assertTrue(message.contains(NativeLoader.EXTRACT_DIR_PROPERTY), message);
		assertTrue(message.contains(NativeLoader.platform()), message);
	}

	@Test
	void detectsMusl() throws Exception {
		Path glibc = Files.createDirectory(root.resolve("glibc"));
		Files.createFile(glibc.resolve("ld-linux-x86-64.so.2"));
		Path musl = Files.createDirectory(root.resolve("musl"));
		Files.createFile(musl.resolve("ld-musl-x86_64.so.1"));

		assertFalse(NativeLoader.isMusl(glibc));
		assertTrue(NativeLoader.isMusl(musl));
		assertFalse(NativeLoader.isMusl(root.resolve("absent")));

		assertTrue(NativeLoader.failureMessage(LIB_FILE, Collections.emptyList(), true).contains("musl"));
		assertFalse(NativeLoader.failureMessage(LIB_FILE, Collections.emptyList(), false).contains("musl"));
	}

	private NativeLoader.Locations locations(Path libraryDir, List<Path> searchPath) {
		return new NativeLoader.Locations(libraryDir, root.resolve("extract"), searchPath);
	}

	private static NativeLoader.Locations locations(Path libraryDir, Path extractRoot) {
		return new NativeLoader.Locations(libraryDir, extractRoot, Collections.emptyList());
	}

	/** A class path with the given resources, each holding a few bytes. */
	private static NativeLoader.Resources resources(String... names) {
		List<String> present = Arrays.asList(names);

		return name -> present.contains(name)
				? new ByteArrayInputStream(new byte[] { 1, 2, 3 })
				: null;
	}



	/** Records what it is handed instead of loading it. */
	private static final class RecordingLinker implements NativeLoader.Linker {

		final List<Path> loaded = new ArrayList<>();

		final List<String> libraries = new ArrayList<>();

		boolean failLoad;

		boolean failLoadLibrary;


		@Override
		public void load(Path file) {
			if (failLoad) {
				throw new UnsatisfiedLinkError(file + ": failed to map segment from shared object");
			}

			loaded.add(file);
		}

		@Override
		public void loadLibrary(String name) {
			if (failLoadLibrary) {
				throw new UnsatisfiedLinkError("no " + name + " in java.library.path");
			}

			libraries.add(name);
		}
	}

	/**
	 * An extraction directory holding a library, made to look as old as a
	 * directory left behind by an ended process.
	 */
	private Path extraction(String name, boolean withLockFile) throws IOException {
		Path directory = Files.createDirectory(root.resolve(NativeLoader.DIRECTORY_PREFIX + name));

		Files.createFile(directory.resolve("library.dll"));

		if (withLockFile) {
			Files.createFile(directory.resolve(NativeLoader.LOCK_FILE_NAME));
		}

		Files.setLastModifiedTime(directory, FileTime.fromMillis(now()));

		return directory;
	}

	private static long now() {
		return System.currentTimeMillis();
	}

}
