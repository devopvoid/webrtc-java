/*
 * Copyright 2019 Alex Andres
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

import java.util.ArrayList;
import java.util.List;

/**
 * The RTCConfiguration defines a set of parameters to configure how the
 * peer-to-peer communication established via {@link RTCPeerConnection} is
 * established or re-established.
 *
 * @author Alex Andres
 */
public class RTCConfiguration {

	/**
	 * Default maximum number of packets in the audio jitter buffer.
	 */
	public static final int kAudioJitterBufferMaxPackets = 200;

	/**
	 * A list of ICE server's describing servers available to be used by ICE,
	 * such as STUN and TURN servers.
	 */
	public List<RTCIceServer> iceServers;

	/**
	 * Indicates which candidates the ICE Agent is allowed to use.
	 */
	public RTCIceTransportPolicy iceTransportPolicy;

	/**
	 * Indicates which media-bundling policy to use when gathering ICE
	 * candidates.
	 */
	public RTCBundlePolicy bundlePolicy;

	/**
	 * Indicates which rtcp-mux policy to use when gathering ICE candidates.
	 */
	public RTCRtcpMuxPolicy rtcpMuxPolicy;

	/**
	 * A list of certificates that the RTCPeerConnection uses to authenticate.
	 * <p>
	 * If this value is absent, then a default set of certificates is generated
	 * for each RTCPeerConnection instance.
	 */
	public List<RTCCertificatePEM> certificates;

	/**
	 * Port allocator configuration for controlling candidate port ranges and
	 * transport behavior.
	 */
	public PortAllocatorConfig portAllocatorConfig;

	/**
	 * The maximum number of packets that can be stored in the NetEq audio
	 * jitter buffer. Can be reduced to lower tolerated audio latency.
	 */
	public int audioJitterBufferMaxPackets = kAudioJitterBufferMaxPackets;

	/** Whether to use the NetEq "fast mode" which will accelerate audio quicker
	 * 	if it falls behind.
	 */
	public boolean audioJitterBufferFastAccelerate;

	/**
	 * The minimum delay in milliseconds for the audio jitter buffer.
	 */
	public int audioJitterBufferMinDelayMs;

	// The fields below are unset by default, which keeps WebRTC's default.

	/**
	 * How many ICE candidates to gather before a connection needs them, so
	 * that connecting is faster. If unset, 0: gathering starts with the
	 * connection.
	 */
	public Integer iceCandidatePoolSize;

	/**
	 * Whether ICE gathers TCP candidates. If unset, {@link
	 * RTCTcpCandidatePolicy#ENABLED}.
	 */
	public RTCTcpCandidatePolicy tcpCandidatePolicy;

	/**
	 * Which networks ICE gathers candidates on. If unset, {@link
	 * RTCCandidateNetworkPolicy#ALL}.
	 */
	public RTCCandidateNetworkPolicy candidateNetworkPolicy;

	/**
	 * Whether ICE keeps gathering candidates after the first ones. If unset,
	 * {@link RTCContinualGatheringPolicy#GATHER_ONCE}.
	 */
	public RTCContinualGatheringPolicy continualGatheringPolicy;

	/**
	 * Whether to leave out IPv6 on Wi-Fi networks. If unset, false.
	 */
	public Boolean disableIpv6OnWifi;

	/**
	 * The largest number of IPv6 networks to gather candidates on. If unset,
	 * WebRTC's default of 5.
	 */
	public Integer maxIpv6Networks;

	/**
	 * The kind of network ICE prefers: a candidate pair on it takes precedence
	 * over pairs on other networks, regardless of their priority or network
	 * cost. If unset, no preference.
	 */
	public RTCAdapterType networkPreference;

	/**
	 * How ICE treats VPN connections. If unset, {@link
	 * RTCVpnPreference#DEFAULT}.
	 */
	public RTCVpnPreference vpnPreference;

	/**
	 * Whether candidates that a change of {@link #iceTransportPolicy} lets
	 * through are signaled at once, rather than at the next gathering. If
	 * unset, false.
	 */
	public Boolean surfaceIceCandidatesOnIceTransportTypeChanged;

	/**
	 * How long a connection may go without receiving before it counts as not
	 * receiving, in milliseconds. If unset, WebRTC's default.
	 */
	public Integer iceConnectionReceivingTimeout;

	/**
	 * How often to ping a backup candidate pair, in milliseconds. If unset,
	 * WebRTC's default.
	 */
	public Integer iceBackupCandidatePairPingInterval;

