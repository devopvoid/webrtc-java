package dev.onvoid.webrtc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

class TestDataChannelPair implements AutoCloseable {

	final RTCDataChannel sender;
	final RTCDataChannel receiver;
	final BlockingQueue<RTCDataChannelBuffer> messages = new LinkedBlockingQueue<>();

	private final RTCPeerConnection caller;
	private final RTCPeerConnection callee;
	private final CountDownLatch open = new CountDownLatch(2);

	TestDataChannelPair(PeerConnectionFactory factory) {
		RTCPeerConnection[] peers = new RTCPeerConnection[2];
		caller = factory.createPeerConnection(new RTCConfiguration(),
				candidate -> peers[1].addIceCandidate(candidate));
		callee = factory.createPeerConnection(new RTCConfiguration(),
				candidate -> peers[0].addIceCandidate(candidate));
		peers[0] = caller;
		peers[1] = callee;

		RTCDataChannelInit config = new RTCDataChannelInit();
		config.negotiated = true;
		config.id = 0;
		sender = caller.createDataChannel("send", config);
		receiver = callee.createDataChannel("send", config);
		sender.registerObserver(observer(sender));
		receiver.registerObserver(observer(receiver));
	}

	void connect() throws Exception {
		TestCreateDescObserver offer = new TestCreateDescObserver();
		caller.createOffer(new RTCOfferOptions(), offer);
		RTCSessionDescription offerDescription = offer.get(5, TimeUnit.SECONDS);
		setDescription(caller, offerDescription, true);
		setDescription(callee, offerDescription, false);

		TestCreateDescObserver answer = new TestCreateDescObserver();
		callee.createAnswer(new RTCAnswerOptions(), answer);
		RTCSessionDescription answerDescription = answer.get(5, TimeUnit.SECONDS);
		setDescription(callee, answerDescription, true);
		setDescription(caller, answerDescription, false);
		assertTrue(open.await(5, TimeUnit.SECONDS), "Data channels did not open");
	}

	private static void setDescription(RTCPeerConnection peer,
			RTCSessionDescription description, boolean local) throws Exception {
		TestSetDescObserver observer = new TestSetDescObserver();
		if (local) {
			peer.setLocalDescription(description, observer);
		}
		else {
			peer.setRemoteDescription(description, observer);
		}
		observer.get(5, TimeUnit.SECONDS);
	}

	private RTCDataChannelObserver observer(RTCDataChannel channel) {
		return new RTCDataChannelObserver() {
			@Override
			public void onBufferedAmountChange(long sentDataSize) { }

			@Override
			public void onStateChange() {
				if (channel.getState() == RTCDataChannelState.OPEN) {
					open.countDown();
				}
			}

			@Override
			public void onMessage(RTCDataChannelBuffer buffer) {
				byte[] bytes = new byte[buffer.data.remaining()];
				buffer.data.duplicate().get(bytes);
				messages.add(new RTCDataChannelBuffer(ByteBuffer.wrap(bytes), buffer.binary));
			}
		};
	}

	@Override
	public void close() {
		sender.unregisterObserver();
		receiver.unregisterObserver();
		caller.close();
		callee.close();
		sender.dispose();
		receiver.dispose();
	}
}
