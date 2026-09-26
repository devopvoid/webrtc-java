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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.stream.Stream;

import dev.onvoid.webrtc.TestBase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the cleanup of extracted native libraries. What is swept is an
 * isolated directory set up here, never the real temporary directory, and the
 * time a sweep runs at is given rather than read from the clock.
 *
 * @author Alex Andres
 */
class NativeLoaderTests extends TestBase {

	/** Well past the age a sweep waits for. */
	private static final long LATER = NativeLoader.SWEEP_MIN_AGE_MS * 2;

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
