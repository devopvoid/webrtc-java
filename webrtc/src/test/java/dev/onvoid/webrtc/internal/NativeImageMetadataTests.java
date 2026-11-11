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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import dev.onvoid.webrtc.media.audio.AudioTrackSink;

/**
 * Keeps the GraalVM Native Image metadata in step with the native code. A
 * native image knows only the classes and members it was told about: a class
 * the native code looks up without an entry fails there with a
 * NoClassDefFoundError or NoSuchMethodError that the JVM tests never see.
 * <p>
 * The classes of this project that the native code uses are registered with
 * all their members, so only a new class has to be added: those its sources
 * name, those that declare native methods, whose objects it reads and writes
 * the fields of, the few it reaches through the class of an object, and the
 * superclasses of all of them, which declare some of the fields it looks up.
 * The array types of these classes are registered as well: the native code
 * creates arrays of them, which a native image can only do for registered
 * types once it requires exact metadata. JDK classes are registered with the
 * members the native code calls.
 * <p>
 * The Java code asks a few questions by reflection, which a native image
 * answers only for registered classes: whether an audio sink implements the
 * method with packets, and whether a tracked object has a close method. The
 * classes of this project are registered for these queries.
 */
class NativeImageMetadataTests {

	private static final String METADATA_DIR =
			"META-INF/native-image/dev.onvoid.webrtc/webrtc-java/";

	/** The native sources, relative to this module's directory. */
	private static final Path NATIVE_SOURCES = Paths.get("../webrtc-jni/src/main/cpp");

	/**
	 * Classes that the native code reaches without naming them, through the
	 * class of an object it was given, with what it looks up there.
	 */
	private static final Set<String> LOOKED_UP_BY_OBJECT = Set.of(
			// The field "codecNames", read in AudioCodecFactories.cpp.
			"dev.onvoid.webrtc.media.audio.codec.BuiltinAudioEncoderFactory",
			"dev.onvoid.webrtc.media.audio.codec.BuiltinAudioDecoderFactory");

	/**
	 * Names in the native sources that are no class a running library looks
	 * up, with why.
	 */
	private static final Set<String> NOT_LOOKED_UP = Set.of(
			// JavaDeviceClass in Device.cpp, which nothing uses.
			"dev.onvoid.webrtc.media.audio.Device",
			// The Char wrapper of JavaPrimitive.h, which nothing uses.
			"java.lang.Char");

	/**
	 * Arrays of JDK classes and arrays of arrays that the native code creates,
	 * which no generic rule covers.
	 */
	private static final Set<String> ARRAYS = Set.of(
			// The values of statistics, in RTCStats.cpp.
			"java.lang.Boolean[]", "java.lang.Double[]", "java.lang.Integer[]", "java.lang.Long[]",
			"java.lang.String[]", "java.math.BigInteger[]",
			// The layers of a bitrate allocation, in VideoEncoderWrapper.cpp.
			"int[][]",
			// Supported and hardware codecs, in JNI_Hardware*Factory.cpp.
			"dev.onvoid.webrtc.media.video.codec.VideoCodecInfo[][]");

	/** Members registered for reflection that only newer JDKs have, with the version. */
	private static final Map<String, Integer> NEWER_MEMBERS = Map.of(
			// Called by reflection, as the library runs on Java 11.
			"java.net.http.HttpClient#shutdown", 21);

	private static final Pattern DEFINE =
			Pattern.compile("^#define\\s+(PKG\\w*|\\w+_SIG)\\s+(\"[^\"]*\")\\s*$", Pattern.MULTILINE);

	private static final Pattern ADJACENT_LITERALS = Pattern.compile("\"([^\"\\n]*)\"\\s*\"([^\"\\n]*)\"");

	private static final Pattern CLASS_LITERAL = Pattern.compile("\"((?:java|dev/onvoid)/[\\w/$]+)\"");


	@Test
	void nativeClassesAreRegistered() throws Exception {
		Set<String> registeredJni = names(array("jni-config.json"));
		Set<String> registeredTypes = names(array("reflect-config.json"));
		Set<String> missing = new TreeSet<>();

		for (String className : usedClasses()) {
			if (!registeredJni.contains(className) || !registeredTypes.contains(className)) {
				missing.add(className);
			}
		}

		assertTrue(missing.isEmpty(), "Looked up by the native code, but not in " + METADATA_DIR
				+ " jni-config.json and reflect-config.json: " + missing);
	}

