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

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCPeerConnectionState;

class PeerRecoveryTests extends ClientTestBase {

	@Test
	void restartKeepsDataChannel() throws Exception {
		CompletableFuture<RecoveryEvent> recovered = new CompletableFuture<>();
		RecoveryPolicy policy = RecoveryPolicy.builder().onEvent(event -> {
			if (event.state() == RecoveryEvent.State.RECOVERED) recovered.complete(event);
		}).build();
		AtomicReference<Peer> calleeRef = new AtomicReference<>();
		CompletableFuture<Void> answeredRestart = new CompletableFuture<>();
		AtomicInteger answers = new AtomicInteger();
		PeerImpl caller = (PeerImpl) rtc.createPeer(PeerOptions.builder().recovery(policy)
				.signaling(signal -> calleeRef.get().signal(signal)).build());
		Peer callee = rtc.createPeer(PeerOptions.builder().polite(true).signaling(signal -> {
			caller.signal(signal);
			if (signal.type() == SignalMessage.Type.ANSWER && answers.incrementAndGet() == 2) {
				answeredRestart.complete(null);
			}
		}).build());
		calleeRef.set(callee);
		CompletableFuture<DataChannel> remote = new CompletableFuture<>();
		callee.onChannel(remote::complete);
		DataChannel channel = caller.createChannel("survives-recovery");
		await(channel.whenOpen());
		DataChannel original = await(remote);
		CompletableFuture<String> message = new CompletableFuture<>();
		original.onMessage(value -> message.complete(value.text()));
		String before = await(caller.withNative(c -> HttpIce.Fragment.of(c.getLocalDescription().sdp).ufrag));
		caller.control.execute(() -> caller.connectionChanged(RTCPeerConnectionState.FAILED));
		await(answeredRestart);
		// The native connection stayed healthy in this test. Simulate the matching
		// CONNECTED notification after the real restart's answer has been applied.
		await(caller.withNative(c -> c.getSignalingState()));
		caller.control.execute(() -> caller.connectionChanged(RTCPeerConnectionState.CONNECTED));
		assertEquals(1, await(recovered).attempt());
		assertNotEquals(before, await(caller.withNative(c -> HttpIce.Fragment.of(c.getLocalDescription().sdp).ufrag)));
		await(channel.send("after-recovery"));
		assertEquals("after-recovery", await(message));
		assertEquals(ChannelState.OPEN, original.state());
	}

	@Test
	void refreshesServersOnEventExecutor() throws Exception {
		CompletableFuture<RecoveryEvent> exhausted = new CompletableFuture<>();
		CompletableFuture<Void> requested = new CompletableFuture<>();
		CompletableFuture<java.util.List<RTCIceServer>> credentials = new CompletableFuture<>();
		AtomicInteger calls = new AtomicInteger();
		ManualTimer timer = new ManualTimer();
		WebRtc client = WebRtc.builder().audio(AudioMode.HEADLESS).timer(timer).errorHandler(errors::add).build();
		try {
			RecoveryPolicy policy = RecoveryPolicy.builder().maxAttempts(1)
					.iceServers(attempt -> {
						assertTrue(SerialExecutor.onEventThread());
						assertEquals(1, attempt);
						calls.incrementAndGet();
						requested.complete(null);
						return credentials;
					}).onEvent(event -> {
						assertTrue(SerialExecutor.onEventThread());
						if (event.state() == RecoveryEvent.State.EXHAUSTED) exhausted.complete(event);
					}).attemptTimeout(Duration.ofSeconds(15)).build();
			PeerImpl peer = (PeerImpl) client.createPeer(PeerOptions.builder().signaling(message -> { })
					.recovery(policy).build());
			peer.control.execute(() -> peer.connectionChanged(RTCPeerConnectionState.FAILED));
			await(requested);
			RTCIceServer turn = new RTCIceServer();
			turn.urls.add("turn:127.0.0.1:3478");
			turn.username = "refreshed";
			turn.password = "secret";
			// Complete on events, behind the provider; its callback posts to control.
			CompletableFuture<Void> refreshed = new CompletableFuture<>();
			peer.events.execute(() -> {
				credentials.complete(Collections.singletonList(turn));
				peer.control.execute(() -> refreshed.complete(null));
			});
			await(refreshed);
			assertEquals("refreshed", await(peer.withNative(c -> c.getConfiguration().iceServers.get(0).username)));
			assertEquals(1, timer.fire(Duration.ofSeconds(15)));
			assertEquals(1, await(exhausted).attempt());
			assertEquals(1, calls.get());
			await(peer.closeAsync());
		}
		finally { client.close(); }
	}

	@Test
	void closeIgnoresPendingCredentials() throws Exception {
		CompletableFuture<java.util.List<RTCIceServer>> credentials = new CompletableFuture<>();
		CompletableFuture<Void> requested = new CompletableFuture<>();
		RecoveryPolicy policy = RecoveryPolicy.builder().iceServers(attempt -> {
			requested.complete(null);
			return credentials;
		}).build();
		PeerImpl peer = (PeerImpl) rtc.createPeer(PeerOptions.builder().signaling(message -> { })
				.recovery(policy).build());
		peer.control.execute(() -> peer.connectionChanged(RTCPeerConnectionState.FAILED));
		await(requested);
		await(peer.closeAsync());
		credentials.complete(Collections.emptyList());
		CompletableFuture<Void> drained = new CompletableFuture<>();
		peer.control.execute(() -> drained.complete(null));
		await(drained);
		assertEquals(PeerState.CLOSED, peer.state());
	}
}
