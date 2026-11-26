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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests the threshold at which the buffered amount of a data channel counts
 * as low, and the event that tells of it.
 */
@Execution(ExecutionMode.SAME_THREAD)
class RTCDataChannelBufferedAmountLowTests extends TestBase {

	/*
	 * Each message raises the buffered amount above the threshold in one step,
	 * before any of it can drain, however fast the machine sends. Messages of
	 * many small sends would let the buffer drain meanwhile, and cross the
	 * threshold any number of times.
	 */
	private static final int MESSAGE_SIZE = 200 * 1024;

	private static final long THRESHOLD = 100 * 1024;


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

			pair.sender.send(message());
			drain.await(MESSAGE_SIZE);
			// The low comes right after the decrease that emptied the buffer.
			pair.awaitSenderEvents();

			assertEquals(List.of(0L), drain.lows);
		}
	}

	@Test
	void lowAtThreshold() throws Exception {
		try (TestDataChannelPair pair = new TestDataChannelPair(factory)) {
			pair.connect();

			Drain drain = new Drain(pair.sender);
			pair.sender.setBufferedAmountLowThreshold(THRESHOLD);
			pair.sender.registerObserver(drain);

			pair.sender.send(message());
			drain.await(MESSAGE_SIZE);
			pair.awaitSenderEvents();

			// Falling through the threshold once, whatever steps it fell in.
			assertEquals(1, drain.lows.size(), "lows: " + drain.lows);
			assertTrue(drain.lows.get(0) <= THRESHOLD, "low at " + drain.lows.get(0));
		}
	}

	@Test
	void lowAgainAfterRefill() throws Exception {
		try (TestDataChannelPair pair = new TestDataChannelPair(factory)) {
			pair.connect();

			Drain drain = new Drain(pair.sender);
			pair.sender.setBufferedAmountLowThreshold(THRESHOLD);
			pair.sender.registerObserver(drain);

			pair.sender.send(message());
			drain.await(MESSAGE_SIZE);
			pair.awaitSenderEvents();

			assertEquals(1, drain.lows.size(), "lows: " + drain.lows);

			// Above the threshold again, and below it once more.
			pair.sender.send(message());
			drain.await(2L * MESSAGE_SIZE);
			pair.awaitSenderEvents();

			assertEquals(2, drain.lows.size(), "lows: " + drain.lows);
		}
	}

	@Test
	void asyncSendsCounted() throws Exception {
		try (TestDataChannelPair pair = new TestDataChannelPair(factory)) {
			// Refused before the channel is open, so never counted. Were it
			// counted, the amount would never fall to the threshold.
			pair.sender.sendAsync(message());

			pair.connect();

			Drain drain = new Drain(pair.sender);
			pair.sender.setBufferedAmountLowThreshold(THRESHOLD);
			pair.sender.registerObserver(drain);

			CountDownLatch completed = new CountDownLatch(1);

			pair.sender.sendAsync(message(), new RTCDataChannelSendObserver() {
				@Override
				public void onSuccess() {
					completed.countDown();
				}

				@Override
				public void onFailure(String error) { }
			});

			assertTrue(completed.await(5, TimeUnit.SECONDS), "send failed");

			drain.await(MESSAGE_SIZE);
			pair.awaitSenderEvents();

			assertEquals(1, drain.lows.size(), "lows: " + drain.lows);
		}
	}

	@Test
	void notLowWhenNeverAbove() throws Exception {
		try (TestDataChannelPair pair = new TestDataChannelPair(factory)) {
			pair.connect();

			Drain drain = new Drain(pair.sender);
			pair.sender.setBufferedAmountLowThreshold(2L * MESSAGE_SIZE);
			pair.sender.registerObserver(drain);

			pair.sender.send(message());
			drain.await(MESSAGE_SIZE);
			pair.awaitSenderEvents();

			assertTrue(drain.lows.isEmpty(), "lows: " + drain.lows);
		}
	}

	private static RTCDataChannelBuffer message() {
		return new RTCDataChannelBuffer(ByteBuffer.wrap(new byte[MESSAGE_SIZE]), true);
	}


	/**
	 * Counts what has drained, and records the buffered amount at each low.
	 */
	private static class Drain implements RTCDataChannelObserver {

		final List<Long> lows = new CopyOnWriteArrayList<>();

		private final RTCDataChannel channel;
		private long drained;


		Drain(RTCDataChannel channel) {
			this.channel = channel;
		}

		/** Waits until the decreases told of add up to the given bytes. */
		synchronized void await(long bytes) throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

			while (drained < bytes) {
				long left = deadline - System.nanoTime();

				assertTrue(left > 0, "drained " + drained + " of " + bytes + " bytes");

				TimeUnit.NANOSECONDS.timedWait(this, left);
			}
		}

		@Override
		public synchronized void onBufferedAmountChange(long sentDataSize) {
			drained += sentDataSize;
			notifyAll();
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