	@Test
	void noStaleProjectClasses() throws Exception {
		Set<String> used = usedClasses();

		Set<String> stale = names(array("jni-config.json")).stream()
				.filter(name -> name.startsWith("dev.onvoid."))
				.filter(name -> !used.contains(name))
				.collect(Collectors.toCollection(TreeSet::new));

		assertTrue(stale.isEmpty(), "Registered, but not used by the native code: " + stale);
	}

	@Test
	void arraysAreRegistered() throws Exception {
		Set<String> registeredTypes = names(array("reflect-config.json"));
		Set<String> missing = new TreeSet<>(ARRAYS);

		for (String name : usedClasses()) {
			if (name.startsWith("dev.onvoid.")) {
				missing.add(name + "[]");
			}
		}

		missing.removeAll(registeredTypes);

		assertTrue(missing.isEmpty(), "Not in reflect-config.json: " + missing);
	}

	@Test
	void methodsCanBeQueried() throws Exception {
		Set<String> queried = new TreeSet<>();

		for (Map<String, Object> entry : array("reflect-config.json")) {
			if (Boolean.TRUE.equals(entry.get("queryAllPublicMethods"))) {
				queried.add((String) entry.get("name"));
			}
		}

		Set<String> missing = new TreeSet<>();

		for (String name : usedClasses()) {
			if (name.startsWith("dev.onvoid.")) {
				missing.add(name);
			}
		}

		// AudioTrack asks the sinks of this module, those of the client API
		// among them, whether they take the packets.
		for (Class<?> cls : moduleClasses()) {
			if (AudioTrackSink.class.isAssignableFrom(cls) && !cls.isInterface()) {
				missing.add(cls.getName());
			}
		}

		missing.removeAll(queried);

		assertTrue(missing.isEmpty(), "Not registered with queryAllPublicMethods: " + missing);
	}

	@Test
	void projectClassesHaveAllMembers() throws IOException {
		for (Map<String, Object> entry : array("jni-config.json")) {
			String name = (String) entry.get("name");

			if (name.startsWith("dev.onvoid.")) {
				for (String key : List.of("allDeclaredConstructors", "allDeclaredMethods", "allDeclaredFields")) {
					assertEquals(Boolean.TRUE, entry.get(key), name + " " + key);
				}
			}
		}
	}

	@Test
	void registeredMembersExist() throws Exception {
		ClassLoader loader = NativeImageMetadataTests.class.getClassLoader();

		for (String file : List.of("jni-config.json", "reflect-config.json")) {
			for (Map<String, Object> entry : array(file)) {
				String name = (String) entry.get("name");
				Class<?> cls = type(name, loader);

				for (Object method : list(entry.get("methods"))) {
					@SuppressWarnings("unchecked")
					Map<String, Object> member = (Map<String, Object>) method;
					String methodName = (String) member.get("name");
					List<Object> types = list(member.get("parameterTypes"));
					Class<?>[] parameterTypes = new Class<?>[types.size()];

					for (int i = 0; i < parameterTypes.length; i++) {
						parameterTypes[i] = type((String) types.get(i), loader);
					}

					Integer since = NEWER_MEMBERS.get(name + "#" + methodName);

					if (since != null && Runtime.version().feature() < since) {
						continue;
					}

					if (methodName.equals("<init>")) {
						cls.getDeclaredConstructor(parameterTypes);
					}
					else {
						// Declared there: a native image looks up a member in
						// the class that declares it.
						cls.getDeclaredMethod(methodName, parameterTypes);
					}
				}
			}
		}
	}

	/**
	 * The library is registered twice: as a pattern for GraalVM 21 and 22,
	 * and by name in reachability-metadata.json for the versions after, which
	 * then answer the question for a library the image does not carry instead
	 * of failing with a MissingResourceRegistrationError, once they require
	 * exact metadata. The loader then goes on to look next to the executable.
	 */
	@Test
	void resourcesMatchEveryPlatform() throws IOException {
		Set<String> libraries = new TreeSet<>();

		for (String arch : List.of("x86_64", "aarch64")) {
			libraries.add("libwebrtc-java-linux-" + arch + ".so");
			libraries.add("libwebrtc-java-macos-" + arch + ".dylib");
			libraries.add("webrtc-java-windows-" + arch + ".dll");
		}
		libraries.add("libwebrtc-java-linux-aarch32.so");

		// What the loader looks for on this platform.
		assertTrue(libraries.contains(System.mapLibraryName("webrtc-java-" + NativeLoader.platform())));

		Pattern pattern = resourcePattern();

		for (String library : libraries) {
			assertTrue(pattern.matcher(library).matches(), library);
		}

		// The media module registers its own library.
		assertFalse(pattern.matcher("libwebrtc-java-media-linux-x86_64.so").matches());
		assertFalse(pattern.matcher("META-INF/LICENSE").matches());

		Set<String> globs = new TreeSet<>();

		for (Object resource : list(object("reachability-metadata.json").get("resources"))) {
			@SuppressWarnings("unchecked")
			Map<String, Object> entry = (Map<String, Object>) resource;

			globs.add((String) entry.get("glob"));
		}

		assertEquals(libraries, globs);
	}

