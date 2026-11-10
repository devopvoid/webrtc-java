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

import dev.onvoid.webrtc.media.player.FFmpeg;

/**
 * Stands in for the webrtc-java library that a recorder takes its encoded
 * frames and its clock from, so that a test decides what arrives, and what
 * time it is. What a recorder makes of its frames then follows from them
 * alone, however fast the machine is. It lives in the tests so that nothing
 * else can reach it.
 *
 * @see FakeTrack
 * @see FakeFeed
 *
 * @author Alex Andres
 */
final class FakeMedia {

	static {
		FFmpeg.load();
	}


	private FakeMedia() {
		// Static access only.
	}

	/**
	 * @return The address of the function table, for a recorder to be
	 * created with.
	 */
	static native long tableAddress();

	/**
	 * Sets the clock the recorder reads, in microseconds.
	 */
	static native void setClock(long nowUs);

	/**
	 * Creates what a sender or receiver would be, holding one reference.
	 */
	static native long createFrames();

	static native void retainFrames(long frames);

	static native void releaseFrames(long frames);

	/**
	 * @return How many observers are attached to the frames.
	 */
	static native int observers(long frames);

	/**
	 * Hands one encoded frame to the observers of the frames, on the calling
	 * thread, as WebRTC does on its own.
	 */
	static native void push(long frames, boolean video, String mimeType, int ssrc, int rtpTimestamp,
			boolean keyFrame, int width, int height, long timeUs, byte[] data);

}
