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

package dev.onvoid.webrtc.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import dev.onvoid.webrtc.media.video.I420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoFrameBuffer;

import org.junit.jupiter.api.Test;

/**
 * Feeds audio and video dispatchers by hand. Their executors share a {@link
 * ManualExecutor} as the subscriptions of a runtime share its frame pool, and
 * a clock that moves only when the test moves it.
 */
class DispatcherTests {

	/** 10 ms of 48 kHz mono. */
	private static final int CHUNK_FRAMES = 480;

	private final List<Throwable> errors = new ArrayList<>();

	private final ManualExecutor pool = new ManualExecutor();

	private long now;

	private final Workload work = new Workload(() -> now);


	@Test
	void audioBufferDropsOldest() {
		List<Integer> heard = new ArrayList<>();
		AudioDispatcher audio = audio(Duration.ofMillis(30), chunk -> heard.add(number(chunk)));

		for (int i = 0; i < 5; i++) {
			push(audio, i);
		}

		LoadStats.Work load = work.snapshot();

		assertEquals(3, load.queued());
		assertEquals(2, load.dropped());

		pool.runAll();

		assertEquals(Arrays.asList(2, 3, 4), heard);
		assertEquals(0, work.snapshot().queued());
		assertTrue(errors.isEmpty(), () -> "Errors: " + errors);
	}

	@Test
	void shortBufferKeepsNewest() {
		List<Integer> heard = new ArrayList<>();
		AudioDispatcher audio = audio(Duration.ofMillis(1), chunk -> heard.add(number(chunk)));

		for (int i = 0; i < 3; i++) {
			push(audio, i);
		}

		pool.runAll();

		assertEquals(Arrays.asList(2), heard);
		assertEquals(2, work.snapshot().dropped());
	}

	@Test
	void handlerCountedActive() {
		List<LoadStats.Work> seen = new ArrayList<>();
		AudioDispatcher audio = audio(Duration.ofSeconds(1), chunk -> seen.add(work.snapshot()));

		push(audio, 0);
		now += 4_000_000;
		push(audio, 1);
		now += 6_000_000;

		LoadStats.Work before = work.snapshot();

		assertEquals(2, before.queued());
		assertEquals(Duration.ofMillis(10), before.oldestWaiting());

		pool.runAll();

		// The first chunk is in the handler, the second waits since 4 ms.
		assertEquals(1, seen.get(0).active());
		assertEquals(1, seen.get(0).queued());
		assertEquals(Duration.ofMillis(6), seen.get(0).oldestWaiting());
		assertEquals(0, work.snapshot().active());
	}

	@Test
	void subscriptionsTakeTurns() {
		// A handler as slow as the audio it gets keeps a thread of a shared
		// pool only for its turn, after which the other subscription runs.
		List<String> heard = new ArrayList<>();
		long slow = SerialExecutor.TURN_NANOS / 4;
		AudioDispatcher busy = audio(Duration.ofSeconds(10), chunk -> {
			heard.add("busy-" + number(chunk));
			now += slow;
		});
		AudioDispatcher quiet = audio(Duration.ofSeconds(10), chunk -> heard.add("quiet-" + number(chunk)));

		for (int i = 0; i < 20; i++) {
			push(busy, i);
		}
		push(quiet, 0);

		pool.runAll();

		List<String> expected = IntStream.range(0, 4).mapToObj(i -> "busy-" + i).collect(Collectors.toList());
		expected.add("quiet-0");

		assertEquals(expected, heard.subList(0, 5));
		assertEquals(21, heard.size());
	}

	@Test
	void framesReleased() {
		List<Integer> shown = new ArrayList<>();
		List<CountedBuffer> buffers = new ArrayList<>();
		FrameDispatcher video = new FrameDispatcher(frame -> shown.add((int) frame.timestampNs),
				FrameDelivery.queue(2), executor(), work, errors::add);

		for (int i = 0; i < 5; i++) {
			buffers.add(new CountedBuffer());
		}
		for (int i = 0; i < 4; i++) {
			video.onVideoFrame(new VideoFrame(buffers.get(i), i));
		}

		assertEquals(2, work.snapshot().dropped());

		pool.runAll();
		video.onVideoFrame(new VideoFrame(buffers.get(4), 4));
		video.close();
		pool.runAll();

		// The first two were dropped, the next two shown, the last left over.
		assertEquals(Arrays.asList(2, 3), shown);
		buffers.forEach(buffer -> assertEquals(1, buffer.released));
		assertEquals(2, work.snapshot().dropped());
		assertEquals(0, work.snapshot().queued());
	}

	@Test
	void rejectedFramesReleased() {
		List<Integer> shown = new ArrayList<>();
		CountedBuffer rejected = new CountedBuffer();
		CountedBuffer accepted = new CountedBuffer();
		FrameDispatcher video = new FrameDispatcher(frame -> shown.add((int) frame.timestampNs),
				FrameDelivery.queue(2), executor(), work, errors::add);

		pool.rejecting(true);
		video.onVideoFrame(new VideoFrame(rejected, 0));

		// Let go of at once, not only at close.
		assertEquals(1, rejected.released);
		assertEquals(1, work.snapshot().dropped());
		assertEquals(0, work.snapshot().queued());
		assertEquals(1, errors.size());
		assertInstanceOf(WebRtcException.class, errors.remove(0));

		// Nothing is left scheduled, so the next frame tries again.
		pool.rejecting(false);
		video.onVideoFrame(new VideoFrame(accepted, 1));
		pool.runAll();

		assertEquals(Arrays.asList(1), shown);
		assertEquals(1, accepted.released);
		assertTrue(errors.isEmpty(), () -> "Errors: " + errors);

		video.close();
	}

	private AudioDispatcher audio(Duration buffer, Consumer<AudioChunk> handler) {
		return new AudioDispatcher(handler, buffer, executor(), work, errors::add);
	}

	/**
	 * As a runtime does, the executor of a subscription counts nothing
	 * itself; its dispatcher counts the media.
	 */
	private SerialExecutor executor() {
		return new SerialExecutor(pool, new Workload(() -> now), errors::add, false, null);
	}

	/**
	 * Pushes a chunk whose first sample tells which it is.
	 */
	private static void push(AudioDispatcher audio, int number) {
		byte[] data = new byte[CHUNK_FRAMES * 2];
		data[0] = (byte) number;

		audio.onData(data, 16, 48000, 1, CHUNK_FRAMES);
	}

	private static int number(AudioChunk chunk) {
		return chunk.samples()[0];
	}



	private static final class CountedBuffer implements VideoFrameBuffer {

		int released;


		@Override
		public int getWidth() {
			return 2;
		}

		@Override
		public int getHeight() {
			return 2;
		}

		@Override
		public I420Buffer toI420() {
			throw new UnsupportedOperationException();
		}

		@Override
		public VideoFrameBuffer cropAndScale(int cropX, int cropY, int cropWidth, int cropHeight,
				int scaleWidth, int scaleHeight) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void retain() {
		}

		@Override
		public void release() {
			released++;
		}
	}
}
