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
import static dev.onvoid.webrtc.client.RecoveryEvent.State.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import dev.onvoid.webrtc.RTCIceServer;

@Execution(ExecutionMode.SAME_THREAD)
class IceRecoveryTests {
	private static final Duration GRACE = Duration.ofSeconds(2);
	private static final Duration TIMEOUT = Duration.ofSeconds(15);
	private final ManualExecutor control = new ManualExecutor();
	private final ManualTimer timer = new ManualTimer();
	private final AtomicBoolean closing = new AtomicBoolean();
	private final List<RecoveryEvent> events = new ArrayList<>();
	private final List<CompletableFuture<Void>> restarts = new ArrayList<>();
	private CompletableFuture<List<RTCIceServer>> credentials = CompletableFuture.completedFuture(null);
	private int usedServers;

	private IceRecovery recovery(RecoveryPolicy policy) {
		return new IceRecovery(policy, control, timer, closing::get, () -> {
			CompletableFuture<Void> result = new CompletableFuture<>();
			restarts.add(result);
			return result;
		}, new IceRecovery.Host() {
			public CompletableFuture<List<RTCIceServer>> servers(int attempt) { return credentials; }
			public void useServers(List<RTCIceServer> servers) { usedServers++; }
			public void event(RecoveryEvent event) { events.add(event); }
		});
	}

	private IceRecovery recovery() { return recovery(RecoveryPolicy.builder().build()); }
	private void fire(Duration delay) { assertEquals(1, timer.fire(delay)); control.runAll(); }
	private RecoveryEvent last() { return events.get(events.size() - 1); }

	@Test
	void briefOutageRecovers() {
		IceRecovery r = recovery();
		r.stateChanged(PeerState.DISCONNECTED);
		assertEquals(WAITING, last().state());
		r.stateChanged(PeerState.CONNECTED);
		assertEquals(RECOVERED, last().state());
		assertEquals(0, last().attempt());
		assertEquals(0, timer.fire(GRACE));
		assertTrue(restarts.isEmpty());
	}

	@Test
	void graceStartsRestart() {
		IceRecovery r = recovery();
		r.stateChanged(PeerState.DISCONNECTED);
		r.stateChanged(PeerState.DISCONNECTED);
		fire(GRACE);
		assertEquals(1, restarts.size());
		assertEquals(ATTEMPTING, last().state());
	}

	@Test
	void errorsDuringRestartDoNotOverlap() {
		IceRecovery r = recovery();
		r.stateChanged(PeerState.FAILED);
		control.runAll();
		r.stateChanged(PeerState.DISCONNECTED);
		r.stateChanged(PeerState.FAILED);
		assertEquals(1, restarts.size());
		restarts.get(0).completeExceptionally(new WebRtcException("Network unavailable"));
		control.runAll();
		assertEquals(RETRYING, last().state());
		fire(Duration.ofSeconds(1));
		assertEquals(2, restarts.size());
	}

	@Test
	void backoffEndsAtBudget() {
		IceRecovery r = recovery();
		r.stateChanged(PeerState.FAILED);
		control.runAll();
		for (int i = 0; i < 3; i++) {
			restarts.get(i).completeExceptionally(new WebRtcException("Offline"));
			control.runAll();
			if (i < 2) fire(Duration.ofSeconds(1L << i));
		}
		assertEquals(EXHAUSTED, last().state());
		assertEquals(3, last().attempt());
		assertNotNull(last().cause());
		r.stateChanged(PeerState.FAILED);
		r.stateChanged(PeerState.DISCONNECTED);
		assertEquals(3, restarts.size());
		assertEquals(0, timer.scheduled(TIMEOUT));
	}

	@Test
	void signalingSuccessStillNeedsConnectivity() {
		IceRecovery r = recovery();
		r.stateChanged(PeerState.FAILED);
		control.runAll();
		restarts.get(0).complete(null);
		control.runAll();
		assertEquals(ATTEMPTING, last().state());
		fire(TIMEOUT);
		assertEquals(RETRYING, last().state());
		fire(Duration.ofSeconds(1));
		assertEquals(2, restarts.size());
	}

	@Test
	void timeoutWaitsForExchange() {
		IceRecovery r = recovery();
		r.stateChanged(PeerState.FAILED);
		control.runAll();
		fire(TIMEOUT);
		assertEquals(0, timer.fire(Duration.ofSeconds(1)));
		restarts.get(0).completeExceptionally(new WebRtcException("HTTP failed"));
		control.runAll();
		fire(Duration.ofSeconds(1));
		assertEquals(2, restarts.size());
	}

	@Test
	void closeCancelsRetry() {
		IceRecovery r = recovery();
		r.stateChanged(PeerState.FAILED);
		control.runAll();
		restarts.get(0).completeExceptionally(new WebRtcException("Offline"));
		control.runAll();
		assertEquals(RETRYING, last().state());
		r.close();
		assertEquals(0, timer.fire(Duration.ofSeconds(1)));
		control.runAll();
		assertEquals(1, restarts.size());
	}

