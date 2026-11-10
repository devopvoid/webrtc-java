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

import java.util.ArrayList;
import java.util.List;

/**
 * A track of encoded frames that a test pushes into a recorder, with an
 * encoder behind it that answers the recorder's key frame requests as the
 * test tells it to: on the next frame, some frames later, or never.
 * <p>
 * The encoder takes the requests that arrived only when {@link FakeFeed}
 * moves the clock on, once the recorder has seen the new time, so it never
 * matters how soon the recorder's thread got to them.
 *
 * @author Alex Andres
 */
final class FakeTrack implements AutoCloseable {

	static final int WIDTH = 320;
	static final int HEIGHT = 240;

	static final int KEY_FRAME_SIZE = 4000;
	static final int DELTA_FRAME_SIZE = 1000;
	static final int AUDIO_FRAME_SIZE = 100;

	private static int nextSsrc = 1000;

	private final long frames;

	private final boolean video;

	private final int clockRate;

	private final int ssrc;

	private String mimeType;

	private long firstTimeUs = -1;

	/** Frames until a requested key frame, or -1 if none is asked for. */
	private int keyFrameIn = -1;

	/** A request that arrived, and is answered from the next moment on. */
	private boolean requested;

	/** Frames after a request that the key frame comes, or -1 for never. */
	private int keyFrameDelay;

	/** Frames between the key frames the encoder sends on its own, or 0. */
	private int keyFrameInterval;

	private boolean forceKeyFrame = true;

	private int requests;

	private int pushed;

	private final List<Long> keyFrameTimesUs = new ArrayList<>();


	private FakeTrack(boolean video, String mimeType, int clockRate) {
		this.video = video;
		this.mimeType = mimeType;
		this.clockRate = clockRate;
		this.frames = FakeMedia.createFrames();

		synchronized (FakeTrack.class) {
			this.ssrc = nextSsrc++;
		}
	}

	/**
	 * @return A VP8 track whose encoder sends a key frame first, and then on
	 * request only.
	 */
	static FakeTrack vp8() {
		return new FakeTrack(true, "video/VP8", 90000);
	}

	/**
	 * @return An Opus track.
	 */
	static FakeTrack opus() {
		return new FakeTrack(false, "audio/opus", 48000);
	}

	boolean isVideo() {
		return video;
	}

	/**
	 * Hands the track to the recorder, which takes a reference of its own.
	 */
	void addTo(MediaRecorder recorder) {
		FakeMedia.retainFrames(frames);

		try {
			recorder.addEncodedFrames(frames, this::requestKeyFrame);
		}
		catch (RuntimeException e) {
			FakeMedia.releaseFrames(frames);
			throw e;
		}
	}

	void addTo(ReplayBuffer buffer) {
		FakeMedia.retainFrames(frames);

		try {
			buffer.addEncodedFrames(frames, this::requestKeyFrame);
		}
		catch (RuntimeException e) {
			FakeMedia.releaseFrames(frames);
			throw e;
		}
	}

	/**
	 * Makes the encoder answer a request after the given number of frames,
	 * or never if it is negative.
	 */
	synchronized FakeTrack answerKeyFrameRequestsAfter(int frames) {
		keyFrameDelay = frames;
		return this;
	}

	/**
	 * Makes the encoder send a key frame of its own accord every so many
	 * frames, as many do.
	 */
	synchronized FakeTrack sendKeyFramesEvery(int frames) {
		keyFrameInterval = frames;
		return this;
	}

	/**
	 * Makes the next frame a key frame.
	 */
	synchronized void sendKeyFrame() {
		forceKeyFrame = true;
	}

	/**
	 * Goes on in another codec from the next frame, a key frame.
	 */
	synchronized void switchCodec(String mimeType) {
		this.mimeType = mimeType;
		this.forceKeyFrame = true;
	}

	/**
	 * @return How often the recorder asked for a key frame.
	 */
	synchronized int getKeyFrameRequests() {
		return requests;
	}

	/**
	 * @return How many frames were pushed.
	 */
	synchronized int getPushed() {
		return pushed;
	}

	/**
	 * @return When the key frames were pushed, in microseconds of the clock.
	 */
	synchronized List<Long> getKeyFrameTimesUs() {
		return new ArrayList<>(keyFrameTimesUs);
	}

	/**
	 * @return How many observers are attached, i.e. whether a recorder still
	 * takes the track's frames.
	 */
	int observers() {
		return FakeMedia.observers(frames);
	}

	/**
	 * Lets the encoder act on the requests that arrived so far. Called by
	 * {@link FakeFeed} each time it moves the clock on.
	 */
	synchronized void takeRequests() {
		if (requested) {
			requested = false;

			if (keyFrameDelay >= 0 && keyFrameIn < 0) {
				keyFrameIn = keyFrameDelay;
			}
		}
	}

	/**
	 * Pushes the next frame, stamped with the given time of the clock.
	 *
	 * @return Whether it was a key frame.
	 */
	boolean push(long timeUs) {
		boolean key;
		String codec;
		int rtpTimestamp;

		synchronized (this) {
			key = false;

			if (video) {
				if (forceKeyFrame || keyFrameIn == 0
						|| (keyFrameInterval > 0 && pushed % keyFrameInterval == 0)) {
					key = true;
				}
				else if (keyFrameIn > 0) {
					keyFrameIn--;
				}

				if (key) {
					forceKeyFrame = false;
					keyFrameIn = -1;
					keyFrameTimesUs.add(timeUs);
				}
			}

			if (firstTimeUs < 0) {
				firstTimeUs = timeUs;
			}

			codec = mimeType;
			rtpTimestamp = (int) ((timeUs - firstTimeUs) * clockRate / 1_000_000L);
			pushed++;
		}

		byte[] data = video ? videoPayload(codec, key) : audioPayload();

		FakeMedia.push(frames, video, codec, ssrc, rtpTimestamp, key, key ? WIDTH : 0, key ? HEIGHT : 0, timeUs,
				data);

		return key;
	}

	@Override
	public void close() {
		FakeMedia.releaseFrames(frames);
	}

	private synchronized void requestKeyFrame() {
		requests++;
		requested = true;
	}

	/**
	 * A video frame of the codec, as far as a recorder and a muxer look into
	 * it: the start of its frame header, which tells a key frame, and for
	 * VP8 its size.
	 */
	private static byte[] videoPayload(String codec, boolean key) {
		byte[] data = new byte[key ? KEY_FRAME_SIZE : DELTA_FRAME_SIZE];

		if (codec.equals("video/VP9")) {
			// The frame marker, profile 0, a frame shown, and its type: the
			// uncompressed header the superframe filter of Matroska reads.
			data[0] = (byte) (key ? 0x82 : 0x86);

			return data;
		}

		// VP8 (RFC 6386, section 9.1): shown, version 0, and the key frame
		// flag clear for a key frame.
		data[0] = (byte) (key ? 0x10 : 0x11);

		if (key) {
			data[3] = (byte) 0x9D;
			data[4] = (byte) 0x01;
			data[5] = (byte) 0x2A;
			data[6] = (byte) (WIDTH & 0xFF);
			data[7] = (byte) (WIDTH >> 8);
			data[8] = (byte) (HEIGHT & 0xFF);
			data[9] = (byte) (HEIGHT >> 8);
		}

		return data;
	}

	/**
	 * An Opus packet of one 20 ms CELT frame, mono (RFC 6716, section 3.1).
	 */
	private static byte[] audioPayload() {
		byte[] data = new byte[AUDIO_FRAME_SIZE];

		data[0] = (byte) 0xF8;

		return data;
	}

}