	/**
	 * How often to check a candidate pair while connectivity is strong, in
	 * milliseconds. If unset, WebRTC's default.
	 */
	public Integer iceCheckIntervalStrongConnectivity;

	/**
	 * How often to check a candidate pair while connectivity is weak, in
	 * milliseconds. If unset, WebRTC's default.
	 */
	public Integer iceCheckIntervalWeakConnectivity;

	/**
	 * The shortest time between two checks of a candidate pair, in
	 * milliseconds. If unset, WebRTC's default.
	 */
	public Integer iceCheckMinInterval;

	/**
	 * How long a connection may go without a response before it counts as
	 * unwritable, in milliseconds. If unset, WebRTC's default.
	 */
	public Integer iceUnwritableTimeout;

	/**
	 * How many checks have to go unanswered before a connection counts as
	 * unwritable. If unset, WebRTC's default.
	 */
	public Integer iceUnwritableMinChecks;

	/**
	 * How long a connection may stay unwritable before it counts as inactive,
	 * in milliseconds. If unset, WebRTC's default.
	 */
	public Integer iceInactiveTimeout;

	/**
	 * How often to send STUN keepalives on a candidate, in milliseconds. If
	 * unset, WebRTC's default.
	 */
	public Integer stunCandidateKeepaliveInterval;

	/**
	 * How often to ping a connection that is writable and stable, in
	 * milliseconds. If unset, WebRTC's default.
	 */
	public Integer stableWritableConnectionPingInterval;

	/**
	 * Whether to check first the candidate pairs most likely to work, rather
	 * than going by priority alone. If unset, false.
	 */
	public Boolean prioritizeMostLikelyIceCandidatePairs;

	/**
	 * Whether to offer ICE renomination, which lets the controlling end switch
	 * the selected candidate pair, if both ends support it. If unset, false.
	 */
	public Boolean enableIceRenomination;

	/**
	 * Whether ICE presumes that TURN-to-TURN candidate pairs work before a
	 * check has succeeded, so that the DTLS handshake can start at once, which
	 * speeds up connecting through TURN. If unset, false.
	 */
	public Boolean presumeWritableWhenFullyRelayed;

	/**
	 * How ICE prunes TURN ports. If unset, {@link RTCPortPrunePolicy#NO_PRUNE}.
	 */
	public RTCPortPrunePolicy turnPortPrunePolicy;

	/**
	 * An identifier sent to TURN servers, which they can log to tie their
	 * sessions to an application's. If unset, none is sent.
	 */
	public String turnLoggingId;

	/**
	 * Whether to mark media packets with DSCP values, so that networks that
	 * honor them can prioritize them. If unset, true.
	 */
	public Boolean enableDscp;

	/**
	 * Whether video senders lower resolution or frame rate when the CPU is
	 * overused. If unset, true.
	 */
	public Boolean enableCpuAdaptation;

	/**
	 * Whether a video sender stops sending when the bitrate falls below its
	 * minimum, rather than sending at too low a quality. If unset, false.
	 */
	public Boolean suspendBelowMinBitrate;

	/**
	 * The bitrate screen share video is padded up to, in kbps, which helps
	 * when a static screen turns into motion. If unset, 100 kbps.
	 */
	public Integer screencastMinBitrate;

	/**
	 * The ciphers to offer for SRTP. If unset, WebRTC's defaults, which a new
	 * {@link RTCCryptoOptions} holds too.
	 */
	public RTCCryptoOptions cryptoOptions;

	/**
	 * Whether offers allow one- and two-byte RTP header extensions to be mixed
	 * (a=extmap-allow-mixed). If unset, true.
	 */
	public Boolean offerExtmapAllowMixed;

	/**
	 * Whether setting a remote offer while a local offer is pending rolls the
	 * local offer back implicitly, as perfect negotiation needs. If unset,
	 * false.
	 */
	public Boolean enableImplicitRollback;

	/**
	 * Whether offers include data channels, as the first m-section, before any
	 * data channel is created. If unset, false.
	 */
	public Boolean alwaysNegotiateDataChannels;

	/**
	 * Creates an instance of RTCConfiguration.
	 */
	public RTCConfiguration() {
		iceServers = new ArrayList<>();
		iceTransportPolicy = RTCIceTransportPolicy.ALL;
		bundlePolicy = RTCBundlePolicy.BALANCED;
		rtcpMuxPolicy = RTCRtcpMuxPolicy.REQUIRE;
		certificates = new ArrayList<>();
		portAllocatorConfig = new PortAllocatorConfig();
	}

}
