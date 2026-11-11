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

package dev.onvoid.webrtc.client;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * TLS for tests: a certificate for 127.0.0.1, made once by the JDK's keytool,
 * the server side that presents it and a client side that trusts only it.
 */
final class TestTls {

	private static final char[] PASSWORD = "changeit".toCharArray();

	private static TestTls instance;

	/** For a {@link TestHttpEndpoint} that serves https. */
	final SSLContext server;

	/** For an {@code HttpClient} that trusts that endpoint. */
	final SSLContext client;


	private TestTls(SSLContext server, SSLContext client) {
		this.server = server;
		this.client = client;
	}

	static synchronized TestTls get() throws Exception {
		if (instance == null) {
			instance = create();
		}

		return instance;
	}

	private static TestTls create() throws Exception {
		Path dir = Files.createDirectories(Paths.get("target", "test-tls"));
		Path file = dir.resolve("endpoint.p12");
		Path log = dir.resolve("keytool.log");
		Files.deleteIfExists(file);

		String keytool = Paths.get(System.getProperty("java.home"), "bin",
				System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool").toString();
		Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "endpoint", "-keyalg", "RSA",
				"-keysize", "2048", "-dname", "CN=127.0.0.1", "-ext", "SAN=ip:127.0.0.1", "-validity", "2",
				"-storetype", "PKCS12", "-keystore", file.toString(), "-storepass", new String(PASSWORD),
				"-keypass", new String(PASSWORD))
				.redirectErrorStream(true)
				.redirectOutput(log.toFile())
				.start();

		if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
			throw new IllegalStateException("keytool failed: " + new String(Files.readAllBytes(log), "UTF-8"));
		}

		KeyStore keys = KeyStore.getInstance("PKCS12");

		try (InputStream in = Files.newInputStream(file)) {
			keys.load(in, PASSWORD);
		}

		KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		keyManagers.init(keys, PASSWORD);

		SSLContext server = SSLContext.getInstance("TLS");
		server.init(keyManagers.getKeyManagers(), null, null);

		KeyStore trusted = KeyStore.getInstance("PKCS12");
		trusted.load(null, null);
		trusted.setCertificateEntry("endpoint", keys.getCertificate("endpoint"));

		TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		trustManagers.init(trusted);

		SSLContext client = SSLContext.getInstance("TLS");
		client.init(null, trustManagers.getTrustManagers(), null);

		return new TestTls(server, client);
	}
}
