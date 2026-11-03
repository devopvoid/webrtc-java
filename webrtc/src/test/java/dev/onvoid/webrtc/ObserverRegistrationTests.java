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

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.onvoid.webrtc.media.MediaType;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Registering an observer on one thread while another thread registers or
 * disposes. A sender that has sent its first packet hands a new observer that
 * packet's notification while it is being set, on the signaling thread, so an
 * observer that blocks there holds the registration in the middle.
 */
class ObserverRegistrationTests extends TestBase {

	@Test
	void setObserverDuringDispose() throws Exception {
		try (TestMediaCall call = new TestMediaCall(factory, false, true)) {
			RTCRtpSender sender = startCall(call);
			BlockingObserver blocking = new BlockingObserver();
			WeakReference<RTCRtpSenderObserver> ref = setInThread(sender, blocking);

			blocking.entered.await();

			// The registration is still under way. Before, the dispose freed
			// the observer set before, and the registration then stored the
			// new one where nothing would ever free it: it stayed registered
			// on the sender, which lives on with its transceiver.
			sender.dispose();

			blocking.resume.countDown();
			blocking.thread.join();
			blocking = null;

			assertReleased(ref);
		}
	}

	@Test
	void setObserverWhileAnotherIsSet() throws Exception {
		try (TestMediaCall call = new TestMediaCall(factory, false, true)) {
			RTCRtpSender sender = startCall(call);
			BlockingObserver blocking = new BlockingObserver();
			WeakReference<RTCRtpSenderObserver> ref = setInThread(sender, blocking);

			blocking.entered.await();

			// Hands its observer to the call under way, which sets it once its
			// own one is set.
			CountDownLatch latest = new CountDownLatch(1);
			sender.setObserver(mediaType -> latest.countDown());

			blocking.resume.countDown();
			blocking.thread.join();
			blocking = null;

			assertTrue(latest.await(10, TimeUnit.SECONDS), "Latest observer not set");

			// Replaced, and so freed.
			assertReleased(ref);

			sender.dispose();
		}
	}

	private static RTCRtpSender startCall(TestMediaCall call) throws Exception {
		call.negotiate();
		call.awaitConnected();
		call.startMedia();

		// An instance of this test's own, the call disposes of its own one.
		RTCRtpSender sender = call.getCallerSenders()[0];

		CountDownLatch sent = new CountDownLatch(1);
		sender.setObserver(mediaType -> sent.countDown());

		assertTrue(sent.await(10, TimeUnit.SECONDS), "No packet sent");

		return sender;
	}

	/**
	 * Sets an observer, which blocks in its first notification, on a thread
	 * of its own. Only the native observer keeps it alive afterwards.
	 */
	private static WeakReference<RTCRtpSenderObserver> setInThread(RTCRtpSender sender,
			BlockingObserver blocking) {
		RTCRtpSenderObserver observer = new RTCRtpSenderObserver() {

			@Override
			public void onFirstPacketSent(MediaType mediaType) {
				blocking.entered.countDown();

				try {
					blocking.resume.await();
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		};

		blocking.thread = new Thread(() -> sender.setObserver(observer), "set-observer");
		blocking.thread.start();

		return new WeakReference<>(observer);
	}

	private static void assertReleased(WeakReference<?> ref) {
		for (int i = 0; i < 50 && ref.get() != null; i++) {
			System.gc();
		}

		assertTrue(ref.get() == null, "Observer still referenced");
	}



	private static class BlockingObserver {

		final CountDownLatch entered = new CountDownLatch(1);

		final CountDownLatch resume = new CountDownLatch(1);

		Thread thread;

	}

}
