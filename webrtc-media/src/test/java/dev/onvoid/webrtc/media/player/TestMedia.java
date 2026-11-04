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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntFunction;

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
		int[] channels = new int[stereoFrames + monoFrames];

		Arrays.fill(channels, 0, stereoFrames, 2);
		Arrays.fill(channels, stereoFrames, channels.length, 1);

		return Files.write(directory.resolve("channel-switch.flac"),
				flac(FLAC_SAMPLE_RATE, FLAC_BLOCK_SIZE, channels, TestMedia::sawtooth, -1));
	}

	/**
	 * Writes a mono, 16-bit FLAC file holding a constant, non-zero value, at
	 * a rate other than the 48 kHz WebRTC takes, so that playing it means
	 * resampling it. Everything played from it is non-zero, apart from what
	 * is missing: resampled completely, it plays as exactly as many non-zero
	 * samples at 48 kHz as it lasts.
	 *
	 * @param directory  Where to write the file.
	 * @param sampleRate 44100 or 48000.
	 * @param frames     How many 100 ms frames to write.
	 * @param value      The value of every sample.
	 *
	 * @return The file written.
	 */
	static Path constantFlac(Path directory, int sampleRate, int frames,
			short value) throws IOException {
		int[] channels = new int[frames];

		Arrays.fill(channels, 1);

		return Files.write(directory.resolve("constant.flac"),
				flac(sampleRate, sampleRate / 10, channels, n -> value, -1));
	}

	/**
	 * Writes a 48 kHz, mono, 16-bit FLAC file in which one frame cannot be
	 * decoded: its header is intact, so the file reads as usual, but its
	 * subframe uses a coding type FLAC reserves, which the decoder rejects.
	 *
	 * @param directory    Where to write the file.
	 * @param frames       How many 100 ms frames to write.
	 * @param corruptFrame Which of them to spoil.
	 *
	 * @return The file written.
	 */
	static Path corruptFlac(Path directory, int frames, int corruptFrame)
			throws IOException {
		int[] channels = new int[frames];

		Arrays.fill(channels, 1);

		return Files.write(directory.resolve("corrupt.flac"),
				flac(FLAC_SAMPLE_RATE, FLAC_BLOCK_SIZE, channels, TestMedia::sawtooth,
						corruptFrame));
	}

	/**
	 * Writes 48 kHz, mono, 16-bit PCM in a Matroska file, in blocks of 100 ms
	 * of a constant, non-zero value. Matroska gives each block a time of its
	 * own, so the blocks need not follow each other: a time later than where
	 * the block before ends leaves audio missing, an earlier one repeats some.
	 *
	 * @param directory  Where to write the file.
	 * @param value      The value of every sample.
	 * @param blockTimes When each block starts, in milliseconds.
	 *
	 * @return The file written.
	 */
	static Path pcmMatroska(Path directory, short value, long... blockTimes)
			throws IOException {
		byte[] block = pcmBlock(value, FLAC_BLOCK_SIZE);

		return Files.write(directory.resolve("pcm.mkv"),
				matroska(pcmTrack(), blockTimes, n -> block));
	}

	/**
	 * Writes 48 kHz, mono, 16-bit PCM in a Matroska file, all of it in a
	 * single block of a constant, non-zero value. A decoder gives all of a
	 * block out in one go, so a long one is more audio at once than a queue
	 * of a player can take.
	 *
	 * @param directory Where to write the file.
	 * @param value     The value of every sample.
	 * @param seconds   How long the block is.
	 *
	 * @return The file written.
	 */
	static Path longPcmBlockMatroska(Path directory, short value, int seconds)
			throws IOException {
		byte[] block = pcmBlock(value, seconds * FLAC_SAMPLE_RATE);

		return Files.write(directory.resolve("long-block.mkv"),
				matroska(pcmTrack(), new long[] { 0 }, n -> block));
	}

	private static byte[] pcmBlock(short value, int samples) {
		byte[] block = new byte[samples * 2];

		for (int i = 0; i < block.length; i += 2) {
			block[i] = (byte) value;
			block[i + 1] = (byte) (value >> 8);
		}

		return block;
	}

	private static byte[] pcmTrack() {
		return trackEntry("A_PCM/INT/LIT", null, 1,
				ebmlUInt(0x6264, 16));	// BitDepth
	}

	/**
	 * Copies a Matroska file holding a video and an audio track, moving each
	 * track to start at the given time, which is what a recording cut out of
	 * a longer one or a stream captured part way through looks like. The
	 * frames stay interleaved by time, and every video keyframe gets a cue.
	 * The copy does not say how long it runs.
	 *
	 * @param directory    Where to write the file.
	 * @param source       The file to copy.
	 * @param videoStartMs When the first video frame is to start.
	 * @param audioStartMs When the first audio frame is to start.
	 *
	 * @return The file written.
	 */
	static Path shiftedMatroska(Path directory, Path source, long videoStartMs,
			long audioStartMs) throws IOException {
		MatroskaContent content = new MatroskaContent(Files.readAllBytes(source));
		List<Frame> frames = new ArrayList<>();

		for (Frame frame : content.frames) {
			long start = frame.track == content.videoTrack ? videoStartMs : audioStartMs;

			frames.add(new Frame(frame.track, start + frame.timeMs, frame.key, frame.data));
		}

		frames.sort(Comparator.comparingLong(frame -> frame.timeMs));

		return Files.write(directory.resolve("shifted.mkv"),
				matroska(content.tracks, frames, content.videoTrack));
	}

	/**
	 * Copies a Matroska file holding a video and an audio track so that its
	 * video comes late. The audio is repeated until it runs for the given
	 * time, and all of it is stored ahead of the video, which keeps its times.
	 * A player reading this has to play audio to make room for the rest of it
	 * before it gets to the video, whose first frames are overdue by then.
	 *
	 * @param directory Where to write the file.
	 * @param source    The file to copy.
	 * @param audioMs   How long the audio is to run.
	 *
	 * @return The file written.
	 */
	static Path lateVideoMatroska(Path directory, Path source, long audioMs)
			throws IOException {
		MatroskaContent content = new MatroskaContent(Files.readAllBytes(source));
		List<Frame> video = new ArrayList<>();
		List<Frame> audio = new ArrayList<>();

		for (Frame frame : content.frames) {
			(frame.track == content.videoTrack ? video : audio).add(frame);
		}

		// Opus frames of 20 ms, the last of which ends a pass.
		long pass = audio.get(audio.size() - 1).timeMs + 20;
		List<Frame> frames = new ArrayList<>();

		for (long start = 0; start < audioMs; start += pass) {
			for (Frame frame : audio) {
				frames.add(new Frame(frame.track, start + frame.timeMs, frame.key, frame.data));
			}
		}

		frames.addAll(video);

		return Files.write(directory.resolve("late-video.mkv"),
				matroska(content.tracks, frames, -1));
	}

	/**
	 * Writes 44.1 kHz, mono, 16-bit PCM in an FLV file, in tags of 100 ms of a
	 * constant, non-zero value. FLV has no index, and FFmpeg finds its way
	 * through one by the entries it has made while reading, so a time before
	 * the first of them cannot be found.
	 *
	 * @param directory Where to write the file.
	 * @param value     The value of every sample.
	 * @param startMs   When the first tag starts.
	 * @param tags      How many tags to write.
	 *
	 * @return The file written.
	 */
	static Path pcmFlv(Path directory, short value, long startMs, int tags) throws IOException {
		byte[] audio = new byte[1 + 4410 * 2];

		// Little-endian PCM at 44.1 kHz, in 16 bits, mono.
		audio[0] = 0x3E;

		for (int i = 1; i < audio.length; i += 2) {
			audio[i] = (byte) value;
			audio[i + 1] = (byte) (value >> 8);
		}

		ByteArrayOutputStream out = new ByteArrayOutputStream();

		// The signature, version 1, audio only, the size of this header.
		writeBytes(out, ascii("FLV"));
		out.write(1);
		out.write(0x04);
		writeInt(out, 9, 4);
		// The size of the tag before the first one.
		writeInt(out, 0, 4);

		for (int i = 0; i < tags; i++) {
			long time = startMs + i * 100L;

			out.write(8);						// An audio tag
			writeInt(out, audio.length, 3);
			writeInt(out, time & 0xFFFFFF, 3);
			out.write((int) (time >>> 24));
			writeInt(out, 0, 3);				// The stream ID
			writeBytes(out, audio);
			writeInt(out, 11 + audio.length, 4);
		}

		return Files.write(directory.resolve("pcm.flv"), out.toByteArray());
	}

	/**
	 * Writes Opus in a Matroska file, with more channels than the eight a
	 * decoded frame has room for in its data pointers, in 20 ms packets of
	 * silence. One mono stream feeds every channel.
	 * <p>
	 * The channel mapping, family 255, names no layout, so the file plays as
	 * the usual layout for its channel count. For 14 channels that is 9.1.4,
	 * whose side and top channels, all of which a stereo downmix takes in,
	 * are planes 8 to 11 of a decoded frame.
	 *
	 * @param directory Where to write the file.
	 * @param channels  How many channels, 9 to 255.
	 * @param packets   How many 20 ms packets to write.
	 *
	 * @return The file written.
	 */
	static Path wideOpusMatroska(Path directory, int channels, int packets)
			throws IOException {
		ByteBuffer head = ByteBuffer.allocate(21 + channels)
				.order(ByteOrder.LITTLE_ENDIAN);

		head.put(ascii("OpusHead"))
				.put((byte) 1)			// Version
				.put((byte) channels)
				.putShort((short) 0)	// Pre-skip
				.putInt(48000)			// Input sample rate
				.putShort((short) 0)	// Output gain
				.put((byte) 255)		// Channel mapping family
				.put((byte) 1)			// Streams
				.put((byte) 0);			// Coupled streams

		// The channel mapping follows, all zeros as allocated: every channel
		// takes stream 0.

		// A CELT-only, fullband, 20 ms, mono frame (TOC 0xF8) whose range
		// coded data starts with the silence flag set.
		byte[] silence = { (byte) 0xF8, (byte) 0xFF, (byte) 0xFF };

		long[] times = new long[packets];

		for (int i = 0; i < packets; i++) {
			times[i] = i * 20L;
		}

		return Files.write(directory.resolve("wide-opus.mkv"),
				matroska(trackEntry("A_OPUS", head.array(), channels), times, n -> silence));
	}

	/**
	 * A Matroska file of one audio track and one block in each cluster, so
	 * that every block carries its time in full. Times are in milliseconds.
	 */
	private static byte[] matroska(byte[] track, long[] blockTimes,
			IntFunction<byte[]> block) {
		List<Frame> frames = new ArrayList<>();

		for (int i = 0; i < blockTimes.length; i++) {
			frames.add(new Frame(1, blockTimes[i], true, block.apply(i)));
		}

		return matroska(track, frames, -1);
	}

	/**
	 * A Matroska file of the given tracks and one frame in each cluster, with
	 * a cue for every keyframe of the given track, if any. The cues come
	 * before the clusters, where the demuxer finds them without a seek head.
	 */
	private static byte[] matroska(byte[] tracks, List<Frame> frames, int cueTrack) {
		ByteArrayOutputStream head = new ByteArrayOutputStream();

		// Info, with timestamps in milliseconds.
		writeBytes(head, ebml(0x1549A966, ebmlUInt(0x2AD7B1, 1000000)));
		// Tracks.
		writeBytes(head, ebml(0x1654AE6B, tracks));

		ByteArrayOutputStream clusters = new ByteArrayOutputStream();
		List<long[]> keyFrames = new ArrayList<>();

		for (Frame frame : frames) {
			if (frame.track == cueTrack && frame.key) {
				keyFrames.add(new long[] { frame.timeMs, clusters.size() });
			}

			ByteArrayOutputStream simpleBlock = new ByteArrayOutputStream();

			// The track, no offset from the cluster's time, the keyframe flag.
			simpleBlock.write(0x80 | frame.track);
			writeInt(simpleBlock, 0, 2);
			simpleBlock.write(frame.key ? 0x80 : 0);
			writeBytes(simpleBlock, frame.data);

			// A cluster: its time, then the SimpleBlock.
			writeBytes(clusters, ebml(0x1F43B675,
					ebmlUInt(0xE7, frame.timeMs),
					ebml(0xA3, simpleBlock.toByteArray())));
		}

		ByteArrayOutputStream segment = new ByteArrayOutputStream();

		writeBytes(segment, head.toByteArray());

		if (!keyFrames.isEmpty()) {
			// Every number takes eight bytes, so the size of the cues does not
			// depend on the positions they hold, which depend on that size.
			int cuesSize = cues(keyFrames, cueTrack, 0).length;

			writeBytes(segment, cues(keyFrames, cueTrack, head.size() + cuesSize));
		}

		writeBytes(segment, clusters.toByteArray());

		ByteArrayOutputStream out = new ByteArrayOutputStream();

		writeBytes(out, ebml(0x1A45DFA3,
				ebmlUInt(0x4286, 1),				// EBMLVersion
				ebmlUInt(0x42F7, 1),				// EBMLReadVersion
				ebmlUInt(0x42F2, 4),				// EBMLMaxIDLength
				ebmlUInt(0x42F3, 8),				// EBMLMaxSizeLength
				ebml(0x4282, ascii("matroska")),	// DocType
				ebmlUInt(0x4287, 4),				// DocTypeVersion
				ebmlUInt(0x4285, 2)));				// DocTypeReadVersion
		writeBytes(out, ebml(0x18538067, segment.toByteArray()));

		return out.toByteArray();
	}

	/**
	 * The Cues element for the given pairs of time and position, positions
	 * being relative to where the clusters start.
	 */
	private static byte[] cues(List<long[]> keyFrames, int track, long clustersStart) {
		ByteArrayOutputStream points = new ByteArrayOutputStream();

		for (long[] keyFrame : keyFrames) {
			writeBytes(points, ebml(0xBB,
					ebmlUInt(0xB3, keyFrame[0]),
					ebml(0xB7,
							ebmlUInt(0xF7, track),
							ebmlUInt(0xF1, clustersStart + keyFrame[1]))));
		}

		return ebml(0x1C53BB6B, points.toByteArray());
	}

	/**
	 * The TrackEntry of track 1, an audio track at 48 kHz, with any further
	 * audio settings given.
	 */
	private static byte[] trackEntry(String codec, byte[] codecPrivate,
			int channels, byte[]... audio) {
		ByteArrayOutputStream settings = new ByteArrayOutputStream();

		writeBytes(settings, ebml(0xB5, ByteBuffer.allocate(8)
				.putDouble(48000).array()));	// SamplingFrequency
		writeBytes(settings, ebmlUInt(0x9F, channels));

		for (byte[] setting : audio) {
			writeBytes(settings, setting);
		}

		ByteArrayOutputStream entry = new ByteArrayOutputStream();

		writeBytes(entry, ebmlUInt(0xD7, 1));		// TrackNumber
		writeBytes(entry, ebmlUInt(0x73C5, 1));		// TrackUID
		writeBytes(entry, ebmlUInt(0x83, 2));		// TrackType, audio
		writeBytes(entry, ebml(0x86, ascii(codec)));

		if (codecPrivate != null) {
			writeBytes(entry, ebml(0x63A2, codecPrivate));
		}

		writeBytes(entry, ebml(0xE1, settings.toByteArray()));

		return ebml(0xAE, entry.toByteArray());
	}

	/**
	 * An EBML element: its ID, which carries its own length, then its size,
	 * always in eight bytes, then the children or data given.
	 */
	private static byte[] ebml(int id, byte[]... content) {
		ByteArrayOutputStream payload = new ByteArrayOutputStream();

		for (byte[] bytes : content) {
			writeBytes(payload, bytes);
		}

		ByteArrayOutputStream out = new ByteArrayOutputStream();

		writeInt(out, id, (32 - Integer.numberOfLeadingZeros(id) + 7) / 8);
		out.write(0x01);
		writeInt(out, payload.size(), 7);
		writeBytes(out, payload.toByteArray());

		return out.toByteArray();
	}

	private static byte[] ebmlUInt(int id, long value) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		writeInt(out, value, 8);

		return ebml(id, out.toByteArray());
	}

	/**
	 * A sawtooth in steps of 37, so that no two neighbouring samples are ever
	 * equal.
	 */
	private static short sawtooth(int n) {
		return (short) ((n * 37) % 16000 - 8000);
	}

	/**
	 * A FLAC stream of fixed size frames, as many as channel counts are given.
	 * The stream starts with the channel count of its first frame.
	 */
	private static byte[] flac(int sampleRate, int blockSize, int[] frameChannels,
			IntFunction<Short> signal, int corruptFrame) {
		// The frame number is written as a single byte below.
		if (frameChannels.length > 127) {
			throw new IllegalArgumentException("Too many frames: " + frameChannels.length);
		}

		ByteArrayOutputStream out = new ByteArrayOutputStream();

		writeBytes(out, ascii("fLaC"));

		// The last metadata block, of type STREAMINFO, 34 bytes long.
		out.write(0x80);
		writeInt(out, 34, 3);

		// Minimum and maximum block size, then unknown frame sizes.
		writeInt(out, blockSize, 2);
		writeInt(out, blockSize, 2);
		writeInt(out, 0, 3);
		writeInt(out, 0, 3);

		// Sample rate, channels - 1, bits per sample - 1, total samples.
		long totalSamples = (long) frameChannels.length * blockSize;
		long packed = ((long) sampleRate << 44) | ((long) (frameChannels[0] - 1) << 41)
				| (15L << 36) | totalSamples;

		writeInt(out, packed, 8);

		// No MD5 of the audio.
		writeBytes(out, new byte[16]);

		int sample = 0;

		for (int frame = 0; frame < frameChannels.length; frame++) {
			short[] samples = new short[blockSize];

			for (int i = 0; i < samples.length; i++) {
				samples[i] = signal.apply(sample++);
			}

			writeBytes(out, flacFrame(frame, sampleRate, frameChannels[frame], samples,
					frame == corruptFrame));
		}

		return out.toByteArray();
	}

	/**
	 * One FLAC frame of verbatim subframes, every channel carrying the given
	 * samples.
	 */
	private static byte[] flacFrame(int number, int sampleRate, int channels,
			short[] samples, boolean corrupt) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		// Sync code, fixed block size.
		out.write(0xFF);
		out.write(0xF8);
		// Block size as 16 bits at the end of the header; the sample rate.
		out.write(0x70 | sampleRateCode(sampleRate));
		// Mono or independent stereo; 16 bits per sample.
		out.write(((channels - 1) << 4) | 0x08);
		// The frame number, which fits a single byte of its UTF-8 coding.
		out.write(number);
		writeInt(out, samples.length - 1, 2);
		out.write(crc8(out.toByteArray()));

		for (int channel = 0; channel < channels; channel++) {
			// A verbatim subframe, no wasted bits; or the first of the coding
			// types FLAC reserves.
			out.write(corrupt ? 0x04 : 0x02);

			for (short value : samples) {
				writeInt(out, value & 0xFFFF, 2);
			}
		}

		writeInt(out, crc16(out.toByteArray()), 2);

		return out.toByteArray();
	}

	private static int sampleRateCode(int sampleRate) {
		switch (sampleRate) {
			case 44100:
				return 0x9;
			case 48000:
				return 0xA;
			default:
				throw new IllegalArgumentException("Unsupported sample rate: " + sampleRate);
		}
	}

	private static void writeBytes(ByteArrayOutputStream out, byte[] bytes) {
		out.write(bytes, 0, bytes.length);
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

	/** One frame of a Matroska file, with its time in milliseconds. */
	private static final class Frame {

		final int track;
		final long timeMs;
		final boolean key;
		final byte[] data;


		Frame(int track, long timeMs, boolean key, byte[] data) {
			this.track = track;
			this.timeMs = timeMs;
			this.key = key;
			this.data = data;
		}
	}

	/**
	 * What a Matroska file with timestamps in milliseconds holds, as far as
	 * the committed assets use the format: no lacing, and track numbers of
	 * one byte.
	 */
	private static final class MatroskaContent {

		final List<Frame> frames = new ArrayList<>();
		byte[] tracks;
		int videoTrack;

		private final ByteBuffer data;
		private long clusterTime;
		private long trackNumber;


		MatroskaContent(byte[] file) {
			data = ByteBuffer.wrap(file);

			read(file.length);
		}

		private void read(int end) {
			while (data.position() < end) {
				int id = readId();
				long size = readSize();
				int start = data.position();
				int stop = size < 0 ? end : (int) Math.min(start + size, end);

				switch (id) {
					case 0x18538067:	// Segment
					case 0x1F43B675:	// Cluster
					case 0xA0:			// BlockGroup
					case 0xAE:			// TrackEntry
						read(stop);
						break;
					case 0x1654AE6B:	// Tracks
						tracks = Arrays.copyOfRange(data.array(), start, stop);
						read(stop);
						break;
					case 0xD7:			// TrackNumber
						trackNumber = readUInt(stop);
						break;
					case 0x83:			// TrackType, which follows the number
						if (readUInt(stop) == 1) {
							videoTrack = (int) trackNumber;
						}
						break;
					case 0xE7:			// Timestamp
						clusterTime = readUInt(stop);
						break;
					case 0xA3:			// SimpleBlock
					case 0xA1:			// Block
						readBlock(stop, id == 0xA3);
						break;
					default:
						break;
				}

				data.position(stop);
			}
		}

		private void readBlock(int end, boolean simple) {
			int track = data.get() & 0x7F;
			long time = clusterTime + data.getShort();
			int flags = data.get() & 0xFF;

			if ((flags & 0x06) != 0) {
				throw new IllegalArgumentException("Laced block");
			}

			frames.add(new Frame(track, time, !simple || (flags & 0x80) != 0,
					Arrays.copyOfRange(data.array(), data.position(), end)));
		}

		private long readUInt(int end) {
			long value = 0;

			while (data.position() < end) {
				value = (value << 8) | (data.get() & 0xFF);
			}

			return value;
		}

		private int readId() {
			int first = data.get() & 0xFF;
			int value = first;

			for (int i = Integer.numberOfLeadingZeros(first) - 24; i > 0; i--) {
				value = (value << 8) | (data.get() & 0xFF);
			}

			return value;
		}

		/** Returns the size, or -1 for an element of unknown size. */
		private long readSize() {
			int first = data.get() & 0xFF;
			int length = Integer.numberOfLeadingZeros(first) - 24;
			int mask = 0xFF >> (length + 1);
			long value = first & mask;
			boolean unknown = value == mask;

			for (int i = 0; i < length; i++) {
				int next = data.get() & 0xFF;

				unknown &= next == 0xFF;
				value = (value << 8) | next;
			}

			return unknown ? -1 : value;
		}
	}

}
