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

		String libFileName = System.mapLibraryName(libName);
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

}