	/**
	 * Every class the native code may look up, in the binary form that the
	 * metadata uses.
	 */
	private static Set<String> usedClasses() throws Exception {
		Set<String> classes = classesOfNativeSources();
		classes.addAll(classesWithNativeMethods());
		classes.addAll(LOOKED_UP_BY_OBJECT);

		ClassLoader loader = NativeImageMetadataTests.class.getClassLoader();

		for (String name : new ArrayList<>(classes)) {
			Class<?> superclass = Class.forName(name, false, loader).getSuperclass();

			for (; superclass != null; superclass = superclass.getSuperclass()) {
				if (superclass.getName().startsWith("dev.onvoid.")) {
					classes.add(superclass.getName());
				}
			}
		}

		return classes;
	}

	/**
	 * The classes of this module that declare a native method. Their native
	 * code finds the fields of the object it was called on, the handles to
	 * native objects among them, through the class of that object.
	 */
	private static Set<String> classesWithNativeMethods() throws Exception {
		Set<String> names = new TreeSet<>();

		for (Class<?> cls : moduleClasses()) {
			for (Method method : cls.getDeclaredMethods()) {
				if (Modifier.isNative(method.getModifiers())) {
					names.add(cls.getName());
					break;
				}
			}
		}

		assertFalse(names.isEmpty(), "No class with native methods");

		return names;
	}

