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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests the configuration fields of peer connections that the native side
 * has to carry: that they reach WebRTC, come back, and take effect.
 */
@Execution(ExecutionMode.SAME_THREAD)
class RTCConfigurationFieldsTests extends TestBase {

	private static final long TIMEOUT_SECONDS = 10;


	@Test
	void nativeDefaults() {
		RTCPeerConnection connection = factory.createPeerConnection(new RTCConfiguration(), new Observer());

		try {
			RTCConfiguration config = connection.getConfiguration();

			// Unset fields keep WebRTC's defaults, including those that are
			// true, which a plain Java boolean would have turned off.
			assertEquals(0, (int) config.iceCandidatePoolSize);
			assertEquals(5, (int) config.maxIpv6Networks);
			assertEquals(RTCTcpCandidatePolicy.ENABLED, config.tcpCandidatePolicy);
			assertEquals(RTCCandidateNetworkPolicy.ALL, config.candidateNetworkPolicy);
			assertEquals(RTCContinualGatheringPolicy.GATHER_ONCE, config.continualGatheringPolicy);
			assertEquals(RTCPortPrunePolicy.NO_PRUNE, config.turnPortPrunePolicy);
			assertEquals(RTCVpnPreference.DEFAULT, config.vpnPreference);
			assertEquals(Boolean.TRUE, config.enableDscp);
			assertEquals(Boolean.TRUE, config.enableCpuAdaptation);
			assertEquals(Boolean.TRUE, config.offerExtmapAllowMixed);
			assertEquals(Boolean.FALSE, config.enableImplicitRollback);
			assertNull(config.networkPreference);
			assertNull(config.iceCheckMinInterval);
			assertNull(config.turnLoggingId);

			assertNotNull(config.cryptoOptions);
			assertTrue(config.cryptoOptions.enableGcmCryptoSuites);
			assertTrue(config.cryptoOptions.enableAes128Sha1_80CryptoCipher);
			assertFalse(config.cryptoOptions.enableAes128Sha1_32CryptoCipher);
			assertEquals(RTCCryptexPolicy.DISABLED, config.cryptoOptions.cryptexPolicy);
		}
		finally {
			connection.close();
		}
	}

	@Test
	void fieldsRoundTrip() {
		RTCConfiguration config = new RTCConfiguration();
		config.iceCandidatePoolSize = 2;
		config.tcpCandidatePolicy = RTCTcpCandidatePolicy.DISABLED;
		config.candidateNetworkPolicy = RTCCandidateNetworkPolicy.LOW_COST;
		config.continualGatheringPolicy = RTCContinualGatheringPolicy.GATHER_CONTINUALLY;
		config.disableIpv6OnWifi = true;
		config.maxIpv6Networks = 2;
		config.networkPreference = RTCAdapterType.WIFI;
		config.vpnPreference = RTCVpnPreference.AVOID_VPN;
		config.iceConnectionReceivingTimeout = 3000;
		// No longer than the ping interval of stable connections, which
		// WebRTC checks.
		config.iceCheckIntervalStrongConnectivity = 2000;
		config.iceCheckMinInterval = 100;
		config.stunCandidateKeepaliveInterval = 15000;
		config.presumeWritableWhenFullyRelayed = true;
		config.turnPortPrunePolicy = RTCPortPrunePolicy.KEEP_FIRST_READY;
		config.turnLoggingId = "session-42";
		config.enableDscp = false;
		config.suspendBelowMinBitrate = true;
		config.screencastMinBitrate = 200;
		config.enableImplicitRollback = true;

		config.cryptoOptions = new RTCCryptoOptions();
		config.cryptoOptions.enableAes128Sha1_32CryptoCipher = true;
		config.cryptoOptions.cryptexPolicy = RTCCryptexPolicy.NEGOTIATE;

		RTCPeerConnection connection = factory.createPeerConnection(config, new Observer());

		try {
			RTCConfiguration applied = connection.getConfiguration();

			assertEquals(2, (int) applied.iceCandidatePoolSize);
			assertEquals(RTCTcpCandidatePolicy.DISABLED, applied.tcpCandidatePolicy);
			assertEquals(RTCCandidateNetworkPolicy.LOW_COST, applied.candidateNetworkPolicy);
			assertEquals(RTCContinualGatheringPolicy.GATHER_CONTINUALLY, applied.continualGatheringPolicy);
			assertEquals(Boolean.TRUE, applied.disableIpv6OnWifi);
			assertEquals(2, (int) applied.maxIpv6Networks);
			assertEquals(RTCAdapterType.WIFI, applied.networkPreference);
			assertEquals(RTCVpnPreference.AVOID_VPN, applied.vpnPreference);
			assertEquals(3000, (int) applied.iceConnectionReceivingTimeout);
			assertEquals(2000, (int) applied.iceCheckIntervalStrongConnectivity);
			assertEquals(100, (int) applied.iceCheckMinInterval);
			assertEquals(15000, (int) applied.stunCandidateKeepaliveInterval);
			assertEquals(Boolean.TRUE, applied.presumeWritableWhenFullyRelayed);
			assertEquals(RTCPortPrunePolicy.KEEP_FIRST_READY, applied.turnPortPrunePolicy);
			assertEquals("session-42", applied.turnLoggingId);
			assertEquals(Boolean.FALSE, applied.enableDscp);
			assertEquals(Boolean.TRUE, applied.suspendBelowMinBitrate);
			assertEquals(200, (int) applied.screencastMinBitrate);
			assertEquals(Boolean.TRUE, applied.enableImplicitRollback);
			assertTrue(applied.cryptoOptions.enableAes128Sha1_32CryptoCipher);
			assertEquals(RTCCryptexPolicy.NEGOTIATE, applied.cryptoOptions.cryptexPolicy);
		}
		finally {
			connection.close();
		}
	}

