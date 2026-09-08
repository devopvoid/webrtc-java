package dev.onvoid.webrtc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class RTCDataChannelBufferWindowTests extends TestBase {

	@Test
	void synchronousBufferWindows() throws Exception {
		assertBufferWindows(false);
	}

	@Test
	void asynchronousBufferWindows() throws Exception {
		assertBufferWindows(true);
	}

	private void assertBufferWindows(boolean asynchronous) throws Exception {
		try (TestDataChannelPair pair = new TestDataChannelPair(factory)) {
			pair.connect();
			for (boolean direct : new boolean[] { false, true }) {
				for (int layout = 0; layout < 5; layout++) {
					ByteBuffer storage = direct ? ByteBuffer.allocateDirect(16) : ByteBuffer.allocate(16);
					for (int i = 0; i < storage.capacity(); i++) {
						storage.put(i, (byte) (i + 1));
					}
					ByteBuffer data = storage.duplicate();
					if (layout != 0) {
						data.position(3);
						data.limit(layout == 4 ? 3 : 12);
					}
					if (layout == 2) {
						data = data.slice();
					}
					if (layout == 3) {
						data = data.asReadOnlyBuffer();
					}
					assertWindow(pair, data, storage, asynchronous);
				}
				ByteBuffer empty = direct ? ByteBuffer.allocateDirect(0) : ByteBuffer.allocate(0);
				assertWindow(pair, empty, empty, asynchronous);
			}
		}
	}

	private static void assertWindow(TestDataChannelPair pair, ByteBuffer data,
			ByteBuffer storage, boolean asynchronous) throws Exception {
		int position = data.position();
		int limit = data.limit();
		byte[] expected = new byte[data.remaining()];
		data.duplicate().get(expected);
		RTCDataChannelBuffer buffer = new RTCDataChannelBuffer(data, true);
		if (asynchronous) {
			pair.sender.sendAsync(buffer);
		}
		else {
			pair.sender.send(buffer);
		}
		assertEquals(position, data.position());
		assertEquals(limit, data.limit());
		// The native send must own its bytes before the caller reuses the buffer.
		for (int i = 0; i < storage.capacity(); i++) {
			storage.put(i, (byte) 0);
		}
		RTCDataChannelBuffer received = pair.messages.poll(5, TimeUnit.SECONDS);
		assertNotNull(received, "Message was not received");
		assertTrue(received.binary);
		byte[] actual = new byte[received.data.remaining()];
		received.data.get(actual);
		assertArrayEquals(expected, actual);
	}
}