	/**
	 * The classes of this module, without initializing them.
	 */
	private static List<Class<?>> moduleClasses() throws Exception {
		Path classes = Paths.get(NativeObject.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		ClassLoader loader = NativeImageMetadataTests.class.getClassLoader();
		List<Class<?>> result = new ArrayList<>();
		List<Path> files;

		try (Stream<Path> walk = Files.walk(classes)) {
			files = walk.filter(path -> path.toString().endsWith(".class"))
					.filter(path -> !path.getFileName().toString().equals("module-info.class"))
					.collect(Collectors.toList());
		}

		for (Path file : files) {
			String path = classes.relativize(file).toString();
			String name = path.substring(0, path.length() - ".class".length())
					.replace(file.getFileSystem().getSeparator(), ".");

			result.add(Class.forName(name, false, loader));
		}

		return result;
	}

	/**
	 * The names of the classes the native sources look up, in the binary
	 * form that the metadata uses.
	 */
	private static Set<String> classesOfNativeSources() throws IOException {
		if (!Files.isDirectory(NATIVE_SOURCES)) {
			fail("The native sources are not at " + NATIVE_SOURCES.toAbsolutePath());
		}

		List<Path> sources;

		try (Stream<Path> walk = Stream.of("src", "include", "dependencies/jni-voithos")
				.map(NATIVE_SOURCES::resolve)
				.flatMap(NativeImageMetadataTests::walk)) {
			sources = walk.filter(path -> path.toString().endsWith(".cpp") || path.toString().endsWith(".h"))
					.collect(Collectors.toList());
		}

		Map<String, String> macros = new LinkedHashMap<>();

		for (Path source : sources) {
			Matcher matcher = DEFINE.matcher(read(source));

			while (matcher.find()) {
				macros.put(matcher.group(1), matcher.group(2));
			}
		}

		Pattern macro = Pattern.compile("\\b(" + String.join("|", macros.keySet()) + ")\\b");
		Set<String> classes = new TreeSet<>();

		for (Path source : sources) {
			String text = DEFINE.matcher(read(source)).replaceAll("");
			text = macro.matcher(text).replaceAll(match -> Matcher.quoteReplacement(" " + macros.get(match.group(1)) + " "));

			String joined;

			do {
				joined = text;
				text = ADJACENT_LITERALS.matcher(joined).replaceAll("\"$1$2\"");
			}
			while (!text.equals(joined));

			Matcher matcher = CLASS_LITERAL.matcher(text);

			while (matcher.find()) {
				String name = matcher.group(1);

				// The package prefixes alone, as in a #define.
				if (!name.endsWith("/")) {
					classes.add(name.replace('/', '.'));
				}
			}
		}

		classes.removeAll(NOT_LOOKED_UP);

		return classes;
	}

	private static Pattern resourcePattern() throws IOException {
		@SuppressWarnings("unchecked")
		Map<String, Object> resources = (Map<String, Object>) object("resource-config.json").get("resources");
		List<Object> includes = list(resources.get("includes"));

		assertEquals(1, includes.size());

		@SuppressWarnings("unchecked")
		Map<String, Object> include = (Map<String, Object>) includes.get(0);

		return Pattern.compile((String) include.get("pattern"));
	}

	private static Class<?> type(String name, ClassLoader loader) throws ClassNotFoundException {
		if (name.endsWith("[]")) {
			Class<?> component = type(name.substring(0, name.length() - 2), loader);

			return java.lang.reflect.Array.newInstance(component, 0).getClass();
		}

		switch (name) {
			case "boolean": return boolean.class;
			case "byte": return byte.class;
			case "short": return short.class;
			case "int": return int.class;
			case "long": return long.class;
			case "float": return float.class;
			case "double": return double.class;
			default: return Class.forName(name, false, loader);
		}
	}

	private static Set<String> names(List<Map<String, Object>> entries) {
		Set<String> names = new TreeSet<>();

		for (Map<String, Object> entry : entries) {
			assertTrue(names.add((String) entry.get("name")), "Registered twice: " + entry.get("name"));
		}

		return names;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> array(String file) throws IOException {
		return (List<Map<String, Object>>) parse(file);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> object(String file) throws IOException {
		return (Map<String, Object>) parse(file);
	}

	@SuppressWarnings("unchecked")
	private static List<Object> list(Object value) {
		return value == null ? List.of() : (List<Object>) value;
	}

	private static Object parse(String file) throws IOException {
		try (InputStream in = NativeImageMetadataTests.class.getClassLoader()
				.getResourceAsStream(METADATA_DIR + file)) {
			if (in == null) {
				fail("Not on the class path: " + METADATA_DIR + file);
			}

			return new JsonReader(new String(in.readAllBytes(), StandardCharsets.UTF_8)).read();
		}
	}

	private static String read(Path path) {
		try {
			return Files.readString(path, StandardCharsets.ISO_8859_1);
		}
		catch (IOException e) {
			throw new RuntimeException(e);
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



	/**
	 * Reads the subset of JSON that the metadata uses: objects, arrays,
	 * strings without escapes other than a backslash, and booleans.
	 */
	private static final class JsonReader {

		private final String text;

		private int position;


		JsonReader(String text) {
			this.text = text;
		}

		Object read() {
			Object value = value();

			skipWhitespace();

			if (position != text.length()) {
				throw error("trailing content");
			}

			return value;
		}

		private Object value() {
			skipWhitespace();

			char c = peek();

			if (c == '{') {
				Map<String, Object> object = new LinkedHashMap<>();
				position++;

				if (!consume('}')) {
					do {
						skipWhitespace();
						String key = string();
						expect(':');

						if (object.put(key, value()) != null) {
							throw error("duplicate key " + key);
						}
					}
					while (consume(','));

					expect('}');
				}

				return object;
			}
			if (c == '[') {
				List<Object> array = new ArrayList<>();
				position++;

				if (!consume(']')) {
					do {
						array.add(value());
					}
					while (consume(','));

					expect(']');
				}

				return array;
			}
			if (c == '"') {
				return string();
			}
			for (String literal : Arrays.asList("true", "false")) {
				if (text.startsWith(literal, position)) {
					position += literal.length();

					return Boolean.valueOf(literal);
				}
			}

			throw error("unexpected '" + c + "'");
		}

		private String string() {
			if (peek() != '"') {
				throw error("string expected");
			}

			StringBuilder value = new StringBuilder();
			position++;

			while (peek() != '"') {
				char c = text.charAt(position++);

				if (c == '\\') {
					char escaped = text.charAt(position++);

					if (escaped != '\\' && escaped != '"') {
						throw error("unsupported escape \\" + escaped);
					}

					c = escaped;
				}

				value.append(c);
			}

			position++;

			return value.toString();
		}

		private boolean consume(char c) {
			skipWhitespace();

			if (position < text.length() && text.charAt(position) == c) {
				position++;

				return true;
			}

			return false;
		}

		private void expect(char c) {
			if (!consume(c)) {
				throw error("'" + c + "' expected");
			}
		}

		private char peek() {
			if (position >= text.length()) {
				throw error("unexpected end");
			}

			return text.charAt(position);
		}

		private void skipWhitespace() {
			while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
				position++;
			}
		}

		private IllegalArgumentException error(String message) {
			return new IllegalArgumentException(message + " at " + position);
		}
	}

}
