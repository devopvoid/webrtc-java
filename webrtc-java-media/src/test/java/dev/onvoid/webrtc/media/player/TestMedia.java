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

import java.io.ByteArrayOutputStream;
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

	/** The rate of the FLAC file, which is what WebRTC takes unchanged. */
	static final int FLAC_SAMPLE_RATE = 48000;

	/** 100 ms per FLAC frame. */
	static final int FLAC_BLOCK_SIZE = 4800;


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

	/**
	 * Writes a 48 kHz, 16-bit FLAC file that starts in stereo and switches to
	 * mono part way through, which FLAC allows from one frame to the next.
	 * <p>
	 * Every stereo frame carries the same signal in both channels, and every
	 * mono frame a sawtooth whose neighbouring samples always differ. Played
	 * correctly, as stereo output, the left and right channel are therefore
	 * equal throughout; a mono frame read as if it were still stereo splits
	 * its neighbouring samples between the two channels and makes them differ.
	 *
	 * @param directory    Where to write the file.
	 * @param stereoFrames How many 100 ms frames of stereo to write first.
	 * @param monoFrames   How many 100 ms frames of mono follow them.
	 *
	 * @return The file written.
	 */
	static Path channelSwitchingFlac(Path directory, int stereoFrames,
			int monoFrames) throws IOException {
		final int frameCount = stereoFrames + monoFrames;

		// The frame number is written as a single byte below.
		if (frameCount > 127) {
			throw new IllegalArgumentException("Too many frames: " + frameCount);
		}

		ByteArrayOutputStream out = new ByteArrayOutputStream();

		out.write(ascii("fLaC"));

		// The last metadata block, of type STREAMINFO, 34 bytes long.
		out.write(0x80);
		writeInt(out, 34, 3);

		// Minimum and maximum block size, then unknown frame sizes.
		writeInt(out, FLAC_BLOCK_SIZE, 2);
		writeInt(out, FLAC_BLOCK_SIZE, 2);
		writeInt(out, 0, 3);
		writeInt(out, 0, 3);

		// Sample rate, channels - 1, bits per sample - 1, total samples.
		long totalSamples = (long) frameCount * FLAC_BLOCK_SIZE;
		long packed = ((long) FLAC_SAMPLE_RATE << 44) | (1L << 41) | (15L << 36)
				| totalSamples;

		writeInt(out, packed, 8);

		// No MD5 of the audio.
		out.write(new byte[16]);

		int sample = 0;

		for (int frame = 0; frame < frameCount; frame++) {
			boolean stereo = frame < stereoFrames;
			short[] samples = new short[FLAC_BLOCK_SIZE];

			for (int i = 0; i < samples.length; i++) {
				// A sawtooth in steps of 37, so that no two neighbouring
				// samples are ever equal.
				samples[i] = (short) ((sample++ * 37) % 16000 - 8000);
			}

			out.write(flacFrame(frame, stereo ? 2 : 1, samples));
		}

		return Files.write(directory.resolve("channel-switch.flac"), out.toByteArray());
	}

	/**
	 * One FLAC frame of verbatim subframes, every channel carrying the given
	 * samples.
	 */
	private static byte[] flacFrame(int number, int channels, short[] samples) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		// Sync code, fixed block size.
		out.write(0xFF);
		out.write(0xF8);
		// Block size as 16 bits at the end of the header; 48 kHz.
		out.write(0x7A);
		// Mono or independent stereo; 16 bits per sample.
		out.write(((channels - 1) << 4) | 0x08);
		// The frame number, which fits a single byte of its UTF-8 coding.
		out.write(number);
		writeInt(out, samples.length - 1, 2);
		out.write(crc8(out.toByteArray()));

		for (int channel = 0; channel < channels; channel++) {
			// A verbatim subframe, no wasted bits.
			out.write(0x02);

			for (short value : samples) {
				writeInt(out, value & 0xFFFF, 2);
			}
		}

		writeInt(out, crc16(out.toByteArray()), 2);

		return out.toByteArray();
	}

	private static void writeInt(ByteArrayOutputStream out, long value, int bytes) {
		for (int shift = (bytes - 1) * 8; shift >= 0; shift -= 8) {
			out.write((int) (value >>> shift) & 0xFF);
		}
	}

	private static int crc8(byte[] data) {
		int crc = 0;

		for (byte b : data) {
			crc ^= b & 0xFF;

			for (int bit = 0; bit < 8; bit++) {
				crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x07) & 0xFF : (crc << 1) & 0xFF;
			}
		}

		return crc;
	}

	private static int crc16(byte[] data) {
		int crc = 0;

		for (byte b : data) {
			crc ^= (b & 0xFF) << 8;

			for (int bit = 0; bit < 8; bit++) {
				crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x8005) & 0xFFFF : (crc << 1) & 0xFFFF;
			}
		}

		return crc;
	}

	private static byte[] ascii(String text) {
		return text.getBytes(StandardCharsets.US_ASCII);
	}

}
