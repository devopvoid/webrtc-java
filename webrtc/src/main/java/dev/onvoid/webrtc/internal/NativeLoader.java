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

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Loads a native library from inside a JAR.
 * <p>
 * A library has to be a file to be loaded, so it is extracted to a temporary
 * directory first. Where the file system lets a loaded library be deleted, as
 * on Linux and macOS, that directory is gone again as soon as everything in it
 * is loaded. Windows does not: a loaded library stays locked until the process
 * ends, so the process cannot clean up after itself, and deleting on exit
 * never succeeds. There the process keeps one directory, marked as in use by a
 * lock it holds until it ends, and each process sweeps away the directories of
 * processes that have ended before it extracts anything of its own.
 *
 * @author Alex Andres
 */
public class NativeLoader {

	/** What every extraction directory is named with. */
	static final String DIRECTORY_PREFIX = "webrtc-java-natives-";

	/** The file whose lock marks an extraction directory as in use. */
	static final String LOCK_FILE_NAME = ".lock";

	/**
	 * How old an extraction directory has to be before a sweep considers it.
	 * A process that has only just created its directory may not hold its
	 * lock yet, and must not lose the directory in the meantime.
	 */
	static final long SWEEP_MIN_AGE_MS = TimeUnit.MINUTES.toMillis(1);

	/**
	 * What earlier versions of this loader left behind on Windows: the
	 * library itself under a randomly numbered temporary file name.
	 */
	private static final Pattern LEGACY_FILE =
			Pattern.compile("webrtc-java-windows-(x86_64|aarch64|aarch32)\\d+\\.dll");

	private static final Set<String> LOADED_LIB_SET = new HashSet<>();

	/** Whether this process has swept up after earlier ones yet. */
	private static boolean swept;

	/**
	 * Where this process keeps its libraries, on a file system that cannot
	 * delete them while they are loaded. Created on first use.
	 */
	private static Path processDirectory;

	/**
	 * Held until the process ends, marking {@link #processDirectory} as in
	 * use. Kept reachable, since the lock goes with its channel.
	 */
	@SuppressWarnings("unused")
	private static FileLock processLock;


	/**
	 * Loads the specified native library. The <code>libName</code> argument
	 * must not contain any platform specific prefix, file extension or path.
	 *
	 * @param libName The name of library to load.
	 *
	 * @throws Exception if the library could not be loaded.
	 *
	 * @see System#load(String)
	 * @see System#loadLibrary(String)
	 */
	public static void loadLibrary(final String libName) throws Exception {
		loadLibrary(libName, new String[0]);
	}

	/**
	 * Loads the specified native library together with the shared libraries it
	 * needs, which a native extension module packages next to it.
	 * <p>
	 * The dependencies are loaded first, in the given order, so that by the
	 * time the library itself is loaded every symbol it imports is already in
	 * the process. They are extracted into one temporary directory under the
	 * exact names given, because a dynamic linker matches an already-loaded
	 * module by file name: a randomly named copy would be loaded a second
	 * time, or not found at all.
	 *
	 * @param libName      The name of the library to load, without any
	 *                     platform specific prefix, file extension or path.
	 * @param dependencies The file names of the libraries to load first,
	 *                     exactly as they are named in the JAR, in the order
	 *                     they depend on each other.
	 *
	 * @throws Exception if one of the libraries could not be loaded.
	 */
	public static synchronized void loadLibrary(final String libName,
			final String... dependencies) throws Exception {
		if (LOADED_LIB_SET.contains(libName)) {
			return;
		}

		Path tempRoot = Paths.get(System.getProperty("java.io.tmpdir"));

		if (!swept) {
			swept = true;

			sweep(tempRoot, System.currentTimeMillis());
		}

		String libFileName = System.mapLibraryName(
				libName + "-" + getOSFamily() + "-" + getOSArch());

		List<String> fileNames = new ArrayList<>();

		Collections.addAll(fileNames, dependencies);
		fileNames.add(libFileName);

		if (canDeleteLoaded()) {
			// A loaded library stays mapped after its file is gone, so the
			// directory is only needed for as long as the loading takes.
			Path directory = Files.createTempDirectory(tempRoot, DIRECTORY_PREFIX);

			try {
				for (String fileName : fileNames) {
					loadFromDirectory(directory, fileName);
				}
			}
			finally {
				deleteDirectory(directory);
			}
		}
		else {
			Path directory = processDirectory(tempRoot);

			for (String fileName : fileNames) {
				loadFromDirectory(directory, fileName);
			}
		}

		LOADED_LIB_SET.add(libName);
	}

