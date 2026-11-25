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

package dev.onvoid.webrtc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests the threshold at which the buffered amount of a data channel counts
 * as low, and the event that tells of it.
 */
@Execution(ExecutionMode.SAME_THREAD)
class RTCDataChannelBufferedAmountLowTests extends TestBase {

	private static final int MESSAGE_SIZE = 64 * 1024;


	@Test
	void thresholdDefaultsToZero() throws Exception {
		try (TestDataChannelPair pair = new TestDataChannelPair(factory)) {
			assertEquals(0, pair.sender.getBufferedAmountLowThreshold());

			pair.sender.setBufferedAmountLowThreshold(1024);
			assertEquals(1024, pair.sender.getBufferedAmountLowThreshold());

			assertThrows(IllegalArgumentException.class, () -> pair.sender.setBufferedAmountLowThreshold(-1));
			assertEquals(1024, pair.sender.getBufferedAmountLowThreshold());
		}
	}

	@Test
	void lowOnceEmpty() throws Exception {
		try (TestDataChannelPair pair = new TestDataChannelPair(factory)) {
			pair.connect();

			Drain drain = new Drain(pair.sender);
			pair.sender.registerObserver(drain);

			send(pair.sender, 4);
			drain.await(4L * MESSAGE_SIZE);
			// The low comes right after the decrease that emptied the buffer.
			pair.awaitSenderEvents();

			assertEquals(List.of(0L), drain.lows);
		}
	}

	@Test
	void lowAtThreshold() throws Exception {
		long threshold = 512 * 1024;

		try (TestDataChannelPair pair = new TestDataChannelPair(factory)) {
			pair.connect();

			Drain drain = new Drain(pair.sender);
			pair.sender.setBufferedAmountLowThreshold(threshold);
			pair.sender.registerObserver(drain);

			// 2 MiB, far above the threshold.
			send(pair.sender, 32);
			drain.await(32L * MESSAGE_SIZE);
			pair.awaitSenderEvents();

			// Falling through the threshold once, whatever steps it fell in.
			assertEquals(1, drain.lows.size(), "lows: " + drain.lows);
			assertTrue(drain.lows.get(0) <= threshold, "low at " + drain.lows.get(0));
		}
	}

	@Test
	void asyncSendsCounted() throws Exception {
		long threshold = 512 * 1024;

		try (TestDataChannelPair pair = new TestDataChannelPair(factory)) {
			// Refused before the channel is open, so never counted.
			pair.sender.sendAsync(new RTCDataChannelBuffer(ByteBuffer.wrap(new byte[MESSAGE_SIZE]), true));

			pair.connect();

			Drain drain = new Drain(pair.sender);
			pair.sender.setBufferedAmountLowThreshold(threshold);
			pair.sender.registerObserver(drain);

			CountDownLatch completed = new CountDownLatch(32);

			for (int i = 0; i < 32; i++) {
				pair.sender.sendAsync(new RTCDataChannelBuffer(ByteBuffer.wrap(new byte[MESSAGE_SIZE]), true),
						new RTCDataChannelSendObserver() {
							@Override
							public void onSuccess() {
								completed.countDown();
							}

							@Override
							public void onFailure(String error) { }
						});
			}

			drain.await(32L * MESSAGE_SIZE);
			pair.awaitSenderEvents();

			assertTrue(completed.await(5, TimeUnit.SECONDS), "sends failed");
			assertEquals(1, drain.lows.size(), "lows: " + drain.lows);
		}
	}

	@Test
	void notLowWhenNeverAbove() throws Exception {
		try (TestDataChannelPair pair = new TestDataChannelPair(factory)) {
			pair.connect();

			Drain drain = new Drain(pair.sender);
			pair.sender.setBufferedAmountLowThreshold(16L * MESSAGE_SIZE);
			pair.sender.registerObserver(drain);

			send(pair.sender, 4);
			drain.await(4L * MESSAGE_SIZE);
			pair.awaitSenderEvents();

			assertTrue(drain.lows.isEmpty(), "lows: " + drain.lows);
		}
	}

	private static void send(RTCDataChannel channel, int messages) throws Exception {
		for (int i = 0; i < messages; i++) {
			channel.send(new RTCDataChannelBuffer(ByteBuffer.wrap(new byte[MESSAGE_SIZE]), true));
		}
	}


	/**
	 * Counts what has drained, and records the buffered amount at each low.
	 */
	private static class Drain implements RTCDataChannelObserver {

		final List<Long> lows = new CopyOnWriteArrayList<>();

		private final RTCDataChannel channel;
		private final AtomicLong drained = new AtomicLong();
		private final CountDownLatch empty = new CountDownLatch(1);
		private volatile long expected = Long.MAX_VALUE;


		Drain(RTCDataChannel channel) {
			this.channel = channel;
		}

		void await(long bytes) throws InterruptedException {
			expected = bytes;

			if (drained.get() >= bytes) {
				empty.countDown();
			}

			assertTrue(empty.await(10, TimeUnit.SECONDS), "drained " + drained.get() + " of " + bytes + " bytes");
		}

		@Override
		public void onBufferedAmountChange(long sentDataSize) {
			if (drained.addAndGet(sentDataSize) >= expected) {
				empty.countDown();
			}
		}

		@Override
		public void onBufferedAmountLow() {
			lows.add(channel.getBufferedAmount());
		}

		@Override
		public void onStateChange() { }

		@Override
		public void onMessage(RTCDataChannelBuffer buffer) { }
	}

}
