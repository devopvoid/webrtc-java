package dev.onvoid.webrtc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

class RTCDataChannelSendRejectionTests extends TestBase {

	@Test
	void rejectsHeapBufferBeforeOpen() {
		assertRejected(ByteBuffer.allocate(8), false);
	}

	@Test
	void rejectsDirectBufferBeforeOpen() {
		assertRejected(ByteBuffer.allocateDirect(8), false);
	}

	@Test
	void rejectsHeapBufferAfterClose() {
		assertRejected(ByteBuffer.allocate(8), true);
	}

	@Test
	void rejectsDirectBufferAfterClose() {
		assertRejected(ByteBuffer.allocateDirect(8), true);
	}

	private void assertRejected(ByteBuffer data, boolean closed) {
		RTCPeerConnection peer = factory.createPeerConnection(
				new RTCConfiguration(), candidate -> { });
		RTCDataChannel channel = peer.createDataChannel("send", new RTCDataChannelInit());

		try {
			if (closed) {
				channel.close();
			}
			assertEquals(closed ? RTCDataChannelState.CLOSED : RTCDataChannelState.CONNECTING,
					channel.getState());
			RuntimeException error = assertThrows(RuntimeException.class,
					() -> channel.send(new RTCDataChannelBuffer(data, true)));
			assertEquals("Data channel rejected the send", error.getMessage());
		}
		finally {
			peer.close();
			channel.dispose();
		}
	}
}
