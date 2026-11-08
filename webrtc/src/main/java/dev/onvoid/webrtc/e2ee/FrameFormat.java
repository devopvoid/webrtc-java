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

import java.util.Locale;

/**
 * What the frame format adds to RFC 9605: how a KID is made up, and how the
 * SFrame ciphertext sits in the frame of each codec so that WebRTC's
 * packetizers and depacketizers still find what they read. The guide
 * ({@code docs/guide/advanced/end-to-end-encryption.md}) is the
 * specification; this class and the browser implementation follow it.
 */
final class FrameFormat {

	/** The largest participant ID, 32 bits. */
	static final long MAX_PARTICIPANT = 0xFFFF_FFFFL;

	/** The largest key generation, 8 bits. */
	static final int MAX_GENERATION = 0xFF;

	/** The largest context, 24 bits. */
	static final int MAX_CONTEXT = 0xFF_FFFF;

	/**
	 * The OBU header an AV1 delta frame is wrapped in: OBU_FRAME (type 6), no
	 * extension, no size field. The packetizer sends it as one OBU, and the
	 * depacketizer hands it over with a size field added.
	 */
	static final int AV1_WRAPPER = 6 << 3;

	/**
	 * The OBU header an AV1 key frame is wrapped in: OBU_SEQUENCE_HEADER (type
	 * 1). The packetizer marks a key frame as the start of a new coded video
	 * sequence, which is how the receiver knows it, only if it begins with a
	 * sequence header.
	 */
	static final int AV1_KEY_WRAPPER = 1 << 3;

	/** The most a frame grows by: the AV1 wrapper, a header and a tag. */
	static final int MAX_OVERHEAD = 1 + SFrame.MAX_HEADER_LENGTH + 16;


	private FrameFormat() {
	}

	/**
	 * KID = context (24 bits) | participant (32 bits) | generation (8 bits),
	 * from the most significant bits down, after RFC 9605, Section 5.2.
	 */
	static long kid(int context, long participant, int generation) {
		return (long) context << 40 | participant << 8 | generation;
	}

	static int context(long kid) {
		return (int) (kid >>> 40);
	}

	static long participant(long kid) {
		return kid >>> 8 & MAX_PARTICIPANT;
	}

	static int generation(long kid) {
		return (int) (kid & MAX_GENERATION);
	}

	/**
	 * The codecs the format covers, by how their frames are laid out.
	 */
	enum Codec {

		/** The payload header stays in the clear: 10 bytes on a key frame, 3 otherwise. */
		VP8,

		/** Encrypted whole; its RTP descriptor comes from the encoder, not the bitstream. */
		VP9,

		/** Encrypted whole, inside one OBU the packetizer can carry. */
		AV1,

		/** The TOC byte stays in the clear. */
		OPUS,

		/** Every other audio codec, encrypted whole. */
		AUDIO;

		/**
		 * @return The codec of a MIME type, or null if the format does not
		 * cover it, as for H.264 and H.265, whose packetizers split frames at
		 * start codes that a ciphertext would contain.
		 */
		static Codec of(String mimeType) {
			if (mimeType == null) {
				return null;
			}

			String type = mimeType.toLowerCase(Locale.ROOT);

			switch (type) {
				case "video/vp8":
					return VP8;
				case "video/vp9":
					return VP9;
				case "video/av1":
					return AV1;
				case "audio/opus":
					return OPUS;
				default:
					return type.startsWith("audio/") ? AUDIO : null;
			}
		}

		/**
		 * @return The bytes at the start of a frame that stay in the clear,
		 * and that are the metadata the frame is authenticated with.
		 */
		int clearBytes(boolean keyFrame) {
			switch (this) {
				case VP8:
					return keyFrame ? 10 : 3;
				case OPUS:
					return 1;
				default:
					return 0;
			}
		}
	}

	/**
	 * The OBU header of an AV1 wrapper as sent, without the size bit the
	 * depacketizer sets: the metadata an AV1 frame is authenticated with.
	 */
	static int av1Wrapper(byte header) {
		return header & 0xFF & ~0x02;
	}

	/**
	 * Finds the SFrame ciphertext in a received AV1 frame: the payload of the
	 * one OBU it consists of.
	 *
	 * @return The offset of the payload, or -1 if the frame is no wrapped
	 * ciphertext.
	 */
	static int av1PayloadOffset(byte[] frame, int size) {
		if (size < 1) {
			return -1;
		}

		int header = av1Wrapper(frame[0]);

		// Type 1 or 6, no extension, the forbidden and reserved bits clear;
		// the depacketizer may have set the size bit.
		if (header != AV1_WRAPPER && header != AV1_KEY_WRAPPER) {
			return -1;
		}
		if ((frame[0] & 0x02) == 0) {
			return 1;
		}

		long obuSize = 0;
		int position = 1;

		for (int i = 0; i < 8; i++, position++) {
			if (position >= size) {
				return -1;
			}

			int b = frame[position] & 0xFF;
			obuSize |= (long) (b & 0x7F) << (7 * i);

			if ((b & 0x80) == 0) {
				position++;

				return obuSize == size - position ? position : -1;
			}
		}

		return -1;
	}

}
