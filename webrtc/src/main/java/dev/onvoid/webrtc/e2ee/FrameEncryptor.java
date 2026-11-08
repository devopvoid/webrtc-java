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
 * Encrypts the frames a sender sends: the codec's clear bytes, if any, then
 * the SFrame header, the ciphertext and the tag.
 */
final class FrameEncryptor extends FrameCryptor {

	FrameEncryptor(KeyProvider keys, Runnable keyFrameRequest) {
		super(keys, keyFrameRequest);
	}

	@Override
	int process(long ssrc, String mimeType, boolean video, boolean keyFrame, byte[] in, int size, byte[] out) {
		Stream stream = stream(ssrc);
		long participant = keys.getParticipant();

		if (stream.context < 0) {
			stream.context = keys.allocateContext();

			if (stream.context < 0) {
				return fail(stream, CryptoFailure.CONTEXTS_EXHAUSTED, participant, -1, mimeType);
			}
		}

		KeyProvider.LocalKey key = keys.encryptionKey();

		if (key == null) {
			return fail(stream, CryptoFailure.MISSING_KEY, participant, -1, mimeType);
		}

		Codec codec = Codec.of(mimeType);

		if (codec == null) {
			return fail(stream, CryptoFailure.UNSUPPORTED_CODEC, participant, key.generation, mimeType);
		}

		int clear = codec.clearBytes(keyFrame);

		if (size < clear) {
			return fail(stream, CryptoFailure.MALFORMED, participant, key.generation, mimeType);
		}

		// The bytes before the SFrame header, which are its metadata too: the
		// codec's clear bytes, or the OBU header an AV1 frame is wrapped in.
		byte[] metadata;
		int metadataLength;

		if (codec == Codec.AV1) {
			out[0] = (byte) (keyFrame ? FrameFormat.AV1_KEY_WRAPPER : FrameFormat.AV1_WRAPPER);
			metadata = out;
			metadataLength = 1;
		}
		else {
			System.arraycopy(in, 0, out, 0, clear);
			metadata = in;
			metadataLength = clear;
		}

		SFrame sframe = key.sframe(keys.getCipherSuite(), stream.context, participant);

		// Never reset, not even by a new key, so that no counter repeats
		// under a key ID, whichever key it names.
		long ctr = stream.counter++;

		int length = metadataLength + sframe.encrypt(ctr, metadata, 0, metadataLength,
				in, clear, size - clear, out, metadataLength);

		boolean newKey = stream.generation != -1 && stream.generation != key.generation;
		stream.generation = key.generation;

		keys.encrypted();
		succeed(stream, video, keyFrame, newKey);

		return length;
	}

	@Override
	boolean isOutgoing() {
		return true;
	}

}
