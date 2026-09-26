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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes small media files that the committed assets cannot provide, because
 * what makes them useful is something no encoder would produce on purpose.
 * They are written by hand here rather than committed, so that what they hold
 * is spelled out next to the tests that rely on it.
 *
 * @author Alex Andres
 */
final class TestMedia {

	private TestMedia() {
		// Static access only.
	}

	/**
	 * Writes one second of 8 kHz G.711 mu-law in a WAV file. The WAV demuxer
	 * is part of the FFmpeg build and knows the format, but its decoder is
	 * not, so this opens as a reader and fails as a player.
	 *
	 * @param directory Where to write the file.
	 *
	 * @return The file written.
	 */
	static Path muLawWav(Path directory) throws IOException {
		final int rate = 8000;
		final int length = rate;

		ByteBuffer buffer = ByteBuffer.allocate(44 + length)
				.order(ByteOrder.LITTLE_ENDIAN);

		buffer.put(ascii("RIFF")).putInt(36 + length).put(ascii("WAVE"));

		buffer.put(ascii("fmt ")).putInt(16)
				.putShort((short) 7)	// WAVE_FORMAT_MULAW
				.putShort((short) 1)	// Channels
				.putInt(rate)			// Sample rate
				.putInt(rate)			// Byte rate
				.putShort((short) 1)	// Block align
				.putShort((short) 8);	// Bits per sample

		buffer.put(ascii("data")).putInt(length);

		while (buffer.hasRemaining()) {
			// Silence in mu-law.
			buffer.put((byte) 0xFF);
		}

		return Files.write(directory.resolve("mulaw.wav"), buffer.array());
	}

	private static byte[] ascii(String text) {
		return text.getBytes(StandardCharsets.US_ASCII);
	}

}