	@Test
	void invalidConfigurationFailsReadably() {
		RTCConfiguration config = new RTCConfiguration();
		config.iceCheckIntervalStrongConnectivity = 10000;
		config.stableWritableConnectionPingInterval = 1000;

		RuntimeException error = assertThrows(RuntimeException.class,
				() -> factory.createPeerConnection(config, new Observer()));

		// The error type is readable text, not the bytes of a pointer.
		assertTrue(error.getMessage().matches("Create PeerConnection failed: [A-Z_]+ .+"), error.getMessage());
	}

	@Test
	void setConfigurationApplies() {
		RTCPeerConnection connection = factory.createPeerConnection(new RTCConfiguration(), new Observer());

		try {
			RTCConfiguration config = connection.getConfiguration();
			config.iceCandidatePoolSize = 3;
			config.iceCheckMinInterval = 250;
			config.networkPreference = RTCAdapterType.ETHERNET;

			connection.setConfiguration(config);

			RTCConfiguration applied = connection.getConfiguration();

			assertEquals(3, (int) applied.iceCandidatePoolSize);
			assertEquals(250, (int) applied.iceCheckMinInterval);
			assertEquals(RTCAdapterType.ETHERNET, applied.networkPreference);
		}
		finally {
			connection.close();
		}
	}

	@Test
	void tcpCandidatesDisabled() throws Exception {
		RTCConfiguration config = new RTCConfiguration();
		config.tcpCandidatePolicy = RTCTcpCandidatePolicy.DISABLED;

		Observer observer = new Observer();
		RTCPeerConnection connection = factory.createPeerConnection(config, observer);

		try {
			// A data channel gives the offer something to gather candidates for.
			RTCDataChannel channel = connection.createDataChannel("data", new RTCDataChannelInit());

			TestCreateDescObserver createObserver = new TestCreateDescObserver();
			connection.createOffer(new RTCOfferOptions(), createObserver);

			TestSetDescObserver setObserver = new TestSetDescObserver();
			connection.setLocalDescription(createObserver.get(), setObserver);
			setObserver.get();

			assertTrue(observer.gathered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "gathering did not complete");
			assertFalse(observer.candidates.isEmpty(), "no candidates gathered");

			for (RTCIceCandidate candidate : observer.candidates) {
				assertFalse(candidate.sdp.toLowerCase().contains(" tcp "), candidate.sdp);
			}

			channel.close();
			channel.dispose();
		}
		finally {
			connection.close();
		}
	}



	private static class Observer implements PeerConnectionObserver {

		final List<RTCIceCandidate> candidates = new CopyOnWriteArrayList<>();

		final CountDownLatch gathered = new CountDownLatch(1);


		@Override
		public void onIceCandidate(RTCIceCandidate candidate) {
			candidates.add(candidate);
		}

		@Override
		public void onIceGatheringChange(RTCIceGatheringState state) {
			if (state == RTCIceGatheringState.COMPLETE) {
				gathered.countDown();
			}
		}

	}

}
