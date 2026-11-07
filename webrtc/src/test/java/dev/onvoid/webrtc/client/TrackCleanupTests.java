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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.RTCRtpSender;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCRtpTransceiverInit;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.MediaStreamTrackMuteListener;
import dev.onvoid.webrtc.media.MediaType;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import dev.onvoid.webrtc.media.audio.VoiceActivityListener;

import org.junit.jupiter.api.Test;

class TrackCleanupTests extends ClientTestBase {

	@Test
	void endedSubscriptionClosesOnce() throws Exception {
		LocalVideo track = rtc.media().customVideo("ended");
		AtomicInteger calls = new AtomicInteger();
		Runnable handler = calls::incrementAndGet;
		Subscription first = track.onEnded(handler);
		Subscription second = track.onEnded(handler);

		first.close();
		first.close();
		await(track.closeAsync());
		assertEquals(1, calls.get());
		second.close();
	}

	@Test
	void failedDetachKeepsTrack() throws Exception {
		LocalMediaImpl media = (LocalMediaImpl) rtc.media();
		TestTrack nativeTrack = new TestTrack();
		AtomicBoolean sourceReleased = new AtomicBoolean();
		LocalTrackImpl track = media.register(new LocalTrackImpl(media, nativeTrack, MediaKind.VIDEO,
				() -> sourceReleased.set(true)) { });
		PeerImpl peer = (PeerImpl) rtc.createPeer(manualOptions());
		SenderImpl sender = new SenderImpl(peer, track);
		track.register(sender);

		// An unbound sender fails in JNI before SetTrack can detach anything.
		Constructor<RTCRtpSender> constructor = RTCRtpSender.class.getDeclaredConstructor();
		constructor.setAccessible(true);
		field(SenderImpl.class, "sender").set(sender, constructor.newInstance());

		ExecutionException error = assertThrows(ExecutionException.class, () -> await(track.closeAsync()));
		assertNotNull(error.getCause());
		assertSame(track, sender.track());
		assertTrue(track.registeredSenders().contains(sender));
		assertEquals(0, nativeTrack.disposals);
		assertFalse(sourceReleased.get());
		assertRuntimeFailure();
	}

	@Test
	void rejectedDetachCompletes() throws Exception {
		ManualExecutor control = new ManualExecutor();
		WebRtc runtime = WebRtc.builder().audio(AudioMode.HEADLESS)
				.controlPool(control).errorHandler(errors::add).build();
		PeerImpl peer = runtime.register(new PeerImpl(runtime, manualOptions(), "rejected"));
		LocalTrackImpl track = (LocalTrackImpl) runtime.media().customVideo("rejected");
		SenderImpl sender = new SenderImpl(peer, track);
		try {
			control.rejecting(true);
			assertThrows(ExecutionException.class, () -> await(sender.trackClosed(track)));
			assertSame(track, sender.track());
		}
		finally {
			control.rejecting(false);
			HttpSessionTests.closeStepping(runtime, control);
			errors.clear();
		}
	}

	@Test
	void voiceCleanupContinues() throws Exception {
		LocalMediaImpl media = (LocalMediaImpl) rtc.media();
		CustomAudioSource source = new CustomAudioSource();
		AtomicInteger detached = new AtomicInteger();
		AtomicBoolean released = new AtomicBoolean();
		IllegalStateException first = new IllegalStateException("first");
		IllegalStateException second = new IllegalStateException("second");
		LocalAudioImpl track = media.register(new LocalAudioImpl(media,
				rtc.factory.createAudioTrack("voice", source), () -> {
					source.dispose();
					released.set(true);
				}, false) {
			@Override
			void detach(VoiceActivityListener listener) {
				super.detach(listener);
				throw detached.incrementAndGet() == 1 ? first : second;
			}
		});
		track.onVoiceActivity(ignored -> { });
		track.onVoiceActivity(ignored -> { });

		assertThrows(ExecutionException.class, () -> await(track.closeAsync()));
		assertEquals(2, detached.get());
		assertArrayEquals(new Throwable[] { second }, first.getSuppressed());
		assertTrue(released.get());
		assertRuntimeFailure();
	}

