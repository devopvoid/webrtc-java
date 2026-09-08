package dev.onvoid.webrtc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class RTCDataChannelSendCompletionTests extends TestBase {

	@Test
	void acceptsHeapBufferWindows() throws Exception {
		assertAccepted(false);
	}

	@Test
	void acceptsDirectBufferWindows() throws Exception {
		assertAccepted(true);
	}

	@Test
	void reportsDiscardedSendsBeforeTransportExists() throws Exception {
		try (TestDataChannelPair pair = new TestDataChannelPair(factory)) {
			assertRejected(pair.sender);
		}
	}

	@Test
	void reportsNativeRejectionAfterClose() throws Exception {
		try (TestDataChannelPair pair = new TestDataChannelPair(factory)) {
			pair.connect();
			pair.sender.close();
			assertRejected(pair.sender);
		}
	}

	@Test
	void rejectsInvalidArgumentsWithoutCallingObserver() {
		SendResult result = new SendResult();
		try (TestDataChannelPair pair = new TestDataChannelPair(factory)) {
			assertThrows(NullPointerException.class, () -> pair.sender.sendAsync(null, result));
			assertThrows(NullPointerException.class,
					() -> pair.sender.sendAsync(new RTCDataChannelBuffer(null, true), result));
			assertThrows(NullPointerException.class,
					() -> pair.sender.sendAsync(new RTCDataChannelBuffer(ByteBuffer.allocate(1), true), null));
		}
		assertEquals(0, result.calls.get());
	}

	@Test
	void callbackExceptionDoesNotStopLaterSends() throws Exception {
		try (TestDataChannelPair pair = new TestDataChannelPair(factory)) {
			pair.connect();
			CountDownLatch entered = new CountDownLatch(1);
			RTCDataChannelBuffer buffer = new RTCDataChannelBuffer(ByteBuffer.allocate(1), true);
			pair.sender.sendAsync(buffer, new RTCDataChannelSendObserver() {
				@Override
				public void onSuccess() {
					entered.countDown();
					throw new IllegalStateException("Send observer test exception");
				}

				@Override
				public void onFailure(String error) {
					entered.countDown();
					throw new IllegalStateException(error);
				}
			});
			assertTrue(entered.await(5, TimeUnit.SECONDS), "Observer was not called");
			SendResult next = new SendResult();
			pair.sender.sendAsync(buffer, next);
			assertNull(next.result.get(5, TimeUnit.SECONDS));
			assertEquals(1, next.calls.get());
		}
	}

	@Test
	void failureCallbackExceptionDoesNotStopLaterSends() throws Exception {
		try (TestDataChannelPair pair = new TestDataChannelPair(factory)) {
			CountDownLatch entered = new CountDownLatch(1);
			RTCDataChannelBuffer buffer = new RTCDataChannelBuffer(ByteBuffer.allocate(1), true);
			pair.sender.sendAsync(buffer, new RTCDataChannelSendObserver() {
				@Override
				public void onSuccess() {
					entered.countDown();
					throw new IllegalStateException("Unexpected send success");
				}

				@Override
				public void onFailure(String error) {
					entered.countDown();
					throw new IllegalStateException("Send failure observer test exception");
				}
			});
			assertTrue(entered.await(5, TimeUnit.SECONDS), "Observer was not called");
			assertRejected(pair.sender);
		}
	}

	@Test
	void pendingSendsCompleteOnceWhenPeerCloses() throws Exception {
		RTCPeerConnection peer = factory.createPeerConnection(new RTCConfiguration(), candidate -> { });
		RTCDataChannel channel = peer.createDataChannel("send", new RTCDataChannelInit());
		SendResult[] results = new SendResult[64];
		try {
			try {
				for (int i = 0; i < results.length; i++) {
					results[i] = new SendResult();
					channel.sendAsync(new RTCDataChannelBuffer(ByteBuffer.allocateDirect(1), true), results[i]);
				}
			}
			finally {
				peer.close();
			}
			for (SendResult result : results) {
				assertNotNull(result.result.get(5, TimeUnit.SECONDS));
				assertEquals(1, result.calls.get());
			}
		}
		finally {
			channel.dispose();
		}
	}

	private void assertAccepted(boolean direct) throws Exception {
		try (TestDataChannelPair pair = new TestDataChannelPair(factory)) {
			pair.connect();
			for (boolean readOnly : new boolean[] { false, true }) {
				ByteBuffer storage = direct ? ByteBuffer.allocateDirect(8) : ByteBuffer.allocate(8);
				storage.put(new byte[] { 99, 10, 20, 30, 40, 50, 88, 77 });
				ByteBuffer window = storage.duplicate();
				window.position(1);
				window.limit(6);
				if (readOnly) {
					window = window.asReadOnlyBuffer();
				}
				SendResult result = new SendResult();
				pair.sender.sendAsync(new RTCDataChannelBuffer(window, true), result);
				assertEquals(1, window.position());
				assertEquals(6, window.limit());
				for (int i = 0; i < storage.capacity(); i++) {
					storage.put(i, (byte) 0);
				}
				assertNull(result.result.get(5, TimeUnit.SECONDS));
				assertEquals(1, result.calls.get());
				RTCDataChannelBuffer received = pair.messages.poll(5, TimeUnit.SECONDS);
				assertNotNull(received);
				assertTrue(received.binary);
				assertArrayEquals(new byte[] { 10, 20, 30, 40, 50 }, received.data.array());
			}
			SendResult empty = new SendResult();
			ByteBuffer buffer = direct ? ByteBuffer.allocateDirect(0) : ByteBuffer.allocate(0);
			pair.sender.sendAsync(new RTCDataChannelBuffer(buffer, false), empty);
			assertNull(empty.result.get(5, TimeUnit.SECONDS));
			assertEquals(1, empty.calls.get());
			RTCDataChannelBuffer received = pair.messages.poll(5, TimeUnit.SECONDS);
			assertNotNull(received);
			assertEquals(false, received.binary);
			assertEquals(0, received.data.remaining());
		}
	}

	private static void assertRejected(RTCDataChannel channel) throws Exception {
		for (ByteBuffer buffer : new ByteBuffer[] { ByteBuffer.allocate(1), ByteBuffer.allocateDirect(1) }) {
			SendResult result = new SendResult();
			channel.sendAsync(new RTCDataChannelBuffer(buffer, true), result);
			String error = result.result.get(5, TimeUnit.SECONDS);
			assertNotNull(error);
			assertTrue(error.startsWith("[INVALID_STATE]"), error);
			assertEquals(1, result.calls.get());
		}
	}

	private static class SendResult implements RTCDataChannelSendObserver {
		final CompletableFuture<String> result = new CompletableFuture<>();
		final AtomicInteger calls = new AtomicInteger();

		@Override
		public void onSuccess() {
			calls.incrementAndGet();
			result.complete(null);
		}

		@Override
		public void onFailure(String error) {
			calls.incrementAndGet();
			result.complete(error);
		}
	}
}
