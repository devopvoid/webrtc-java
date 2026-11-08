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

import dev.onvoid.webrtc.e2ee.FrameFormat.Codec;

/**
 * Decrypts the frames a receiver receives, and drops every one that does not
 * authenticate before it reaches the decoder.
 */
final class FrameDecryptor extends FrameCryptor {

	FrameDecryptor(KeyProvider keys, Runnable keyFrameRequest) {
		super(keys, keyFrameRequest);
	}

	@Override
	int process(long ssrc, String mimeType, boolean video, boolean keyFrame, byte[] in, int size, byte[] out) {
		Stream stream = stream(ssrc);
		Codec codec = Codec.of(mimeType);

		if (codec == null) {
			return fail(stream, CryptoFailure.UNSUPPORTED_CODEC, -1, -1, mimeType);
		}

		// The bytes before the SFrame header, the clear ones kept in the
		// frame, and the metadata the frame is authenticated with.
		int clear;
		int headerOffset;
		byte[] metadata;
		int metadataLength;

		if (codec == Codec.AV1) {
			clear = 0;
			headerOffset = FrameFormat.av1PayloadOffset(in, size);

			if (headerOffset < 0) {
				return fail(stream, CryptoFailure.MALFORMED, -1, -1, mimeType);
			}

			metadata = new byte[] { (byte) FrameFormat.av1Wrapper(in[0]) };
			metadataLength = 1;
		}
		else {
			// A VP8 receiver knows a key frame from the clear header itself.
			clear = codec.clearBytes(keyFrame);
			headerOffset = clear;
			metadata = in;
			metadataLength = clear;
		}

		long[] header = new long[2];
		int headerLength = headerOffset <= size
				? SFrame.parseHeader(in, headerOffset, size - headerOffset, header)
				: -1;

		if (headerLength < 0) {
			return fail(stream, CryptoFailure.MALFORMED, -1, -1, mimeType);
		}

		long kid = header[0];
		long ctr = header[1];
		long participant = FrameFormat.participant(kid);
		int generation = FrameFormat.generation(kid);
		long now = System.nanoTime();

		KeyProvider.RemoteKey key = keys.decryptionKey(participant, generation, now);

		if (key == null) {
			return fail(stream, CryptoFailure.UNKNOWN_KEY, participant, generation, mimeType);
		}

		KeyProvider.Opener opener = key.opener(keys.getCipherSuite(), kid, keys.replayWindow());

		if (opener.window != null && !opener.window.isFresh(ctr)) {
			return fail(stream, CryptoFailure.REPLAY, participant, generation, mimeType);
		}

		int ciphertextOffset = headerOffset + headerLength;

		int length = opener.sframe.decrypt(ctr, in, headerOffset, headerLength, metadata, 0, metadataLength,
				in, ciphertextOffset, size - ciphertextOffset, out, clear);

		if (length < 0) {
			return fail(stream, CryptoFailure.AUTHENTICATION, participant, generation, mimeType);
		}
		if (opener.window != null && !opener.window.accept(ctr)) {
			return fail(stream, CryptoFailure.REPLAY, participant, generation, mimeType);
		}

		System.arraycopy(in, 0, out, 0, clear);

		keys.decryptedWith(participant, key, now);
		succeed(stream, video, keyFrame, false);

		return clear + length;
	}

	@Override
	boolean isOutgoing() {
		return false;
	}

}
