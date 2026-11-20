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

package dev.onvoid.webrtc.livekit;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Access tokens for tests, signed with the key and secret of a server started
 * with --dev unless the system properties livekit.key and livekit.secret name
 * others. Applications get their tokens from their backend instead.
 */
final class TestTokens {

	private TestTokens() {
	}

	static String token(String identity, String room) {
		String key = System.getProperty("livekit.key", "devkey");
		String secret = System.getProperty("livekit.secret", "secret");
		long now = System.currentTimeMillis() / 1000;

		String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
		String claims = "{\"iss\":\"" + key + "\",\"sub\":\"" + identity + "\",\"name\":\"" + identity + "\""
				+ ",\"nbf\":" + (now - 10) + ",\"exp\":" + (now + 600)
				+ ",\"video\":{\"room\":\"" + room + "\",\"roomJoin\":true,\"canPublish\":true"
				+ ",\"canSubscribe\":true,\"canPublishData\":true}}";

		String unsigned = encode(header.getBytes(StandardCharsets.UTF_8)) + "."
				+ encode(claims.getBytes(StandardCharsets.UTF_8));

		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return unsigned + "." + encode(mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static String encode(byte[] data) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
	}

}
