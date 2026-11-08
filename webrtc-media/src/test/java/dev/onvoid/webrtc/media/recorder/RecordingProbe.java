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

package dev.onvoid.webrtc.media.recorder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.onvoid.webrtc.media.player.FFmpeg;

/**
 * Reads a recorded file packet by packet, which shows what its header alone
 * does not: where each stream begins, and whether at a key frame. It lives in
 * the tests so that nothing else can reach it.
 *
 * @author Alex Andres
 */
final class RecordingProbe {

	static {
		FFmpeg.load();
	}

	/** How many values the native probe reports per stream. */
	private static final int VALUES = 5;


	private RecordingProbe() {
		// Static access only.
	}

	/**
	 * @return The streams of the file, in the order the file has them.
	 */
	static List<Stream> streamsOf(Path file) throws IOException {
		long[] values = streams(file.toString());
		List<Stream> list = new ArrayList<>();

		for (int i = 0; i + VALUES <= values.length; i += VALUES) {
			list.add(new Stream(values[i] == 0, values[i] == 1, values[i + 1] == 1, values[i + 2],
					values[i + 3], values[i + 4]));
		}

		return list;
	}

	private static native long[] streams(String path) throws IOException;


	/** What a file holds of one stream. */
	static final class Stream {

		final boolean video;

		final boolean audio;

		/** Whether the first packet is a key frame. */
		final boolean startsWithKeyFrame;

		final long firstPtsUs;

		final long lastPtsUs;

		final long packets;


		Stream(boolean video, boolean audio, boolean startsWithKeyFrame, long firstPtsUs, long lastPtsUs,
				long packets) {
			this.video = video;
			this.audio = audio;
			this.startsWithKeyFrame = startsWithKeyFrame;
			this.firstPtsUs = firstPtsUs;
			this.lastPtsUs = lastPtsUs;
			this.packets = packets;
		}

		@Override
		public String toString() {
			return (video ? "video" : audio ? "audio" : "other") + "[key=" + startsWithKeyFrame + ", first="
					+ firstPtsUs + ", last=" + lastPtsUs + ", packets=" + packets + "]";
		}
	}

}