	@Test
	void remoteCleanupContinues() throws Exception {
		remoteCleanup(false);
	}

	@Test
	void removedTrackFailureSurvives() throws Exception {
		remoteCleanup(true);
	}

	private void remoteCleanup(boolean removeFirst) throws Exception {
		PeerImpl peer = (PeerImpl) rtc.createPeer(manualOptions());
		RTCRtpReceiver receiver = await(peer.withNative(connection -> {
			RTCRtpTransceiver transceiver = connection.addTransceiver(MediaType.VIDEO, new RTCRtpTransceiverInit());
			try {
				return transceiver.getReceiver();
			}
			finally {
				transceiver.dispose();
			}
		}));
		TestTrack track = new TestTrack();
		IllegalStateException sinks = new IllegalStateException("sinks");
		track.muteFailure = new IllegalStateException("mute");
		track.disposeFailure = new IllegalStateException("dispose");
		RemoteTrackImpl remote = new RemoteTrackImpl(peer, receiver, track, MediaKind.VIDEO,
				"remote", Collections.emptyList()) {
			@Override
			void detachSinks() {
				throw sinks;
			}
		};
		RemoteTracks tracks = (RemoteTracks) field(PeerImpl.class, "tracks").get(peer);
		@SuppressWarnings("unchecked")
		List<RemoteTrackImpl> current = (List<RemoteTrackImpl>) field(RemoteTracks.class, "tracks").get(tracks);
		current.add(remote);
		if (removeFirst) {
			// A removal must retain the failure even after both public track
			// lists have forgotten the track.
			@SuppressWarnings("unchecked")
			Map<String, RemoteTrackImpl> byId = (Map<String, RemoteTrackImpl>)
					field(RemoteTracks.class, "byId").get(tracks);
			await(peer.withNative(connection -> {
				MediaStreamTrack view = receiver.getTrack();
				String id = view.getId();
				view.dispose();
				byId.put(id, remote);
				tracks.removed(connection.getReceivers()[0]);
				return null;
			}));
			assertTrue(peer.remoteTracks().isEmpty());
		}

		assertThrows(ExecutionException.class, () -> await(peer.closeAsync()));
		assertEquals(1, track.muteRemovals);
		assertEquals(1, track.disposals);
		assertArrayEquals(new Throwable[] { track.muteFailure, track.disposeFailure }, sinks.getSuppressed());
		assertThrows(IllegalStateException.class, receiver::getId);
		assertTrue(rtc.peers().contains(peer));
		assertRuntimeFailure();
	}

	private void assertRuntimeFailure() throws Exception {
		ExecutionException error = assertThrows(ExecutionException.class, () -> await(rtc.closeAsync()));
		assertTrue(error.getCause().getMessage().contains("left undisposed"));
		assertEquals(2, errors.size(), () -> "Errors: " + errors);
		errors.clear();
	}

	private static PeerOptions manualOptions() {
		return PeerOptions.builder().negotiation(Negotiation.MANUAL).build();
	}

	private static Field field(Class<?> type, String name) throws Exception {
		Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}

	private static final class TestTrack extends MediaStreamTrack {
		int disposals;
		int muteRemovals;
		RuntimeException muteFailure;
		RuntimeException disposeFailure;

		@Override
		public String getId() {
			return "test";
		}

		@Override
		public void addTrackMuteListener(MediaStreamTrackMuteListener listener) {
		}

		@Override
		public void removeTrackMuteListener(MediaStreamTrackMuteListener listener) {
			muteRemovals++;
			if (muteFailure != null) {
				throw muteFailure;
			}
		}

		@Override
		public void dispose() {
			disposals++;
			if (disposeFailure != null) {
				throw disposeFailure;
			}
		}
	}
}
