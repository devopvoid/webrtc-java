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

import java.util.EnumMap;
import java.util.Map;

/**
 * The frames the encryptors and decryptors of a {@link KeyProvider} handled
 * since it was created, as of the moment it was asked.
 *
 * @author Alex Andres
 */
public final class CryptoStats {

	private final long encrypted;

	private final long decrypted;

	private final Map<CryptoFailure, Long> dropped;


	CryptoStats(long encrypted, long decrypted, Map<CryptoFailure, Long> dropped) {
		this.encrypted = encrypted;
		this.decrypted = decrypted;
		this.dropped = new EnumMap<>(dropped);
	}

	/**
	 * @return The frames encrypted and sent on.
	 */
	public long getEncryptedFrames() {
		return encrypted;
	}

	/**
	 * @return The frames decrypted and passed to the decoder.
	 */
	public long getDecryptedFrames() {
		return decrypted;
	}

	/**
	 * @param failure The reason.
	 *
	 * @return The frames dropped for the reason.
	 */
	public long getDroppedFrames(CryptoFailure failure) {
		return dropped.getOrDefault(failure, 0L);
	}

	/**
	 * @return The frames dropped for any reason.
	 */
	public long getDroppedFrames() {
		return dropped.values().stream().mapToLong(Long::longValue).sum();
	}

	@Override
	public String toString() {
		return String.format("CryptoStats [encrypted=%d, decrypted=%d, dropped=%s]",
				encrypted, decrypted, dropped);
	}

}