	/**
	 * Deletes what earlier processes left behind in the given directory:
	 * extraction directories no process holds any more, and the library
	 * files earlier versions of this loader extracted. Anything that cannot
	 * be deleted is left, since on Windows that means a process still has it
	 * loaded.
	 *
	 * @param root The directory to sweep, which is the temporary directory
	 *             outside of tests.
	 * @param now  The current time in milliseconds, which is what the age of
	 *             an entry is measured against.
	 */
	static void sweep(Path root, long now) {
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
			for (Path entry : entries) {
				String name = entry.getFileName().toString();

				try {
					if (name.startsWith(DIRECTORY_PREFIX) && Files.isDirectory(entry)) {
						if (isOldEnough(entry, now) && !isInUse(entry)) {
							deleteDirectory(entry);
						}
					}
					else if (LEGACY_FILE.matcher(name).matches()) {
						if (isOldEnough(entry, now)) {
							Files.deleteIfExists(entry);
						}
					}
				}
				catch (IOException | RuntimeException e) {
					// In use, or already gone: either way not ours to delete.
				}
			}
		}
		catch (IOException e) {
			// Nothing to sweep.
		}
	}

	/**
	 * Whether a process still holds the given extraction directory. A
	 * directory without a lock file is not held: it belongs to a process that
	 * ended before it could create one, since one that is still starting is
	 * too young to be asked.
	 *
	 * @param directory The extraction directory.
	 *
	 * @return True if a process, this one included, holds its lock.
	 *
	 * @throws IOException if the lock file cannot be opened.
	 */
	static boolean isInUse(Path directory) throws IOException {
		Path lockFile = directory.resolve(LOCK_FILE_NAME);

		if (!Files.exists(lockFile)) {
			return false;
		}

		try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
			FileLock lock = channel.tryLock();

			if (lock == null) {
				// Another process holds it.
				return true;
			}

			lock.release();

			return false;
		}
		catch (OverlappingFileLockException e) {
			// This process holds it.
			return true;
		}
	}

	/**
	 * Returns the directory of this process, and creates it, locked, the
	 * first time.
	 *
	 * @param tempRoot Where to create it.
	 *
	 * @return The directory of this process.
	 *
	 * @throws IOException if it cannot be created or locked.
	 */
	static synchronized Path processDirectory(Path tempRoot) throws IOException {
		if (processDirectory == null) {
			Path directory = Files.createTempDirectory(tempRoot, DIRECTORY_PREFIX);
			FileChannel channel = FileChannel.open(directory.resolve(LOCK_FILE_NAME),
					StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

			try {
				processLock = channel.lock();
			}
			catch (IOException | RuntimeException e) {
				channel.close();

				throw e;
			}

			processDirectory = directory;
		}

		return processDirectory;
	}

	/**
	 * Extracts one library from the JAR into the given directory, keeping its
	 * file name, and loads it. A library already extracted there under that
	 * name is loaded as it is: on Windows it is locked by having been loaded,
	 * and loading it again does nothing.
	 *
	 * @param directory The directory to extract into.
	 * @param fileName  The resource name of the library, which is also the
	 *                  name it is written under.
	 *
	 * @throws Exception if the library is not in the JAR or could not be
	 *                   loaded.
	 */
	private static void loadFromDirectory(Path directory, String fileName)
			throws Exception {
		Path libPath = directory.resolve(fileName);

		if (!Files.exists(libPath)) {
			try (InputStream is = NativeLoader.class.getClassLoader()
					.getResourceAsStream(fileName)) {
				if (is == null) {
					throw new UnsatisfiedLinkError(
							"Native library '" + fileName + "' is not on the classpath");
				}

				Files.copy(is, libPath);
			}
		}

		try {
			System.load(libPath.toAbsolutePath().toString());
		}
		catch (Throwable e) {
			Files.deleteIfExists(libPath);

			throw e;
		}
	}

	private static boolean isOldEnough(Path path, long now) throws IOException {
		return now - Files.getLastModifiedTime(path).toMillis() >= SWEEP_MIN_AGE_MS;
	}

	/**
	 * Deletes the given directory and everything in it, as far as it can.
	 */
	private static void deleteDirectory(Path directory) {
		List<Path> paths;

		try (Stream<Path> walk = Files.walk(directory)) {
			paths = walk.collect(Collectors.toList());
		}
		catch (IOException e) {
			return;
		}

		// Deepest first, so that every directory is empty by the time it is
		// reached.
		Collections.reverse(paths);

		for (Path path : paths) {
			try {
				Files.deleteIfExists(path);
			}
			catch (IOException e) {
				// Still loaded somewhere; a later sweep gets it.
			}
		}
	}

	private static boolean canDeleteLoaded() {
		return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
	}

	private static String getOSFamily() {
		String osName = System.getProperty("os.name").toLowerCase();

		if (osName.startsWith("mac os")) {
			return "macos";
		}
		if (osName.startsWith("linux")) {
			return "linux";
		}
		if (osName.startsWith("windows")) {
			return "windows";
		}

		throw new RuntimeException("Unsupported operating system: " + osName);
	}

	private static String getOSArch() {
		String osArch = System.getProperty("os.arch").toLowerCase();

		switch (osArch) {
			case "x86_64":
			case "x86-64":
			case "amd64":
				return "x86_64";
			case "aarch32":
			case "arm":
				return "aarch32";
			case "aarch64":
			case "arm64":
				return "aarch64";
		}

		throw new RuntimeException("Unsupported CPU architecture: " + osArch);
	}

}
