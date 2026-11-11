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

package dev.onvoid.webrtc.media.audio;

import static org.junit.jupiter.api.Assertions.*;

import dev.onvoid.webrtc.RTCRtpPacketInfo;
import dev.onvoid.webrtc.TestBase;
import dev.onvoid.webrtc.internal.SinkRegistry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A custom source hands its audio to the sinks of its track before {@code
 * pushAudio} returns, on the pushing thread, so each test sees every call of
 * its sink by the time the push is done.
 */
class AudioDataAccessTests extends TestBase {

	private CustomAudioSource source;

	private AudioTrack track;


	@BeforeEach
	void init() {
		source = new CustomAudioSource();
		track = factory.createAudioTrack("audioTrack", source);
	}

	@AfterEach
	void dispose() {
		track.dispose();
		source.dispose();
	}

	@Test
	void copiedArraysAreKept() {
		List<byte[]> arrays = new ArrayList<>();

		track.addSink((data, bits, rate, channels, frames) -> arrays.add(data));

		push(1, 480);
		push(2, 480);

		assertEquals(2, arrays.size());
		assertNotSame(arrays.get(0), arrays.get(1));
		assertFilled(arrays.get(0), 1, 480);
		assertFilled(arrays.get(1), 2, 480);
	}

	@Test
	void reusedArrayIsTheSame() {
		List<byte[]> arrays = new ArrayList<>();
		List<byte[]> contents = new ArrayList<>();

		track.addSink((data, bits, rate, channels, frames) -> {
			arrays.add(data);
			contents.add(data.clone());
		}, AudioDataAccess.REUSED);

		push(1, 480);
		push(2, 480);

		assertEquals(2, arrays.size());
		assertSame(arrays.get(0), arrays.get(1));
		assertFilled(contents.get(0), 1, 480);
		assertFilled(contents.get(1), 2, 480);
	}

	@Test
	void reusedArrayFollowsChunkSize() {
		List<byte[]> arrays = new ArrayList<>();

		track.addSink((data, bits, rate, channels, frames) -> arrays.add(data), AudioDataAccess.REUSED);

		push(1, 480);
		push(2, 160);
		push(3, 160);

		assertEquals(480 * 4, arrays.get(0).length);
		assertEquals(160 * 4, arrays.get(1).length);
		assertSame(arrays.get(1), arrays.get(2));
		assertFilled(arrays.get(2), 3, 160);
	}

	@Test
	void sinksReuseArraysOfTheirOwn() {
		List<byte[]> first = new ArrayList<>();
		List<byte[]> second = new ArrayList<>();

		track.addSink((data, bits, rate, channels, frames) -> first.add(data), AudioDataAccess.REUSED);
		track.addSink((data, bits, rate, channels, frames) -> second.add(data), AudioDataAccess.REUSED);

		push(1, 480);

		assertNotSame(first.get(0), second.get(0));
	}

	@Test
	void reusedSinkGetsPacketInfos() {
		List<byte[]> arrays = new ArrayList<>();
		List<RTCRtpPacketInfo[]> infos = new ArrayList<>();

		track.addSink(new AudioTrackSink() {

			@Override
			public void onData(byte[] data, int bitsPerSample, int sampleRate, int channels, int frames) {
				fail("Called without packets");
			}

			@Override
			public void onData(byte[] data, int bitsPerSample, int sampleRate, int channels, int frames,
					RTCRtpPacketInfo[] packetInfos) {
				arrays.add(data);
				infos.add(packetInfos);
			}
		}, AudioDataAccess.REUSED);

		push(1, 480);
		push(2, 480);

		assertSame(arrays.get(0), arrays.get(1));
		// A local track has no packets.
		assertEquals(0, infos.get(0).length);
	}

	@Test
	void reusedSinkSwapsInCallback() throws Exception {
		List<String> calls = new ArrayList<>();

		AudioTrackSink second = (data, bits, rate, channels, frames) -> calls.add("second");

		track.addSink(new AudioTrackSink() {

			@Override
			public void onData(byte[] data, int bitsPerSample, int sampleRate, int channels, int frames) {
				calls.add("first");

				// The source holds the lock that adding and removing take while
				// it is here.
				track.removeSink(this);
				track.addSink(second, AudioDataAccess.REUSED);
			}
		}, AudioDataAccess.REUSED);

		assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
			push(1, 480);

			// The native calls made from within the callback come after it.
			SinkRegistry.awaitDeferred();

			push(2, 480);
		});

		assertEquals(Arrays.asList("first", "second"), calls);
	}

	@Test
	void addAgainKeepsAccess() {
		List<byte[]> arrays = new ArrayList<>();
		AudioTrackSink sink = (data, bits, rate, channels, frames) -> arrays.add(data);

		track.addSink(sink, AudioDataAccess.REUSED);
		track.addSink(sink, AudioDataAccess.COPY);

		push(1, 480);
		push(2, 480);

		assertEquals(2, arrays.size());
		assertSame(arrays.get(0), arrays.get(1));
	}

	@Test
	void addNullAccess() {
		AudioTrackSink sink = (data, bits, rate, channels, frames) -> { };

		assertThrows(NullPointerException.class, () -> track.addSink(sink, null));
		assertThrows(NullPointerException.class, () -> track.addSink(null, AudioDataAccess.REUSED));
	}

	/**
	 * Pushes 10 ms of 16-bit stereo audio whose every byte is {@code value},
	 * at the sample rate that makes that the given number of frames.
	 */
	private void push(int value, int frames) {
		byte[] data = new byte[frames * 2 * 2];

		Arrays.fill(data, (byte) value);

		source.pushAudio(data, 16, frames * 100, 2, frames);
	}

	private static void assertFilled(byte[] data, int value, int frames) {
		byte[] expected = new byte[frames * 2 * 2];

		Arrays.fill(expected, (byte) value);

		assertArrayEquals(expected, data);
	}

}
