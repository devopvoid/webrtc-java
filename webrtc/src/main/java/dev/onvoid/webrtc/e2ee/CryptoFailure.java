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

/**
 * Why a frame was dropped. Every failure drops the frame: nothing is sent in
 * the clear, and nothing that did not authenticate reaches a decoder.
 *
 * @author Alex Andres
 *
 * @see CryptoListener
 */
public enum CryptoFailure {

	/**
	 * A frame was to be sent, but the {@link KeyProvider} has no encryption
	 * key yet, or no longer.
	 */
	MISSING_KEY,

	/**
	 * A received frame names a participant or key generation the {@link
	 * KeyProvider} has no decryption key for: the key has not arrived yet,
	 * was removed, or was retired after its successor took over. Decryption
	 * resumes with the first frame whose key is there.
	 */
	UNKNOWN_KEY,

	/**
	 * A received frame did not authenticate: it was changed on the way, or
	 * encrypted with another key under the same key ID.
	 */
	AUTHENTICATION,

	/**
	 * A received frame repeats the counter of one already decrypted, or is
	 * older than the replay window reaches back.
	 */
	REPLAY,

	/**
	 * A received frame is no SFrame ciphertext, e.g. because the remote side
	 * sends without encryption, or a frame to be sent is too short for its
	 * codec's header.
	 */
	MALFORMED,

	/**
	 * The frame's codec is one the frame format does not cover, H.264 or
	 * H.265; use VP8, VP9, AV1 or any audio codec instead.
	 */
	UNSUPPORTED_CODEC,

	/**
	 * The {@link KeyProvider} gave out every one of its 2<sup>16</sup>
	 * sender contexts, and has to be replaced.
	 */
	CONTEXTS_EXHAUSTED

}
