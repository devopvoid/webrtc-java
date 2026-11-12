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

import java.io.File;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Loads a native library, looking for it in this order:
 * <ol>
 * <li>In the directory named by the system property {@value
 * #LIBRARY_DIR_PROPERTY}, if it is set and the library is there. The files are
 * loaded where they are, nothing is extracted. This is for libraries installed
 * with the application, as by a jpackage installer or a distribution
 * package.</li>
 * <li>Inside a JAR on the class path, the native JAR of the platform. A library
 * has to be a file to be loaded, so it is extracted first, into the directory
 * named by {@value #EXTRACT_DIR_PROPERTY}, or the temporary directory if that
 * is not set.</li>
 * <li>Where {@link System#loadLibrary(String)} looks: the libraries of the
 * Java runtime itself, which is where a jlink image keeps those of its
 * modules, then {@code java.library.path}.</li>
 * </ol>
 * If none of them has it, the error names every place that was tried.
 * <p>
 * Where the file system lets a loaded library be deleted, as on Linux and
 * macOS, an extraction directory is gone again as soon as everything in it is
 * loaded. Windows does not: a loaded library stays locked until the process
 * ends, so the process cannot clean up after itself, and deleting on exit
 * never succeeds. There the process keeps one directory, marked as in use by a
 * lock it holds until it ends, and each process sweeps away the directories of
 * processes that have ended before it extracts anything of its own.
 *
 * @author Alex Andres
 */
public class NativeLoader {

	/**
	 * The system property naming a directory that holds the native libraries,
	 * which are then loaded from there instead of being extracted.
	 */
	public static final String LIBRARY_DIR_PROPERTY = "webrtc.java.library.dir";

	/**
	 * The system property naming the directory to extract the native
	 * libraries of a JAR into, instead of the temporary directory. Its file
	 * system has to allow running code from it.
	 */
	public static final String EXTRACT_DIR_PROPERTY = "webrtc.java.extract.dir";

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

	/** The libraries the thread that holds the lock of this class is loading. */
	private static final Set<String> LOADING_LIB_SET = new HashSet<>();

	/** What is to run once a library is loaded, by the name of the library. */
	private static final Map<String, List<Runnable>> WHEN_LOADED = new HashMap<>();

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
	 * time, or not found at all. A directory of {@value #LIBRARY_DIR_PROPERTY}
	 * or of the library path holds them under these names too; one it lacks
	 * is left to the dynamic linker to find.
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
		loadOnce(libName, () -> load(libName + "-" + platform(),
				Arrays.asList(dependencies), Locations.fromSystem(),
				NativeLoader::classPathResource, Linker.SYSTEM));
	}

	/**
	 * Runs the given loader, unless the library is loaded already or is being
	 * loaded by this thread, and then what is to run once it is loaded.
	 * <p>
	 * Loading a library runs its {@code JNI_OnLoad}, which looks up Java
	 * classes and with that initializes them. A class that loads the same
	 * library in its static initializer then calls back in on this thread,
	 * which the lock of this class lets through. That nested call must not
	 * load the library again, which would put a second copy of it into the
	 * process: it returns at once, before the library is loaded, and the outer
	 * call finishes loading it. Code that needs the library at that point
	 * goes through {@link #whenLoaded(String, Runnable)}. A loader that fails
	 * leaves the library not loaded, so that a later call tries anew.
	 *
	 * @param libName The name of the library.
	 * @param loader  Loads the library.
	 */
	static synchronized void loadOnce(String libName, Runnable loader) {
		if (LOADED_LIB_SET.contains(libName) || !LOADING_LIB_SET.add(libName)) {
			return;
		}

		try {
			loader.run();
		}
		finally {
			LOADING_LIB_SET.remove(libName);
		}

		LOADED_LIB_SET.add(libName);

		List<Runnable> actions = WHEN_LOADED.remove(libName);

		if (actions != null) {
			for (Runnable action : actions) {
				action.run();
			}
		}
	}

	/**
	 * Runs the given action now if the library is loaded, or else once it is.
	 * A static initializer that loads a library and then calls one of its
	 * native methods makes that call through this. When the loading of the
	 * library is what runs the initializer, the library is not loaded yet, see
	 * {@link #loadOnce(String, Runnable)}, and the JVM finds none of its
	 * native methods before its {@code JNI_OnLoad} has returned.
	 *
	 * @param libName The name of the library, as given to {@link
	 *                #loadLibrary(String, String...)}.
	 * @param action  What needs the library.
	 */
	static synchronized void whenLoaded(String libName, Runnable action) {
		if (LOADED_LIB_SET.contains(libName)) {
			action.run();
		}
		else {
			WHEN_LOADED.computeIfAbsent(libName, name -> new ArrayList<>()).add(action);
		}
	}

	/**
	 * Loads a library and its dependencies from the first place that has the
	 * library, in the order the class description gives.
	 *
	 * @param baseName     The platform specific name of the library, without
	 *                     prefix or file extension, as {@link
	 *                     System#loadLibrary(String)} takes it.
	 * @param dependencies The file names of the libraries to load first.
	 * @param locations    Where to look.
	 * @param resources    Opens a resource of the class path.
	 * @param linker       Loads what was found.
	 *
	 * @throws UnsatisfiedLinkError if no place has it, or if loading it from
	 *                              the directory of {@value
	 *                              #LIBRARY_DIR_PROPERTY} fails.
	 */
	static void load(String baseName, List<String> dependencies,
			Locations locations, Resources resources, Linker linker) {
		String fileName = System.mapLibraryName(baseName);
		List<String> tried = new ArrayList<>();

		if (locations.libraryDir != null) {
			Path file = locations.libraryDir.resolve(fileName);

			if (Files.isRegularFile(file)) {
				try {
					for (String dependency : dependencies) {
						Path dependencyFile = locations.libraryDir.resolve(dependency);

						if (Files.isRegularFile(dependencyFile)) {
							linker.load(dependencyFile);
						}
					}

					linker.load(file);
				}
				catch (UnsatisfiedLinkError e) {
					// Named by the application, so not passed over for another place.
					throw linkError("Native library " + file + " of " + LIBRARY_DIR_PROPERTY
							+ " could not be loaded: " + e.getMessage(), e);
				}

				return;
			}

			tried.add(LIBRARY_DIR_PROPERTY + " " + locations.libraryDir + ": not there");
		}

		if (exists(resources, fileName)) {
			List<String> fileNames = new ArrayList<>(dependencies);
			fileNames.add(fileName);

			try {
				extractAndLoad(fileNames, locations.extractRoot, resources, linker);

				return;
			}
			catch (IOException | UnsatisfiedLinkError e) {
				tried.add("class path, extracted into " + locations.extractRoot + ": " + e);
			}
		}
		else {
			tried.add("class path: no native JAR for " + platform());
		}

		for (String dependency : dependencies) {
			for (Path directory : locations.searchPath) {
				Path dependencyFile = directory.resolve(dependency);

				if (Files.isRegularFile(dependencyFile)) {
					try {
						linker.load(dependencyFile);
					}
					catch (UnsatisfiedLinkError e) {
						// What the library then misses is reported when it is loaded.
					}

					break;
				}
			}
		}

		try {
			linker.loadLibrary(baseName);

			return;
		}
		catch (UnsatisfiedLinkError e) {
			// Its message names the directories of java.library.path.
			tried.add("System.loadLibrary: " + e.getMessage());
		}

		throw new UnsatisfiedLinkError(failureMessage(fileName, tried,
				"linux".equals(getOSFamily()) && isMusl(Paths.get("/lib"))));
	}

	/**
	 * Describes a library that could not be loaded from anywhere, and how to
	 * tell the loader where it is.
	 *
	 * @param fileName The file name of the library.
	 * @param tried    The places tried, each with why it failed.
	 * @param musl     Whether the system runs on the musl C library.
	 *
	 * @return The message of the error.
	 */
	static String failureMessage(String fileName, List<String> tried, boolean musl) {
		String newLine = System.lineSeparator();
		StringBuilder message = new StringBuilder();

		message.append("Native library ").append(fileName).append(" could not be loaded. Tried:");

		for (String place : tried) {
			message.append(newLine).append("  - ").append(place);
		}

		message.append(newLine)
				.append("Add the native JAR of this platform (classifier ")
				.append(platform())
				.append(") to the class path, name a directory that holds the library with -D")
				.append(LIBRARY_DIR_PROPERTY)
				.append(", or one to extract it into that allows running code with -D")
				.append(EXTRACT_DIR_PROPERTY).append('.');

		if (musl) {
			message.append(newLine)
					.append("This system uses the musl C library, as Alpine Linux does. ")
					.append("The native libraries are built against glibc and do not load on it.");
		}

		return message.toString();
	}

	/**
	 * Whether the system runs on the musl C library, told by its dynamic
	 * linker, which is named {@code ld-musl-<arch>.so.1}.
	 *
	 * @param libDirectory Where the dynamic linker is, {@code /lib} outside of
	 *                     tests.
	 *
	 * @return True if a musl dynamic linker is there.
	 */
	static boolean isMusl(Path libDirectory) {
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(libDirectory, "ld-musl-*")) {
			return entries.iterator().hasNext();
		}
		catch (IOException | RuntimeException e) {
			return false;
		}
	}

	/**
	 * Extracts the given libraries of the class path into a directory under
	 * the given root and loads them, in their order.
	 */
	private static void extractAndLoad(List<String> fileNames, Path root,
			Resources resources, Linker linker) throws IOException {
		Files.createDirectories(root);

		if (!swept) {
			swept = true;

			sweep(root, System.currentTimeMillis());
		}

		if (canDeleteLoaded()) {
			// A loaded library stays mapped after its file is gone, so the
			// directory is only needed for as long as the loading takes.
			Path directory = Files.createTempDirectory(root, DIRECTORY_PREFIX);

			try {
				for (String fileName : fileNames) {
					loadFromDirectory(directory, fileName, resources, linker);
				}
			}
			finally {
				deleteDirectory(directory);
			}
		}
		else {
			Path directory = processDirectory(root);

			for (String fileName : fileNames) {
				loadFromDirectory(directory, fileName, resources, linker);
			}
		}
	}

	private static boolean exists(Resources resources, String name) {
		try (InputStream is = resources.open(name)) {
			return is != null;
		}
		catch (IOException e) {
			return false;
		}
	}

	private static InputStream classPathResource(String name) {
		return NativeLoader.class.getClassLoader().getResourceAsStream(name);
	}

	private static UnsatisfiedLinkError linkError(String message, Throwable cause) {
		UnsatisfiedLinkError error = new UnsatisfiedLinkError(message);
		error.initCause(cause);

		return error;
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
	 * @throws IOException          if it could not be extracted.
	 * @throws UnsatisfiedLinkError if it is not in the JAR or could not be
	 *                              loaded.
	 */
	private static void loadFromDirectory(Path directory, String fileName,
			Resources resources, Linker linker) throws IOException {
		Path libPath = directory.resolve(fileName);

		if (!Files.exists(libPath)) {
			try (InputStream is = resources.open(fileName)) {
				if (is == null) {
					throw new UnsatisfiedLinkError(
							"Native library '" + fileName + "' is not on the classpath");
				}

				Files.copy(is, libPath);
			}
		}

		try {
			linker.load(libPath);
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

	/**
	 * The platform part of library names and of the classifier of the native
	 * JAR, for example {@code linux-x86_64}.
	 */
	static String platform() {
		return getOSFamily() + "-" + getOSArch();
	}

	private static String getOSFamily() {
		String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);

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
		String osArch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);

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



	/** Opens a resource of the class path, or returns null if there is none. */
	interface Resources {

		InputStream open(String name) throws IOException;

	}



	/** Hands a library to the JVM; replaced in tests. */
	interface Linker {

		Linker SYSTEM = new Linker() {

			@Override
			public void load(Path file) {
				System.load(file.toAbsolutePath().toString());
			}

			@Override
			public void loadLibrary(String name) {
				System.loadLibrary(name);
			}
		};

		void load(Path file);

		void loadLibrary(String name);

	}



	/** Where a library is looked for, read from the system properties. */
	static final class Locations {

		/** The directory of {@link #LIBRARY_DIR_PROPERTY}, or null if not set. */
		final Path libraryDir;

		/** Where a library of the class path is extracted into. */
		final Path extractRoot;

		/**
		 * The directories {@link System#loadLibrary(String)} looks in, in its
		 * order: those of the Java runtime, then {@code java.library.path}.
		 */
		final List<Path> searchPath;


		Locations(Path libraryDir, Path extractRoot, List<Path> searchPath) {
			this.libraryDir = libraryDir;
			this.extractRoot = extractRoot;
			this.searchPath = searchPath;
		}

		static Locations fromSystem() {
			Path extractRoot = directory(EXTRACT_DIR_PROPERTY);

			if (extractRoot == null) {
				extractRoot = Paths.get(System.getProperty("java.io.tmpdir"));
			}

			List<Path> searchPath = new ArrayList<>();
			searchPath.addAll(paths(System.getProperty("sun.boot.library.path")));
			searchPath.addAll(paths(System.getProperty("java.library.path")));

			return new Locations(directory(LIBRARY_DIR_PROPERTY), extractRoot, searchPath);
		}

		/** The absolute directory a property names, or null if it is not set. */
		private static Path directory(String property) {
			String value = System.getProperty(property);

			if (value == null || value.trim().isEmpty()) {
				return null;
			}

			return Paths.get(value.trim()).toAbsolutePath();
		}

		private static List<Path> paths(String value) {
			List<Path> paths = new ArrayList<>();

			if (value == null) {
				return paths;
			}

			for (String entry : value.split(File.pathSeparator)) {
				if (!entry.isEmpty()) {
					try {
						paths.add(Paths.get(entry).toAbsolutePath());
					}
					catch (RuntimeException e) {
						// Not a path on this system, which System.loadLibrary skips too.
					}
				}
			}

			return paths;
		}
	}

}