	@Test
	void closeWinsOverQueuedRetry() {
		IceRecovery r = recovery();
		r.stateChanged(PeerState.FAILED);
		control.runAll();
		restarts.get(0).completeExceptionally(new WebRtcException("Offline"));
		control.runAll();
		assertEquals(1, timer.fire(Duration.ofSeconds(1)));
		closing.set(true); // close() has been called, teardown is still queued.
		control.runAll();
		assertEquals(1, restarts.size());
	}

	@Test
	void lateCredentialsAreIgnored() {
		credentials = new CompletableFuture<>();
		IceRecovery r = recovery();
		r.stateChanged(PeerState.FAILED);
		fire(TIMEOUT);
		credentials.complete(Collections.emptyList());
		control.runAll();
		assertEquals(0, usedServers);
		assertTrue(restarts.isEmpty());
		credentials = CompletableFuture.completedFuture(Collections.emptyList());
		fire(Duration.ofSeconds(1));
		assertEquals(1, usedServers);
		assertEquals(1, restarts.size());
	}

	@Test
	void closeDuringCredentials() {
		credentials = new CompletableFuture<>();
		IceRecovery r = recovery();
		r.stateChanged(PeerState.FAILED);
		r.close();
		credentials.complete(Collections.emptyList());
		control.runAll();
		assertEquals(0, usedServers);
		assertTrue(restarts.isEmpty());
	}

	@Test
	void recoveryResetsBudget() {
		IceRecovery r = recovery(RecoveryPolicy.builder().maxAttempts(1).build());
		r.stateChanged(PeerState.FAILED);
		control.runAll();
		restarts.get(0).completeExceptionally(new WebRtcException("Offline"));
		control.runAll();
		assertEquals(EXHAUSTED, last().state());
		r.stateChanged(PeerState.CONNECTED);
		r.stateChanged(PeerState.FAILED);
		control.runAll();
		assertEquals(2, restarts.size());
		assertEquals(1, last().attempt());
	}

	@Test
	void recoveredExchangeCannotAffectNewOutage() {
		IceRecovery r = recovery();
		r.stateChanged(PeerState.FAILED);
		control.runAll();
		r.stateChanged(PeerState.CONNECTED);
		r.stateChanged(PeerState.FAILED);
		assertEquals(1, restarts.size());
		restarts.get(0).completeExceptionally(new WebRtcException("Old failure"));
		control.runAll();
		assertEquals(2, restarts.size());
		assertEquals(ATTEMPTING, last().state());
		assertEquals(1, last().attempt());
	}

	@Test
	void failedSkipsGrace() {
		IceRecovery r = recovery();
		r.stateChanged(PeerState.DISCONNECTED);
		r.stateChanged(PeerState.FAILED);
		control.runAll();
		assertEquals(1, restarts.size());
		assertEquals(0, timer.fire(GRACE));
	}

	@Test
	void oldExchangePreservesNewGrace() {
		IceRecovery r = recovery();
		r.stateChanged(PeerState.FAILED);
		control.runAll();
		r.stateChanged(PeerState.CONNECTED);
		r.stateChanged(PeerState.DISCONNECTED);
		restarts.get(0).complete(null);
		control.runAll();
		assertEquals(1, restarts.size());
		fire(GRACE);
		assertEquals(2, restarts.size());
	}

	@Test
	void failedCredentialsUseBudget() {
		credentials = new CompletableFuture<>();
		IceRecovery r = recovery(RecoveryPolicy.builder().maxAttempts(1).build());
		r.stateChanged(PeerState.FAILED);
		credentials.completeExceptionally(new WebRtcException("Credential service unavailable"));
		control.runAll();
		assertEquals(EXHAUSTED, last().state());
		assertEquals(1, last().attempt());
		assertTrue(restarts.isEmpty());
	}

	@Test
	void policyValidatesAndCapsBackoff() {
		assertThrows(IllegalArgumentException.class, () -> RecoveryPolicy.builder().maxAttempts(0));
		assertThrows(IllegalArgumentException.class, () -> RecoveryPolicy.builder().grace(Duration.ofSeconds(-1)));
		assertThrows(IllegalArgumentException.class, () -> RecoveryPolicy.builder().attemptTimeout(Duration.ZERO));
		assertThrows(IllegalArgumentException.class, () -> RecoveryPolicy.builder().backoff(GRACE, Duration.ofSeconds(1)));
		RecoveryPolicy p = RecoveryPolicy.builder().backoff(Duration.ofSeconds(1), Duration.ofSeconds(3)).build();
		assertEquals(Duration.ofSeconds(3), p.backoff(Integer.MAX_VALUE));
		RecoveryPolicy huge = RecoveryPolicy.builder().backoff(Duration.ofSeconds(Long.MAX_VALUE),
				Duration.ofSeconds(Long.MAX_VALUE)).build();
		assertEquals(Duration.ofMillis(Long.MAX_VALUE), huge.backoff(2));
		assertThrows(IllegalStateException.class, () -> PeerOptions.builder().negotiation(Negotiation.MANUAL)
				.recovery(p).build());
	}
}
