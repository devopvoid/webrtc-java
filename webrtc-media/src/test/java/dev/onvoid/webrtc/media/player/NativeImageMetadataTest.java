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

package dev.onvoid.webrtc.media.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Keeps the GraalVM Native Image metadata of this module in step with its
 * native code and its libraries. A class the native code looks up without an
 * entry, or a library the image does not carry, fails only in a native image.
 * <p>
 * The project classes are registered with all their members: those the
 * native code names, and those that declare native methods, on whose objects
 * the native code calls back through the class of the object. The libraries
 * are registered twice, as a pattern for GraalVM 21 and 22 and by name for
 * the versions after.
 *
 * @author Alex Andres
 */
class NativeImageMetadataTest {

	private static final String METADATA_DIR =
			"META-INF/native-image/dev.onvoid.webrtc/webrtc-java-media/";

	/** The native sources, relative to this module's directory. */
	private static final Path NATIVE_SOURCES = Paths.get("src/main/cpp");

	/** Names in the native sources that a running library does not look up. */
	private static final Set<String> NOT_LOOKED_UP = Set.of(
			// What JNI_AllocationFault.cpp fails to find on purpose, for a test.
			"dev.onvoid.webrtc.media.player.NoSuchClass");

	private static final Pattern CLASS_LITERAL = Pattern.compile("\"((?:java|dev/onvoid)/[\\w/$]+)\"");

	/** An entry of jni-config.json, with its name and what follows up to the next. */
	private static final Pattern ENTRY = Pattern.compile("^  \\{\\s*\"name\": \"([^\"]+)\"(.*?)^  }",
			Pattern.MULTILINE | Pattern.DOTALL);

	private static final Pattern METHOD =
			Pattern.compile("\\{\"name\": \"([^\"]+)\", \"parameterTypes\": \\[([^\\]]*)\\]\\}");

	private static final Pattern GLOB = Pattern.compile("\\{\"glob\": \"([^\"]+)\"\\}");

	private static final Pattern PATTERN = Pattern.compile("\"pattern\": \"((?:[^\"\\\\]|\\\\.)*)\"");


	@Test
	void nativeClassesAreRegistered() throws Exception {
		Set<String> missing = usedClasses();
		missing.removeAll(entries().keySet());

		assertTrue(missing.isEmpty(), "Not in " + METADATA_DIR + "jni-config.json: " + missing);
	}

	@Test
	void noStaleProjectClasses() throws Exception {
		Set<String> used = usedClasses();
		Set<String> stale = entries().keySet().stream()
				.filter(name -> name.startsWith("dev.onvoid."))
				.filter(name -> !used.contains(name))
				.collect(Collectors.toCollection(TreeSet::new));

		assertTrue(stale.isEmpty(), "Registered, but not used by the native code: " + stale);
	}

	@Test
	void registeredMembersExist() throws Exception {
		ClassLoader loader = NativeImageMetadataTest.class.getClassLoader();
		int members = 0;

		for (var entry : entries().entrySet()) {
			Class<?> cls = Class.forName(entry.getKey(), false, loader);
			String body = entry.getValue();

			if (entry.getKey().startsWith("dev.onvoid.")) {
				for (String key : List.of("allDeclaredConstructors", "allDeclaredMethods", "allDeclaredFields")) {
					assertTrue(body.contains("\"" + key + "\": true"), entry.getKey() + " " + key);
				}
			}

			Matcher method = METHOD.matcher(body);

			while (method.find()) {
				List<Class<?>> types = new ArrayList<>();

				for (String type : method.group(2).split(",")) {
					if (!type.isBlank()) {
						types.add(Class.forName(type.trim().replace("\"", ""), false, loader));
					}
				}

				Class<?>[] parameterTypes = types.toArray(new Class<?>[0]);
				members++;

				if (method.group(1).equals("<init>")) {
					cls.getDeclaredConstructor(parameterTypes);
				}
				else {
					cls.getDeclaredMethod(method.group(1), parameterTypes);
				}
			}
		}

		// One for each exception and three for the uncaught exception handler.
		assertEquals(8, members);
	}

