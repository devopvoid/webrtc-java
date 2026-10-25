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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.media.audio.AudioDevice;
import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.VideoDevice;

import org.junit.jupiter.api.Test;

/**
 * Closes runtimes at each point where it can meet another operation. The
 * runtimes run their control tasks, or their events, on a {@link
 * ManualExecutor}, so that each test decides the order rather than the
 * scheduler.
 */
class RuntimeCloseTests extends ClientTestBase {

	@Test
	void closeBeforeRegister() throws Exception {
		// createPeer checked the runtime, then the runtime began to close and
		// found no peer to wait for.
		ManualExecutor control = new ManualExecutor();
		WebRtc runtime = runtime(control);
		PeerImpl peer = new PeerImpl(runtime, manualOptions(), "late");

		CompletableFuture<Void> closed = runtime.closeAsync();

		assertThrows(PeerClosedException.class, () -> runtime.register(peer));
		assertFalse(runtime.peers().contains(peer));

		// Nothing of the peer reached the control executor, so no connection
		// was created, and the runtime closes without it.
		assertEquals(0, control.queued());
		await(closed);
	}

	@Test
	void closeBeforeOpen() throws Exception {
		// The peer is registered, and the runtime closes before the connection
		// is created.
		ManualExecutor control = new ManualExecutor();
		WebRtc runtime = runtime(control);
		PeerImpl peer = runtime.register(new PeerImpl(runtime, manualOptions(), "registered"));

		CompletableFuture<Void> closed = runtime.closeAsync();

		// The runtime found the peer and queued its teardown, and waits for it
		// before it frees the factory.
		control.awaitQueued(TIMEOUT_SECONDS);
		assertFalse(closed.isDone());

		// Whether the creation joins the teardown's turn or takes one of its
		// own, the teardown runs first.
		CompletableFuture<PeerImpl> opened = CompletableFuture.supplyAsync(() -> runtime.open(peer));
		control.runUntil(opened::isDone, TIMEOUT_SECONDS);

		ExecutionException e = assertThrows(ExecutionException.class, () -> await(opened));
		assertInstanceOf(PeerClosedException.class, e.getCause());

		control.runUntil(closed::isDone, TIMEOUT_SECONDS);
		await(closed);
		assertEquals(PeerState.CLOSED, peer.state());
		assertTrue(runtime.peers().isEmpty());
	}

	@Test
	void closeWhileOpening() throws Exception {
		// The task that creates the connection is queued when the runtime
		// begins to close.
		ManualExecutor control = new ManualExecutor();
		WebRtc runtime = runtime(control);

		CompletableFuture<Peer> created = CompletableFuture.supplyAsync(() -> runtime.createPeer(manualOptions()));
		control.awaitQueued(TIMEOUT_SECONDS);

		CompletableFuture<Void> closed = runtime.closeAsync();
		assertFalse(closed.isDone());

		control.runUntil(closed::isDone, TIMEOUT_SECONDS);
		await(closed);

		// Created first, so createPeer returns it, then closed with the runtime.
		Peer peer = await(created);
		assertEquals(PeerState.CLOSED, peer.state());
		await(peer.whenClosed());
	}

