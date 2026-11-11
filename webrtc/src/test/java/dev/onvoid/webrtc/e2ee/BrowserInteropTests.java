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

package dev.onvoid.webrtc.e2ee;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks the browser implementation of the frame format, the JavaScript
 * module the guide publishes ({@code docs/public/e2ee}), against this one: in
 * Node.js, which has the same WebCrypto a browser has. Frames encrypted on
 * either side must decrypt on the other, in every cipher suite and codec
 * layout. Skipped where Node.js is not installed.
 */
class BrowserInteropTests {

	private static final Path MODULE = Paths.get("..", "docs", "public", "e2ee", "webrtc-java-sframe.js");

	private static final long JAVA = 7;
	private static final long JS = 9;

	/** Codec, key frame, of every layout the format defines. */
	private static final String[][] FRAMES = {
			{ "video/VP8", "1" }, { "video/VP8", "0" },
			{ "video/VP9", "1" }, { "video/VP9", "0" },
			{ "video/AV1", "1" }, { "video/AV1", "0" },
			{ "audio/opus", "0" }, { "audio/PCMU", "0" },
	};

	@TempDir
	Path dir;


	@Test
	void browserModuleMatchesJava() throws Exception {
		assumeTrue(Files.isRegularFile(MODULE), "The browser module is not there");
		assumeTrue(nodeAvailable(), "Node.js is not installed");

		Random random = new Random(7);

		for (CipherSuite suite : CipherSuite.values()) {
			KeyProvider keys = KeyProvider.builder(JAVA).cipherSuite(suite).build();
			byte[] javaKey = keys.generateKey();
			byte[] jsKey = keys.generateKey();

			keys.setEncryptionKey(0, javaKey);
			keys.setDecryptionKey(JS, 0, jsKey);

			FrameCryptor encryptor = (FrameCryptor) keys.createEncryptor();
			FrameCryptor decryptor = (FrameCryptor) keys.createDecryptor();

			List<byte[]> plain = new ArrayList<>();
			StringBuilder input = new StringBuilder();
			input.append("suite ").append(suite.getId()).append('\n');
			input.append("javaKey ").append(JAVA).append(' ').append(hex(javaKey)).append('\n');
			input.append("jsKey ").append(JS).append(' ').append(hex(jsKey)).append('\n');

			for (int i = 0; i < FRAMES.length; i++) {
				byte[] frame = new byte[20 + random.nextInt(2000)];
				random.nextBytes(frame);
				plain.add(frame);

				String mimeType = FRAMES[i][0];
				boolean keyFrame = FRAMES[i][1].equals("1");
				byte[] sealed = process(encryptor, 1000 + i, mimeType, keyFrame, frame);

				input.append("frame ").append(mimeType).append(' ').append(FRAMES[i][1]).append(' ')
						.append(1000 + i).append(' ').append(hex(frame)).append(' ').append(hex(sealed)).append('\n');
			}

			List<String> output = runNode(input.toString());

			assertTrue(output.contains("format " + KeyProvider.FORMAT), () -> "The formats differ: " + output);

			assertEquals(CipherSuite.values().length, output.stream().filter(l -> l.startsWith("vector")).count());
			output.stream().filter(l -> l.startsWith("vector"))
					.forEach(l -> assertTrue(l.endsWith(" ok"), "RFC 9605 vector in JavaScript: " + l));

			for (int i = 0; i < FRAMES.length; i++) {
				String label = suite + " " + FRAMES[i][0] + " " + FRAMES[i][1];

				assertTrue(output.contains("decrypt " + i + " ok"), "JavaScript decrypts " + label + ": " + output);

				String sealedLine = find(output, "sealed " + i + " ");
				byte[] fromJs = unhex(sealedLine.substring(("sealed " + i + " ").length()));

				// Another stream than Java's own, of the JavaScript participant.
				byte[] decrypted = process(decryptor, 2000 + i, FRAMES[i][0], FRAMES[i][1].equals("1"), fromJs);

				assertArrayEquals(plain.get(i), decrypted, "Java decrypts " + label);
			}

			assertEquals(0, keys.getStats().getDroppedFrames());
		}
	}

	private List<String> runNode(String input) throws Exception {
		Path frames = dir.resolve("frames.txt");
		Files.write(frames, input.getBytes(StandardCharsets.US_ASCII));

		Path script = resource("sframe-interop.mjs");
		Path vectors = resource("rfc9605-test-vectors.txt");
		Path log = dir.resolve("node.log");

		Process process = new ProcessBuilder("node", script.toString(), MODULE.toAbsolutePath().toString(),
				vectors.toString(), frames.toString())
				.redirectErrorStream(true)
				.redirectOutput(log.toFile())
				.start();

		assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Node.js did not finish");

		List<String> output = Files.readAllLines(log, StandardCharsets.UTF_8);

		assertEquals(0, process.exitValue(), () -> String.join("\n", output));
		assertTrue(output.contains("done"), () -> String.join("\n", output));

		return output;
	}

	private static Path resource(String name) throws Exception {
		return Paths.get(BrowserInteropTests.class.getResource(name).toURI());
	}

	private static boolean nodeAvailable() {
		try {
			Process process = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
			process.getInputStream().readAllBytes();

			return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
		}
		catch (IOException e) {
			return false;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private static String find(List<String> lines, String prefix) {
		return lines.stream().filter(l -> l.startsWith(prefix)).findFirst()
				.orElseThrow(() -> new AssertionError("No line " + prefix + " in " + lines));
	}

	private static byte[] process(FrameCryptor cryptor, long ssrc, String mimeType, boolean keyFrame, byte[] frame) {
		byte[] out = new byte[frame.length + FrameFormat.MAX_OVERHEAD];
		int length = cryptor.process(ssrc, mimeType, mimeType.startsWith("video/"), keyFrame, frame.clone(),
				frame.length, out);

		assertTrue(length >= 0, mimeType);

		return Arrays.copyOf(out, length);
	}

	private static String hex(byte[] bytes) {
		StringBuilder hex = new StringBuilder(bytes.length * 2);

		for (byte b : bytes) {
			hex.append(String.format("%02x", b));
		}

		return hex.toString();
	}

	private static byte[] unhex(String hex) {
		return SFrameTests.hex(hex);
	}

}