	@Test
	void librariesOfEveryPlatformAreRegistered() throws IOException {
		Set<String> libraries = new TreeSet<>();

		for (String os : List.of("Linux", "Mac OS X", "Windows 11")) {
			libraries.addAll(Arrays.asList(FFmpegLibraries.forSystem(os)));
		}
		for (String arch : List.of("x86_64", "aarch64")) {
			libraries.add("libwebrtc-java-media-linux-" + arch + ".so");
			libraries.add("libwebrtc-java-media-macos-" + arch + ".dylib");
			libraries.add("webrtc-java-media-windows-" + arch + ".dll");
		}
		libraries.add("libwebrtc-java-media-linux-aarch32.so");

		Set<String> globs = new TreeSet<>();
		Matcher glob = GLOB.matcher(read("reachability-metadata.json"));

		while (glob.find()) {
			globs.add(glob.group(1));
		}

		assertEquals(libraries, globs);

		Matcher pattern = PATTERN.matcher(read("resource-config.json"));

		assertTrue(pattern.find());

		Pattern included = Pattern.compile(pattern.group(1).replace("\\\\", "\\"));

		for (String library : libraries) {
			assertTrue(included.matcher(library).matches(), library);
		}

		assertFalse(pattern.find(), "More than one pattern");
		// The core module registers its own library.
		assertFalse(included.matcher("libwebrtc-java-linux-x86_64.so").matches());
	}

	/**
	 * Every class the native code may look up, as the metadata names it.
	 */
	private static Set<String> usedClasses() throws Exception {
		Set<String> classes = new TreeSet<>();
		List<Path> sources;

		if (!Files.isDirectory(NATIVE_SOURCES)) {
			fail("The native sources are not at " + NATIVE_SOURCES.toAbsolutePath());
		}

		try (Stream<Path> walk = Stream.of("src", "include").map(NATIVE_SOURCES::resolve).flatMap(NativeImageMetadataTest::walk)) {
			sources = walk.filter(path -> path.toString().endsWith(".cpp") || path.toString().endsWith(".h"))
					.collect(Collectors.toList());
		}

		for (Path source : sources) {
			Matcher matcher = CLASS_LITERAL.matcher(Files.readString(source, StandardCharsets.ISO_8859_1));

			while (matcher.find()) {
				classes.add(matcher.group(1).replace('/', '.'));
			}
		}

		classes.removeAll(NOT_LOOKED_UP);

		// The callbacks of MediaPlayer and MediaRecorder are looked up on the
		// class of the object, and both declare native methods.
		Path classesDir = Paths.get(FFmpegLibraries.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		ClassLoader loader = NativeImageMetadataTest.class.getClassLoader();
		List<Path> files;

		try (Stream<Path> walk = Files.walk(classesDir)) {
			files = walk.filter(path -> path.toString().endsWith(".class"))
					.filter(path -> !path.getFileName().toString().equals("module-info.class"))
					.collect(Collectors.toList());
		}

		for (Path file : files) {
			String path = classesDir.relativize(file).toString();
			String name = path.substring(0, path.length() - ".class".length())
					.replace(file.getFileSystem().getSeparator(), ".");

			for (Method method : Class.forName(name, false, loader).getDeclaredMethods()) {
				if (Modifier.isNative(method.getModifiers())) {
					classes.add(name);
					break;
				}
			}
		}

		assertTrue(classes.contains(MediaPlayer.class.getName()));

		return classes;
	}

	/** The entries of jni-config.json by class name. */
	private static Map<String, String> entries() throws IOException {
		Map<String, String> entries = new TreeMap<>();
		Matcher entry = ENTRY.matcher(read("jni-config.json"));

		while (entry.find()) {
			assertTrue(entries.put(entry.group(1), entry.group(2)) == null, "Registered twice: " + entry.group(1));
		}

		assertFalse(entries.isEmpty());

		return entries;
	}

	private static String read(String file) throws IOException {
		try (InputStream in = NativeImageMetadataTest.class.getClassLoader()
				.getResourceAsStream(METADATA_DIR + file)) {
			if (in == null) {
				fail("Not on the class path: " + METADATA_DIR + file);
			}

			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static Stream<Path> walk(Path directory) {
		try {
			return Files.walk(directory);
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

}
