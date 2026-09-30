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

#include "api/RTCConfiguration.h"
#include "api/RTCCryptoOptions.h"
#include "api/RTCIceServer.h"
#include "api/PortAllocatorConfig.h"
#include "rtc/RTCCertificatePEM.h"
#include "JavaArrayList.h"
#include "JavaClasses.h"
#include "JavaEnums.h"
#include "JavaIterable.h"
#include "JavaList.h"
#include "JavaRef.h"
#include "JavaObject.h"
#include "JavaPrimitive.h"
#include "JavaString.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace RTCConfiguration
	{
		namespace
		{
			// The order of RTCAdapterType. The native adapter types are bit
			// flags, which the conversion of enums by ordinal cannot map.
			const webrtc::AdapterType kAdapterTypes[] = {
				webrtc::ADAPTER_TYPE_UNKNOWN,
				webrtc::ADAPTER_TYPE_ETHERNET,
				webrtc::ADAPTER_TYPE_WIFI,
				webrtc::ADAPTER_TYPE_CELLULAR,
				webrtc::ADAPTER_TYPE_VPN,
				webrtc::ADAPTER_TYPE_LOOPBACK,
				webrtc::ADAPTER_TYPE_ANY
			};

			AdapterTypeOrdinal AdapterTypeToOrdinal(webrtc::AdapterType type)
			{
				for (size_t i = 0; i < std::size(kAdapterTypes); i++) {
					if (kAdapterTypes[i] == type) {
						return static_cast<AdapterTypeOrdinal>(i);
					}
				}

				// The cellular subtypes of mobile platforms.
				return static_cast<AdapterTypeOrdinal>(3);
			}

			webrtc::AdapterType OrdinalToAdapterType(AdapterTypeOrdinal ordinal)
			{
				const size_t index = static_cast<size_t>(ordinal);

				return index < std::size(kAdapterTypes) ? kAdapterTypes[index] : webrtc::ADAPTER_TYPE_UNKNOWN;
			}
		}

		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::PeerConnectionInterface::RTCConfiguration & nativeType)
		{
			const auto javaClass = JavaClasses::get<JavaRTCConfigurationClass>(env);

			auto certificates = nativeType.certificates;

			auto serverList = JavaList::toArrayList(env, nativeType.servers, &RTCIceServer::toJava);
			JavaArrayList certificateList(env, certificates.size());

			for (auto & certificate : certificates) {
				certificateList.add(jni::RTCCertificatePEM::toJava(env, certificate->ToPEM()));
			}

			auto type = JavaEnums::toJava(env, nativeType.type);
			auto bundlePolicy = JavaEnums::toJava(env, nativeType.bundle_policy);
			auto rtcpMuxPolicy = JavaEnums::toJava(env, nativeType.rtcp_mux_policy);

			jobject config = env->NewObject(javaClass->cls, javaClass->ctor);

			env->SetObjectField(config, javaClass->iceServers, serverList.get());
			env->SetObjectField(config, javaClass->iceTransportPolicy, type.get());
			env->SetObjectField(config, javaClass->bundlePolicy, bundlePolicy.get());
			env->SetObjectField(config, javaClass->rtcpMuxPolicy, rtcpMuxPolicy.get());
			env->SetObjectField(config, javaClass->certificates, certificateList.listObject());

			auto pac = jni::PortAllocatorConfig::toJava(env, nativeType.port_allocator_config);
			env->SetObjectField(config, javaClass->portAllocatorConfig, pac.get());

            env->SetIntField(config, javaClass->audioJitterBufferMaxPackets, nativeType.audio_jitter_buffer_max_packets);
            env->SetBooleanField(config, javaClass->audioJitterBufferFastAccelerate, nativeType.audio_jitter_buffer_fast_accelerate);
            env->SetIntField(config, javaClass->audioJitterBufferMinDelayMs, nativeType.audio_jitter_buffer_min_delay_ms);

			env->SetObjectField(config, javaClass->iceCandidatePoolSize, Integer::create(env, nativeType.ice_candidate_pool_size).get());
			env->SetObjectField(config, javaClass->tcpCandidatePolicy, JavaEnums::toJava(env, nativeType.tcp_candidate_policy).get());
			env->SetObjectField(config, javaClass->candidateNetworkPolicy, JavaEnums::toJava(env, nativeType.candidate_network_policy).get());
			env->SetObjectField(config, javaClass->continualGatheringPolicy, JavaEnums::toJava(env, nativeType.continual_gathering_policy).get());
			env->SetObjectField(config, javaClass->disableIpv6OnWifi, Boolean::create(env, nativeType.disable_ipv6_on_wifi).get());
			env->SetObjectField(config, javaClass->maxIpv6Networks, Integer::create(env, nativeType.max_ipv6_networks).get());
			env->SetObjectField(config, javaClass->vpnPreference, JavaEnums::toJava(env, nativeType.vpn_preference).get());
			env->SetObjectField(config, javaClass->surfaceIceCandidatesOnIceTransportTypeChanged, Boolean::create(env, nativeType.surface_ice_candidates_on_ice_transport_type_changed).get());
			if (nativeType.ice_connection_receiving_timeout != webrtc::PeerConnectionInterface::RTCConfiguration::kUndefined) {
				env->SetObjectField(config, javaClass->iceConnectionReceivingTimeout, Integer::create(env, nativeType.ice_connection_receiving_timeout).get());
			}
			if (nativeType.ice_backup_candidate_pair_ping_interval != webrtc::PeerConnectionInterface::RTCConfiguration::kUndefined) {
				env->SetObjectField(config, javaClass->iceBackupCandidatePairPingInterval, Integer::create(env, nativeType.ice_backup_candidate_pair_ping_interval).get());
			}
			if (nativeType.ice_check_interval_strong_connectivity.has_value()) {
				env->SetObjectField(config, javaClass->iceCheckIntervalStrongConnectivity, Integer::create(env, *nativeType.ice_check_interval_strong_connectivity).get());
			}
			if (nativeType.ice_check_interval_weak_connectivity.has_value()) {
				env->SetObjectField(config, javaClass->iceCheckIntervalWeakConnectivity, Integer::create(env, *nativeType.ice_check_interval_weak_connectivity).get());
			}
			if (nativeType.ice_check_min_interval.has_value()) {
				env->SetObjectField(config, javaClass->iceCheckMinInterval, Integer::create(env, *nativeType.ice_check_min_interval).get());
			}
			if (nativeType.ice_unwritable_timeout.has_value()) {
				env->SetObjectField(config, javaClass->iceUnwritableTimeout, Integer::create(env, *nativeType.ice_unwritable_timeout).get());
			}
			if (nativeType.ice_unwritable_min_checks.has_value()) {
				env->SetObjectField(config, javaClass->iceUnwritableMinChecks, Integer::create(env, *nativeType.ice_unwritable_min_checks).get());
			}
			if (nativeType.ice_inactive_timeout.has_value()) {
				env->SetObjectField(config, javaClass->iceInactiveTimeout, Integer::create(env, *nativeType.ice_inactive_timeout).get());
			}
			if (nativeType.stun_candidate_keepalive_interval.has_value()) {
				env->SetObjectField(config, javaClass->stunCandidateKeepaliveInterval, Integer::create(env, *nativeType.stun_candidate_keepalive_interval).get());
			}
			if (nativeType.stable_writable_connection_ping_interval_ms.has_value()) {
				env->SetObjectField(config, javaClass->stableWritableConnectionPingInterval, Integer::create(env, *nativeType.stable_writable_connection_ping_interval_ms).get());
			}
			env->SetObjectField(config, javaClass->prioritizeMostLikelyIceCandidatePairs, Boolean::create(env, nativeType.prioritize_most_likely_ice_candidate_pairs).get());
			env->SetObjectField(config, javaClass->enableIceRenomination, Boolean::create(env, nativeType.enable_ice_renomination).get());
			env->SetObjectField(config, javaClass->presumeWritableWhenFullyRelayed, Boolean::create(env, nativeType.presume_writable_when_fully_relayed).get());
			env->SetObjectField(config, javaClass->turnPortPrunePolicy, JavaEnums::toJava(env, nativeType.turn_port_prune_policy).get());
			env->SetObjectField(config, javaClass->enableDscp, Boolean::create(env, nativeType.media_config.enable_dscp).get());
			env->SetObjectField(config, javaClass->enableCpuAdaptation, Boolean::create(env, nativeType.media_config.video.enable_cpu_adaptation).get());
			env->SetObjectField(config, javaClass->suspendBelowMinBitrate, Boolean::create(env, nativeType.media_config.video.suspend_below_min_bitrate).get());
			if (nativeType.screencast_min_bitrate.has_value()) {
				env->SetObjectField(config, javaClass->screencastMinBitrate, Integer::create(env, *nativeType.screencast_min_bitrate).get());
			}
			env->SetObjectField(config, javaClass->offerExtmapAllowMixed, Boolean::create(env, nativeType.offer_extmap_allow_mixed).get());
			env->SetObjectField(config, javaClass->enableImplicitRollback, Boolean::create(env, nativeType.enable_implicit_rollback).get());
			env->SetObjectField(config, javaClass->alwaysNegotiateDataChannels, Boolean::create(env, nativeType.always_negotiate_data_channels).get());
			if (nativeType.network_preference.has_value()) {
				env->SetObjectField(config, javaClass->networkPreference,
					JavaEnums::toJava(env, AdapterTypeToOrdinal(*nativeType.network_preference)).get());
			}
			if (!nativeType.turn_logging_id.empty()) {
				env->SetObjectField(config, javaClass->turnLoggingId, JavaString::toJava(env, nativeType.turn_logging_id).get());
			}

			env->SetObjectField(config, javaClass->cryptoOptions, RTCCryptoOptions::toJava(env, nativeType.crypto_options).get());

			return JavaLocalRef<jobject>(env, config);
		}

		webrtc::PeerConnectionInterface::RTCConfiguration toNative(JNIEnv * env, const JavaRef<jobject> & javaType)
		{
			const auto javaClass = JavaClasses::get<JavaRTCConfigurationClass>(env);

			JavaObject obj(env, javaType);

			JavaLocalRef<jobject> is = obj.getObject(javaClass->iceServers);
			JavaLocalRef<jobject> tp = obj.getObject(javaClass->iceTransportPolicy);
			JavaLocalRef<jobject> bp = obj.getObject(javaClass->bundlePolicy);
			JavaLocalRef<jobject> mp = obj.getObject(javaClass->rtcpMuxPolicy);
			JavaLocalRef<jobject> cr = obj.getObject(javaClass->certificates);
			JavaLocalRef<jobject> pac = obj.getObject(javaClass->portAllocatorConfig);

			webrtc::PeerConnectionInterface::RTCConfiguration configuration;

			configuration.servers = JavaList::toVector(env, is, &RTCIceServer::toNative);
			configuration.sdp_semantics = webrtc::SdpSemantics::kUnifiedPlan;
			configuration.type = JavaEnums::toNative<webrtc::PeerConnectionInterface::IceTransportsType>(env, tp);
			configuration.bundle_policy = JavaEnums::toNative<webrtc::PeerConnectionInterface::BundlePolicy>(env, bp);
			configuration.rtcp_mux_policy = JavaEnums::toNative<webrtc::PeerConnectionInterface::RtcpMuxPolicy>(env, mp);

			for (auto & item : JavaIterable(env, cr)) {
				auto certificate = webrtc::RTCCertificate::FromPEM(jni::RTCCertificatePEM::toNative(env, item));

				RTC_CHECK(certificate != nullptr) << "Supplied certificate is malformed";

				if (certificate != nullptr) {
					configuration.certificates.push_back(certificate);
				}
			}

			if (pac.get() != nullptr) {
				const auto pacJavaClass = JavaClasses::get<PortAllocatorConfig::JavaPortAllocatorConfigClass>(env);
				JavaObject pacObj(env, pac);

				configuration.port_allocator_config.min_port = pacObj.getInt(pacJavaClass->minPort);
				configuration.port_allocator_config.max_port = pacObj.getInt(pacJavaClass->maxPort);
				configuration.port_allocator_config.flags = pacObj.getInt(pacJavaClass->flags);
			}

            configuration.audio_jitter_buffer_fast_accelerate = obj.getBoolean(javaClass->audioJitterBufferFastAccelerate);
            configuration.audio_jitter_buffer_max_packets = obj.getInt(javaClass->audioJitterBufferMaxPackets);
            configuration.audio_jitter_buffer_min_delay_ms = obj.getInt(javaClass->audioJitterBufferMinDelayMs);

			JavaLocalRef<jobject> iceCandidatePoolSize = obj.getObject(javaClass->iceCandidatePoolSize);
			JavaLocalRef<jobject> tcpCandidatePolicy = obj.getObject(javaClass->tcpCandidatePolicy);
			JavaLocalRef<jobject> candidateNetworkPolicy = obj.getObject(javaClass->candidateNetworkPolicy);
			JavaLocalRef<jobject> continualGatheringPolicy = obj.getObject(javaClass->continualGatheringPolicy);
			JavaLocalRef<jobject> disableIpv6OnWifi = obj.getObject(javaClass->disableIpv6OnWifi);
			JavaLocalRef<jobject> maxIpv6Networks = obj.getObject(javaClass->maxIpv6Networks);
			JavaLocalRef<jobject> vpnPreference = obj.getObject(javaClass->vpnPreference);
			JavaLocalRef<jobject> surfaceIceCandidatesOnIceTransportTypeChanged = obj.getObject(javaClass->surfaceIceCandidatesOnIceTransportTypeChanged);
			JavaLocalRef<jobject> iceConnectionReceivingTimeout = obj.getObject(javaClass->iceConnectionReceivingTimeout);
			JavaLocalRef<jobject> iceBackupCandidatePairPingInterval = obj.getObject(javaClass->iceBackupCandidatePairPingInterval);
			JavaLocalRef<jobject> iceCheckIntervalStrongConnectivity = obj.getObject(javaClass->iceCheckIntervalStrongConnectivity);
			JavaLocalRef<jobject> iceCheckIntervalWeakConnectivity = obj.getObject(javaClass->iceCheckIntervalWeakConnectivity);
			JavaLocalRef<jobject> iceCheckMinInterval = obj.getObject(javaClass->iceCheckMinInterval);
			JavaLocalRef<jobject> iceUnwritableTimeout = obj.getObject(javaClass->iceUnwritableTimeout);
			JavaLocalRef<jobject> iceUnwritableMinChecks = obj.getObject(javaClass->iceUnwritableMinChecks);
			JavaLocalRef<jobject> iceInactiveTimeout = obj.getObject(javaClass->iceInactiveTimeout);
			JavaLocalRef<jobject> stunCandidateKeepaliveInterval = obj.getObject(javaClass->stunCandidateKeepaliveInterval);
			JavaLocalRef<jobject> stableWritableConnectionPingInterval = obj.getObject(javaClass->stableWritableConnectionPingInterval);
			JavaLocalRef<jobject> prioritizeMostLikelyIceCandidatePairs = obj.getObject(javaClass->prioritizeMostLikelyIceCandidatePairs);
			JavaLocalRef<jobject> enableIceRenomination = obj.getObject(javaClass->enableIceRenomination);
			JavaLocalRef<jobject> presumeWritableWhenFullyRelayed = obj.getObject(javaClass->presumeWritableWhenFullyRelayed);
			JavaLocalRef<jobject> turnPortPrunePolicy = obj.getObject(javaClass->turnPortPrunePolicy);
			JavaLocalRef<jobject> enableDscp = obj.getObject(javaClass->enableDscp);
			JavaLocalRef<jobject> enableCpuAdaptation = obj.getObject(javaClass->enableCpuAdaptation);
			JavaLocalRef<jobject> suspendBelowMinBitrate = obj.getObject(javaClass->suspendBelowMinBitrate);
			JavaLocalRef<jobject> screencastMinBitrate = obj.getObject(javaClass->screencastMinBitrate);
			JavaLocalRef<jobject> offerExtmapAllowMixed = obj.getObject(javaClass->offerExtmapAllowMixed);
			JavaLocalRef<jobject> enableImplicitRollback = obj.getObject(javaClass->enableImplicitRollback);
			JavaLocalRef<jobject> alwaysNegotiateDataChannels = obj.getObject(javaClass->alwaysNegotiateDataChannels);

			if (iceCandidatePoolSize.get() != nullptr) {
				configuration.ice_candidate_pool_size = Integer::getValue(env, iceCandidatePoolSize);
			}
			if (tcpCandidatePolicy.get() != nullptr) {
				configuration.tcp_candidate_policy = JavaEnums::toNative<webrtc::PeerConnectionInterface::TcpCandidatePolicy>(env, tcpCandidatePolicy);
			}
			if (candidateNetworkPolicy.get() != nullptr) {
				configuration.candidate_network_policy = JavaEnums::toNative<webrtc::PeerConnectionInterface::CandidateNetworkPolicy>(env, candidateNetworkPolicy);
			}
			if (continualGatheringPolicy.get() != nullptr) {
				configuration.continual_gathering_policy = JavaEnums::toNative<webrtc::PeerConnectionInterface::ContinualGatheringPolicy>(env, continualGatheringPolicy);
			}
			if (disableIpv6OnWifi.get() != nullptr) {
				configuration.disable_ipv6_on_wifi = Boolean::getValue(env, disableIpv6OnWifi);
			}
			if (maxIpv6Networks.get() != nullptr) {
				configuration.max_ipv6_networks = Integer::getValue(env, maxIpv6Networks);
			}
			if (vpnPreference.get() != nullptr) {
				configuration.vpn_preference = JavaEnums::toNative<webrtc::VpnPreference>(env, vpnPreference);
			}
			if (surfaceIceCandidatesOnIceTransportTypeChanged.get() != nullptr) {
				configuration.surface_ice_candidates_on_ice_transport_type_changed = Boolean::getValue(env, surfaceIceCandidatesOnIceTransportTypeChanged);
			}
			if (iceConnectionReceivingTimeout.get() != nullptr) {
				configuration.ice_connection_receiving_timeout = Integer::getValue(env, iceConnectionReceivingTimeout);
			}
			if (iceBackupCandidatePairPingInterval.get() != nullptr) {
				configuration.ice_backup_candidate_pair_ping_interval = Integer::getValue(env, iceBackupCandidatePairPingInterval);
			}
			if (iceCheckIntervalStrongConnectivity.get() != nullptr) {
				configuration.ice_check_interval_strong_connectivity = Integer::getValue(env, iceCheckIntervalStrongConnectivity);
			}
			if (iceCheckIntervalWeakConnectivity.get() != nullptr) {
				configuration.ice_check_interval_weak_connectivity = Integer::getValue(env, iceCheckIntervalWeakConnectivity);
			}
			if (iceCheckMinInterval.get() != nullptr) {
				configuration.ice_check_min_interval = Integer::getValue(env, iceCheckMinInterval);
			}
			if (iceUnwritableTimeout.get() != nullptr) {
				configuration.ice_unwritable_timeout = Integer::getValue(env, iceUnwritableTimeout);
			}
			if (iceUnwritableMinChecks.get() != nullptr) {
				configuration.ice_unwritable_min_checks = Integer::getValue(env, iceUnwritableMinChecks);
			}
			if (iceInactiveTimeout.get() != nullptr) {
				configuration.ice_inactive_timeout = Integer::getValue(env, iceInactiveTimeout);
			}
			if (stunCandidateKeepaliveInterval.get() != nullptr) {
				configuration.stun_candidate_keepalive_interval = Integer::getValue(env, stunCandidateKeepaliveInterval);
			}
			if (stableWritableConnectionPingInterval.get() != nullptr) {
				configuration.stable_writable_connection_ping_interval_ms = Integer::getValue(env, stableWritableConnectionPingInterval);
			}
			if (prioritizeMostLikelyIceCandidatePairs.get() != nullptr) {
				configuration.prioritize_most_likely_ice_candidate_pairs = Boolean::getValue(env, prioritizeMostLikelyIceCandidatePairs);
			}
			if (enableIceRenomination.get() != nullptr) {
				configuration.enable_ice_renomination = Boolean::getValue(env, enableIceRenomination);
			}
			if (presumeWritableWhenFullyRelayed.get() != nullptr) {
				configuration.presume_writable_when_fully_relayed = Boolean::getValue(env, presumeWritableWhenFullyRelayed);
			}
			if (turnPortPrunePolicy.get() != nullptr) {
				configuration.turn_port_prune_policy = JavaEnums::toNative<webrtc::PortPrunePolicy>(env, turnPortPrunePolicy);
			}
			if (enableDscp.get() != nullptr) {
				configuration.media_config.enable_dscp = Boolean::getValue(env, enableDscp);
			}
			if (enableCpuAdaptation.get() != nullptr) {
				configuration.media_config.video.enable_cpu_adaptation = Boolean::getValue(env, enableCpuAdaptation);
			}
			if (suspendBelowMinBitrate.get() != nullptr) {
				configuration.media_config.video.suspend_below_min_bitrate = Boolean::getValue(env, suspendBelowMinBitrate);
			}
			if (screencastMinBitrate.get() != nullptr) {
				configuration.screencast_min_bitrate = Integer::getValue(env, screencastMinBitrate);
			}
			if (offerExtmapAllowMixed.get() != nullptr) {
				configuration.offer_extmap_allow_mixed = Boolean::getValue(env, offerExtmapAllowMixed);
			}
			if (enableImplicitRollback.get() != nullptr) {
				configuration.enable_implicit_rollback = Boolean::getValue(env, enableImplicitRollback);
			}
			if (alwaysNegotiateDataChannels.get() != nullptr) {
				configuration.always_negotiate_data_channels = Boolean::getValue(env, alwaysNegotiateDataChannels);
			}

			JavaLocalRef<jobject> networkPreference = obj.getObject(javaClass->networkPreference);
			JavaLocalRef<jstring> turnLoggingId = obj.getString(javaClass->turnLoggingId);
			JavaLocalRef<jobject> cryptoOptions = obj.getObject(javaClass->cryptoOptions);

			if (networkPreference.get() != nullptr) {
				configuration.network_preference =
					OrdinalToAdapterType(JavaEnums::toNative<AdapterTypeOrdinal>(env, networkPreference));
			}
			if (turnLoggingId.get() != nullptr) {
				configuration.turn_logging_id = JavaString::toNative(env, turnLoggingId);
			}
			if (cryptoOptions.get() != nullptr) {
				configuration.crypto_options = RTCCryptoOptions::toNative(env, cryptoOptions);
			}

			return configuration;
		}

		JavaRTCConfigurationClass::JavaRTCConfigurationClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG"RTCConfiguration");

			ctor = GetMethod(env, cls, "<init>", "()V");

			iceServers = GetFieldID(env, cls, "iceServers", LIST_SIG);
			iceTransportPolicy = GetFieldID(env, cls, "iceTransportPolicy", "L" PKG "RTCIceTransportPolicy;");
			bundlePolicy = GetFieldID(env, cls, "bundlePolicy", "L" PKG "RTCBundlePolicy;");
			rtcpMuxPolicy = GetFieldID(env, cls, "rtcpMuxPolicy", "L" PKG "RTCRtcpMuxPolicy;");
			certificates = GetFieldID(env, cls, "certificates", LIST_SIG);
			portAllocatorConfig = GetFieldID(env, cls, "portAllocatorConfig", "L" PKG "PortAllocatorConfig;");
			audioJitterBufferMaxPackets = GetFieldID(env, cls, "audioJitterBufferMaxPackets", "I");
			audioJitterBufferFastAccelerate = GetFieldID(env, cls, "audioJitterBufferFastAccelerate", "Z");
			audioJitterBufferMinDelayMs = GetFieldID(env, cls, "audioJitterBufferMinDelayMs", "I");
			iceCandidatePoolSize = GetFieldID(env, cls, "iceCandidatePoolSize", INTEGER_SIG);
			tcpCandidatePolicy = GetFieldID(env, cls, "tcpCandidatePolicy", "L" PKG "RTCTcpCandidatePolicy;");
			candidateNetworkPolicy = GetFieldID(env, cls, "candidateNetworkPolicy", "L" PKG "RTCCandidateNetworkPolicy;");
			continualGatheringPolicy = GetFieldID(env, cls, "continualGatheringPolicy", "L" PKG "RTCContinualGatheringPolicy;");
			disableIpv6OnWifi = GetFieldID(env, cls, "disableIpv6OnWifi", BOOLEAN_SIG);
			maxIpv6Networks = GetFieldID(env, cls, "maxIpv6Networks", INTEGER_SIG);
			vpnPreference = GetFieldID(env, cls, "vpnPreference", "L" PKG "RTCVpnPreference;");
			surfaceIceCandidatesOnIceTransportTypeChanged = GetFieldID(env, cls, "surfaceIceCandidatesOnIceTransportTypeChanged", BOOLEAN_SIG);
			iceConnectionReceivingTimeout = GetFieldID(env, cls, "iceConnectionReceivingTimeout", INTEGER_SIG);
			iceBackupCandidatePairPingInterval = GetFieldID(env, cls, "iceBackupCandidatePairPingInterval", INTEGER_SIG);
			iceCheckIntervalStrongConnectivity = GetFieldID(env, cls, "iceCheckIntervalStrongConnectivity", INTEGER_SIG);
			iceCheckIntervalWeakConnectivity = GetFieldID(env, cls, "iceCheckIntervalWeakConnectivity", INTEGER_SIG);
			iceCheckMinInterval = GetFieldID(env, cls, "iceCheckMinInterval", INTEGER_SIG);
			iceUnwritableTimeout = GetFieldID(env, cls, "iceUnwritableTimeout", INTEGER_SIG);
			iceUnwritableMinChecks = GetFieldID(env, cls, "iceUnwritableMinChecks", INTEGER_SIG);
			iceInactiveTimeout = GetFieldID(env, cls, "iceInactiveTimeout", INTEGER_SIG);
			stunCandidateKeepaliveInterval = GetFieldID(env, cls, "stunCandidateKeepaliveInterval", INTEGER_SIG);
			stableWritableConnectionPingInterval = GetFieldID(env, cls, "stableWritableConnectionPingInterval", INTEGER_SIG);
			prioritizeMostLikelyIceCandidatePairs = GetFieldID(env, cls, "prioritizeMostLikelyIceCandidatePairs", BOOLEAN_SIG);
			enableIceRenomination = GetFieldID(env, cls, "enableIceRenomination", BOOLEAN_SIG);
			presumeWritableWhenFullyRelayed = GetFieldID(env, cls, "presumeWritableWhenFullyRelayed", BOOLEAN_SIG);
			turnPortPrunePolicy = GetFieldID(env, cls, "turnPortPrunePolicy", "L" PKG "RTCPortPrunePolicy;");
			enableDscp = GetFieldID(env, cls, "enableDscp", BOOLEAN_SIG);
			enableCpuAdaptation = GetFieldID(env, cls, "enableCpuAdaptation", BOOLEAN_SIG);
			suspendBelowMinBitrate = GetFieldID(env, cls, "suspendBelowMinBitrate", BOOLEAN_SIG);
			screencastMinBitrate = GetFieldID(env, cls, "screencastMinBitrate", INTEGER_SIG);
			offerExtmapAllowMixed = GetFieldID(env, cls, "offerExtmapAllowMixed", BOOLEAN_SIG);
			enableImplicitRollback = GetFieldID(env, cls, "enableImplicitRollback", BOOLEAN_SIG);
			alwaysNegotiateDataChannels = GetFieldID(env, cls, "alwaysNegotiateDataChannels", BOOLEAN_SIG);
			networkPreference = GetFieldID(env, cls, "networkPreference", "L" PKG "RTCAdapterType;");
			turnLoggingId = GetFieldID(env, cls, "turnLoggingId", STRING_SIG);
			cryptoOptions = GetFieldID(env, cls, "cryptoOptions", "L" PKG "RTCCryptoOptions;");
		}
	}
}