	@Test
	void closeTimeoutReported() throws Exception {
		// The peer's teardown never runs, so neither close finishes.
		ManualExecutor control = new ManualExecutor();
		WebRtc runtime = WebRtc.builder()
				.audio(AudioMode.HEADLESS)
				.errorHandler(errors::add)
				.controlPool(control)
				.closeTimeout(Duration.ofMillis(100))
				.build();
		CompletableFuture<Peer> created = CompletableFuture.supplyAsync(() -> runtime.createPeer(manualOptions()));
		control.runUntil(created::isDone, TIMEOUT_SECONDS);
		await(created);

		try {
			runtime.close();

			// The runtime cannot tell whether WebRTC still uses the factory,
			// so it leaves it. That outlasts close(), whose wait ran out first.
			ExecutionException e = assertThrows(ExecutionException.class,
					() -> runtime.closeAsync().get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

			assertEquals(2, errors.size(), () -> "Errors: " + errors);
			assertTrue(errors.stream().anyMatch(error -> error.getMessage().contains("did not close within")),
					() -> "Errors: " + errors);
			assertTrue(errors.contains(e.getCause()), () -> "Errors: " + errors);
			assertTrue(e.getCause().getMessage().contains("left undisposed"), e.getCause()::getMessage);
		}
		finally {
			// Lets the teardown close the connection.
			control.runAll();
			errors.clear();
		}
	}

	@Test
	void closeTimeoutBeyondMillis() throws Exception {
		// Longer than milliseconds can count. The waits of close take the
		// longest they can count instead of failing on the conversion.
		WebRtc runtime = WebRtc.builder()
				.audio(AudioMode.HEADLESS)
				.errorHandler(errors::add)
				.closeTimeout(Duration.ofSeconds(Long.MAX_VALUE))
				.build();
		Peer peer = runtime.createPeer(manualOptions());

		runtime.close();

		assertEquals(PeerState.CLOSED, peer.state());
		await(runtime.closeAsync());
	}

	@Test
	void closeOnEventThread() throws Exception {
		// close() does not wait for the events of the peers it closes, which
		// would wait for the very thread it runs on.
		Peer peer = rtc.createPeer(manualOptions());
		CompletableFuture<PeerState> closedState = new CompletableFuture<>();

		peer.onStateChange(state -> {
			if (state == PeerState.NEW) {
				rtc.close();
				closedState.complete(peer.state());
			}
		});

		assertEquals(PeerState.CLOSED, await(closedState));
		await(peer.whenClosed());
		// No "did not close within" either; the base class checks.
	}

	@Test
	void rejectedCloseEvents() throws Exception {
		// The event executor refuses the events that tell of the close. The
		// peer's futures settle all the same, on the thread that found them
		// refused.
		ManualExecutor events = new ManualExecutor();
		WebRtc runtime = WebRtc.builder()
				.audio(AudioMode.HEADLESS)
				.errorHandler(errors::add)
				.eventExecutor(events)
				.build();

		try {
			Peer peer = runtime.createPeer(manualOptions());
			CompletableFuture<Peer> connected = peer.whenConnected();

			events.runAll();
			events.rejecting(true);
			peer.close();

			await(peer.whenClosed());
			ExecutionException e = assertThrows(ExecutionException.class, () -> await(connected));
			assertInstanceOf(PeerClosedException.class, e.getCause());

			assertEquals(1, errors.size(), () -> "Errors: " + errors);
			assertInstanceOf(RejectedExecutionException.class, errors.get(0).getCause());
		}
		finally {
			events.rejecting(false);
			runtime.close();
			events.runAll();
			errors.clear();
		}
	}

	@Test
	void closeFailureReportedOnce() throws Exception {
		// What closing fails with reaches the error handler once, whether the
		// application waits through close() or closeAsync().
		ManualExecutor control = new ManualExecutor();
		WebRtc runtime = WebRtc.builder()
				.audio(AudioMode.HEADLESS)
				.errorHandler(errors::add)
				.controlPool(control)
				.closeTimeout(Duration.ofMillis(100))
				.build();
		CompletableFuture<Peer> created = CompletableFuture.supplyAsync(() -> runtime.createPeer(manualOptions()));
		control.runUntil(created::isDone, TIMEOUT_SECONDS);
		await(created);

		try {
			ExecutionException e = assertThrows(ExecutionException.class,
					() -> runtime.closeAsync().get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

			runtime.close();
			runtime.close();

			assertInstanceOf(WebRtcException.class, e.getCause());
			assertTrue(e.getCause().getMessage().contains("left undisposed"), e.getCause()::getMessage);
			assertEquals(1, errors.size(), () -> "Errors: " + errors);
			assertSame(e.getCause(), errors.get(0));
		}
		finally {
			control.runAll();
			errors.clear();
		}
	}

	@Test
	void closeBeforeTrackCreated() throws Exception {
		CompletableFuture<Void> closed = rtc.closeAsync();

		assertThrows(PeerClosedException.class, () -> rtc.media().customVideo("late"));
		await(closed);
	}

	@Test
	void closeWhileCreatingTrack() throws Exception {
		// The runtime begins to close while a track is created over its
		// factory, which it keeps until the track is made, and then closed.
		ManualExecutor control = new ManualExecutor();
		WebRtc runtime = runtime(control);
		LocalMediaImpl media = (LocalMediaImpl) runtime.media();
		CompletableFuture<Void> closed;
		CustomVideoImpl track;

		try (LocalMediaImpl.Creation creation = media.begin()) {
			closed = runtime.closeAsync();

			CustomVideoSource source = new CustomVideoSource();
			track = media.register(new CustomVideoImpl(media, runtime.factory.createVideoTrack("late", source),
					source));
		}

		// The release of the track, which the runtime waits for.
		control.awaitQueued(TIMEOUT_SECONDS);
		assertTrue(track.isEnded());
		assertFalse(closed.isDone());

		control.runUntil(closed::isDone, TIMEOUT_SECONDS);
		await(closed);
		await(track.closeAsync());
	}

	@Test
	void endedHandlerWaitsForClose() throws Exception {
		// Handlers of a track wait for its own close and for that of another
		// track. Both releases complete without the handlers, which run on an
		// event thread, where the waits are refused rather than hanging.
		LocalVideo first = rtc.media().customVideo("first");
		LocalVideo second = rtc.media().customVideo("second");
		CompletableFuture<Throwable> ownWait = new CompletableFuture<>();
		CompletableFuture<Throwable> otherWait = new CompletableFuture<>();

		first.onEnded(() -> ownWait.complete(waitFor(first.closeAsync())));
		first.onEnded(() -> otherWait.complete(waitFor(second.closeAsync())));
		first.close();

		assertInstanceOf(BlockingCallException.class, await(ownWait));
		assertInstanceOf(BlockingCallException.class, await(otherWait));

		await(first.closeAsync());
		await(second.closeAsync());
		assertTrue(second.isEnded());
	}

	@Test
	void cancelledTrackClose() throws Exception {
		// The application stops waiting for a track's close before the release
		// ran. The release goes on, and the runtime, which waits for it, does
		// not take the cancellation for a failure.
		ManualExecutor control = new ManualExecutor();
		WebRtc runtime = runtime(control);
		LocalVideo track = runtime.media().customVideo("cancelled");

		assertTrue(track.closeAsync().cancel(false));
		control.awaitQueued(TIMEOUT_SECONDS);

		CompletableFuture<Void> closed = runtime.closeAsync();
		control.runUntil(closed::isDone, TIMEOUT_SECONDS);

		await(closed);
		await(track.closeAsync());
	}

	@Test
	void cancelledRuntimeClose() throws Exception {
		// Each caller gets a future of its own, whose cancellation neither
		// stops the close nor reaches the others.
		assertTrue(rtc.closeAsync().cancel(false));

		rtc.close();
		await(rtc.closeAsync());
	}

	@Test
	void cancelledPeerClose() throws Exception {
		Peer peer = rtc.createPeer(manualOptions());

		assertTrue(peer.whenClosed().cancel(false));
		assertTrue(peer.closeAsync().cancel(false));

		await(peer.whenClosed());
		await(peer.closeAsync());
	}

	@Test
	void releaseFailureFailsClose() throws Exception {
		// A step of the release fails. The steps after it run all the same,
		// and the track's close fails, as does the runtime's, which leaves
		// the factory undisposed.
		WebRtc runtime = WebRtc.builder()
				.audio(AudioMode.HEADLESS)
				.errorHandler(errors::add)
				.build();
		LocalMediaImpl media = (LocalMediaImpl) runtime.media();
		IllegalStateException broken = new IllegalStateException("Broken");
		AtomicBoolean sourceReleased = new AtomicBoolean();
		LocalVideoImpl track;

		try (LocalMediaImpl.Creation creation = media.begin()) {
			CustomVideoSource source = new CustomVideoSource();
			Runnable release = () -> {
				source.dispose();
				sourceReleased.set(true);
			};

			track = media.register(new LocalVideoImpl(media, runtime.factory.createVideoTrack("broken", source),
					release) {

				@Override
				void beforeRelease() {
					throw broken;
				}
			});
		}

		try {
			ExecutionException e = assertThrows(ExecutionException.class, () -> await(track.closeAsync()));
			assertSame(broken, e.getCause().getCause());
			assertTrue(sourceReleased.get());
			assertTrue(track.isEnded());

			ExecutionException closing = assertThrows(ExecutionException.class, () -> await(runtime.closeAsync()));
			assertTrue(closing.getCause().getMessage().contains("left undisposed"), closing.getCause()::getMessage);

			assertEquals(2, errors.size(), () -> "Errors: " + errors);
			assertSame(e.getCause(), errors.get(0));
			assertSame(closing.getCause(), errors.get(1));
		}
		finally {
			errors.clear();
		}
	}

	@Test
	void closeWhileSwitchingDevice() throws Exception {
		// A speaker switch is queued when the runtime begins to close. The
		// runtime waits for it before it frees the audio module it uses.
		ManualExecutor control = new ManualExecutor();
		WebRtc runtime = runtime(control);
		LocalMediaImpl media = (LocalMediaImpl) runtime.media();
		AtomicBoolean poolStopped = new AtomicBoolean(true);

		CompletableFuture<Void> switched = media.devices().useSpeaker(speaker());
		switched.whenComplete((value, error) -> poolStopped.set(runtime.controlPool.isShutdown()));

		// Named among what the runtime waits for.
		assertEquals(1, media.closeAll().size());

		CompletableFuture<Void> closed = runtime.closeAsync();
		control.runUntil(closed::isDone, TIMEOUT_SECONDS);
		await(closed);

		// The switch ran while the runtime still had its threads, and failed
		// on its own: a headless runtime plays to no speakers.
		assertFalse(poolStopped.get());
		ExecutionException e = assertThrows(ExecutionException.class, () -> await(switched));
		assertInstanceOf(ConfigurationException.class, e.getCause());
	}

	@Test
	void switchDeviceAfterClose() throws Exception {
		Devices devices = rtc.media().devices();
		CompletableFuture<Void> closed = rtc.closeAsync();

		// Failed at once, without reaching the module the runtime frees.
		CompletableFuture<Void> switched = devices.useSpeaker(speaker());

		assertTrue(switched.isDone());
		ExecutionException e = assertThrows(ExecutionException.class, () -> await(switched));
		assertInstanceOf(PeerClosedException.class, e.getCause());
		await(closed);
	}

	@Test
	void closeWhileSwitchingCamera() throws Exception {
		// A camera switch is queued when the runtime begins to close. The
		// runtime waits for it before it frees the factory, and the switch,
		// which finds its track closed, opens no camera.
		ManualExecutor control = new ManualExecutor();
		WebRtc runtime = runtime(control);
		LocalMediaImpl media = (LocalMediaImpl) runtime.media();
		LocalCameraImpl camera = camera(runtime);

		CompletableFuture<Void> switched = camera.switchCamera(camera("Other camera"));

		// Named among what the runtime waits for, beside the track.
		assertEquals(2, media.closeAll().size());

		CompletableFuture<Void> closed = runtime.closeAsync();
		control.runUntil(closed::isDone, TIMEOUT_SECONDS);
		await(closed);

		// The switch ran while the runtime still had its threads, rather than
		// being failed by the runtime's close.
		assertTrackClosed(switched);
	}

	@Test
	void switchCameraAfterClose() throws Exception {
		LocalCameraImpl camera = camera(rtc);
		await(camera.closeAsync());

		// Failed at once, without opening a camera over the factory.
		CompletableFuture<Void> switched = camera.switchCamera(camera("Other camera"));

		assertTrue(switched.isDone());
		assertTrackClosed(switched);
	}

	@Test
	void switchCameraRejected() throws Exception {
		// The executor refuses the switch, which fails and lets go of its
		// registration, so that the runtime does not wait for it.
		ManualExecutor control = new ManualExecutor();
		WebRtc runtime = runtime(control);
		LocalMediaImpl media = (LocalMediaImpl) runtime.media();
		LocalCameraImpl camera = camera(runtime);

		try {
			control.rejecting(true);
			CompletableFuture<Void> switched = camera.switchCamera(camera("Other camera"));

			ExecutionException e = assertThrows(ExecutionException.class, () -> await(switched));
			assertInstanceOf(PeerClosedException.class, e.getCause());
			assertEquals(1, errors.size(), () -> "Errors: " + errors);
			assertInstanceOf(RejectedExecutionException.class, errors.get(0).getCause());
		}
		finally {
			control.rejecting(false);
			errors.clear();
		}

		// Only the track is left.
		assertEquals(1, media.closeAll().size());

		CompletableFuture<Void> closed = runtime.closeAsync();
		control.runUntil(closed::isDone, TIMEOUT_SECONDS);
		await(closed);
	}

	@Test
	void closedPeerNeedsNoTimer() throws Exception {
		// An operation reaches a peer the closing runtime has closed, after it
		// stopped its timer, as control tasks queued before still run.
		ManualExecutor control = new ManualExecutor();
		ManualTimer timer = new ManualTimer();
		WebRtc runtime = runtime(control, timer);
		CompletableFuture<Peer> created = CompletableFuture.supplyAsync(() -> runtime.createPeer(manualOptions()));
		control.runUntil(created::isDone, TIMEOUT_SECONDS);
		Peer peer = await(created);

		try {
			CompletableFuture<Void> peerClosed = peer.closeAsync();
			control.runUntil(peerClosed::isDone, TIMEOUT_SECONDS);

			CompletableFuture<Object> operation = peer.withNative(connection -> null);
			timer.shutdown();
			control.runUntil(operation::isDone, TIMEOUT_SECONDS);

			ExecutionException e = assertThrows(ExecutionException.class, () -> await(operation));
			assertInstanceOf(PeerClosedException.class, e.getCause());
			assertTrue(errors.isEmpty(), () -> "Errors: " + errors);
		}
		finally {
			HttpSessionTests.closeStepping(runtime, control);
		}
	}

	@Test
	void operationWithoutTimer() throws Exception {
		// A peer the runtime could not close still runs what was queued, with
		// no timer left to bound how long WebRTC or gathering takes.
		ManualExecutor control = new ManualExecutor();
		ManualTimer timer = new ManualTimer();
		WebRtc runtime = runtime(control, timer);
		CompletableFuture<Peer> created = CompletableFuture.supplyAsync(() -> runtime.createPeer(manualOptions()));
		control.runUntil(created::isDone, TIMEOUT_SECONDS);
		Peer peer = await(created);

		try {
			timer.shutdown();

			CompletableFuture<RTCSessionDescription> offer = peer.negotiator().createOffer(Gathering.COMPLETE);
			control.runUntil(offer::isDone, TIMEOUT_SECONDS);

			assertEquals(RTCSdpType.OFFER, await(offer).sdpType);
			assertTrue(errors.isEmpty(), () -> "Errors: " + errors);
		}
		finally {
			HttpSessionTests.closeStepping(runtime, control);
		}
	}

	private WebRtc runtime(ManualExecutor control, ManualTimer timer) {
		return WebRtc.builder()
				.audio(AudioMode.HEADLESS)
				.errorHandler(errors::add)
				.controlPool(control)
				.timer(timer)
				.build();
	}

	private WebRtc runtime(ManualExecutor control) {
		return WebRtc.builder()
				.audio(AudioMode.HEADLESS)
				.errorHandler(errors::add)
				.controlPool(control)
				.build();
	}

	/**
	 * A camera track over a custom source, so that no camera is needed. Its
	 * camera has another user, so that the track's release lets go of its
	 * share only, and touches no capture device.
	 */
	private static LocalCameraImpl camera(WebRtc runtime) {
		LocalMediaImpl media = (LocalMediaImpl) runtime.media();
		VideoDevice device = camera("Test camera");
		LocalMediaImpl.SharedCamera shared = new LocalMediaImpl.SharedCamera(device.getDescriptor(),
				CameraOptions.builder().device(device).build(), null);
		shared.users = 2;

		try (LocalMediaImpl.Creation creation = media.begin()) {
			CustomVideoSource source = new CustomVideoSource();
			LocalCameraImpl camera = media.register(new LocalCameraImpl(media,
					runtime.factory.createVideoTrack("camera", source), shared));
			camera.onEnded(source::dispose);

			return camera;
		}
	}

	private static VideoDevice camera(String name) {
		return new VideoDevice(name, name.toLowerCase().replace(' ', '-')) { };
	}

	private static void assertTrackClosed(CompletableFuture<Void> switched) {
		ExecutionException e = assertThrows(ExecutionException.class, () -> await(switched));
		assertInstanceOf(MediaException.class, e.getCause());
		assertEquals("The camera track is closed", e.getCause().getMessage());
	}

	/**
	 * @return what waiting for the future threw, or null.
	 */
	private static Throwable waitFor(CompletableFuture<?> future) {
		try {
			future.join();
			return null;
		}
		catch (Throwable e) {
			return e;
		}
	}

	private static AudioDevice speaker() {
		return new AudioDevice("Test speaker", "test-speaker") { };
	}

	private static PeerOptions manualOptions() {
		return PeerOptions.builder().negotiation(Negotiation.MANUAL).build();
	}

}
