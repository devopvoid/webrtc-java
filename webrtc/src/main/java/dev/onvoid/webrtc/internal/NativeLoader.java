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
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads a native library from inside a JAR.
 *
 * @author Alex Andres
 */
public class NativeLoader {

	private static final Set<String> LOADED_LIB_SET = ConcurrentHashMap.newKeySet();


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
		if (LOADED_LIB_SET.contains(libName)) {
			return;
		}

		String osFamily = getOSFamily();
		String osArch = getOSArch();
		String libFileName = System.mapLibraryName(libName + "-" + osFamily + "-" + osArch);
		String tempName = removeExtension(libFileName);
		String ext = getExtension(libFileName);

		Path tempPath = Files.createTempFile(tempName, ext);
		File tempFile = tempPath.toFile();

		try (InputStream is = NativeLoader.class.getClassLoader().getResourceAsStream(libFileName)) {
			Files.copy(is, tempPath, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (Exception e) {
			tempFile.delete();

			throw e;
		}

		try {
			System.load(tempPath.toAbsolutePath().toString());

			LOADED_LIB_SET.add(libName);
		}
		catch (Exception e) {
			tempFile.delete();

			throw e;
		}

		if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
			// Assume POSIX compliant file system, library can be deleted after loading.
			tempFile.delete();
		}
		else {
			tempFile.deleteOnExit();
		}
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
	public static void loadLibrary(final String libName, final String... dependencies)
			throws Exception {
		if (LOADED_LIB_SET.contains(libName)) {
			return;
		}

		String libFileName = System.mapLibraryName(
				libName + "-" + getOSFamily() + "-" + getOSArch());
		Path tempDir = Files.createTempDirectory(libName);

		tempDir.toFile().deleteOnExit();

		for (String dependency : dependencies) {
			loadFromDirectory(tempDir, dependency);
		}

		loadFromDirectory(tempDir, libFileName);

		LOADED_LIB_SET.add(libName);
	}

	/**
	 * Extracts one library from the JAR into the given directory, keeping its
	 * file name, and loads it.
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

		try (InputStream is = NativeLoader.class.getClassLoader()
				.getResourceAsStream(fileName)) {
			if (is == null) {
				throw new UnsatisfiedLinkError(
						"Native library '" + fileName + "' is not on the classpath");
			}

			Files.copy(is, libPath, StandardCopyOption.REPLACE_EXISTING);
		}

		File libFile = libPath.toFile();

		libFile.deleteOnExit();

		try {
			System.load(libPath.toAbsolutePath().toString());
		}
		catch (Throwable e) {
			libFile.delete();

			throw e;
		}
	}

	private static String getExtension(String fileName) {
		final int index = getExtensionIndex(fileName);

		if (index < 0) {
			return "";
		}

		return fileName.substring(index);
	}

	private static String removeExtension(String fileName) {
		final int index = getExtensionIndex(fileName);

		if (index < 0) {
			return fileName;
		}

		return fileName.substring(0, index);
	}

	private static int getExtensionIndex(String fileName) {
		final String file = fileName.replace("\\", "/");
		final int extSeparator = file.lastIndexOf(".");
		final int pathSeparator = file.lastIndexOf("/");

		if (pathSeparator > extSeparator) {
			return -1;
		}

		return extSeparator;
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
