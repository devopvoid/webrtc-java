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

#include "api/RTCRtpEncodingParameters.h"
#include "api/RTCResolutionRestriction.h"
#include "api/RTCRtpCodecCapability.h"
#include "JavaClasses.h"
#include "JavaEnums.h"
#include "JavaObject.h"
#include "JavaPrimitive.h"
#include "JavaRef.h"
#include "JavaString.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace RTCRtpEncodingParameters
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::RtpEncodingParameters & parameters)
		{
			const auto javaClass = JavaClasses::get<JavaRTCRtpEncodingParametersClass>(env);

			jobject object = env->NewObject(javaClass->cls, javaClass->ctor);
			env->SetObjectField(object, javaClass->active, Boolean::create(env, parameters.active));

			if (parameters.ssrc.has_value()) {
				env->SetObjectField(object, javaClass->ssrc, Long::create(env, parameters.ssrc.value()));
			}
			if (parameters.min_bitrate_bps.has_value()) {
				env->SetObjectField(object, javaClass->minBitrate, Integer::create(env, parameters.min_bitrate_bps.value()));
			}
			if (parameters.max_bitrate_bps.has_value()) {
				env->SetObjectField(object, javaClass->maxBitrate, Integer::create(env, parameters.max_bitrate_bps.value()));
			}
			if (parameters.max_framerate.has_value()) {
				env->SetObjectField(object, javaClass->maxFramerate, Double::create(env, parameters.max_framerate.value()));
			}
			if (parameters.scale_resolution_down_by.has_value()) {
				env->SetObjectField(object, javaClass->scaleResolution, Double::create(env, parameters.scale_resolution_down_by.value()));
			}
			if (!parameters.rid.empty()) {
				env->SetObjectField(object, javaClass->rid, JavaString::toJava(env, parameters.rid).get());
			}
			if (parameters.scale_resolution_down_to.has_value()) {
				env->SetObjectField(object, javaClass->scaleResolutionDownTo,
					RTCResolutionRestriction::toJava(env, *parameters.scale_resolution_down_to).get());
			}
			if (parameters.scalability_mode.has_value()) {
				env->SetObjectField(object, javaClass->scalabilityMode,
					JavaString::toJava(env, *parameters.scalability_mode).get());
			}
			if (parameters.num_temporal_layers.has_value()) {
				env->SetObjectField(object, javaClass->numTemporalLayers,
					Integer::create(env, *parameters.num_temporal_layers).get());
			}
			if (parameters.codec.has_value()) {
				// The Java type of a codec is the capability, which adds to
				// the codec what the encoding does not carry.
				webrtc::RtpCodecCapability capability;
				static_cast<webrtc::RtpCodec &>(capability) = *parameters.codec;

				env->SetObjectField(object, javaClass->codec, RTCRtpCodecCapability::toJava(env, capability).get());
			}

			env->SetObjectField(object, javaClass->bitratePriority, Double::create(env, parameters.bitrate_priority).get());
			env->SetObjectField(object, javaClass->networkPriority, JavaEnums::toJava(env, parameters.network_priority).get());
			env->SetObjectField(object, javaClass->adaptivePtime, Boolean::create(env, parameters.adaptive_ptime).get());

			return JavaLocalRef<jobject>(env, object);
		}

		webrtc::RtpEncodingParameters toNative(JNIEnv * env, const JavaRef<jobject> & parameters)
		{
			const auto javaClass = JavaClasses::get<JavaRTCRtpEncodingParametersClass>(env);

			JavaObject obj(env, parameters);

			auto active = obj.getObject(javaClass->active);
			auto ssrc = obj.getObject(javaClass->ssrc);
			auto minBitrate = obj.getObject(javaClass->minBitrate);
			auto maxBitrate = obj.getObject(javaClass->maxBitrate);
			auto maxFramerate = obj.getObject(javaClass->maxFramerate);
			auto scaleResolution = obj.getObject(javaClass->scaleResolution);
			auto rid = obj.getString(javaClass->rid);
			auto scaleResolutionDownTo = obj.getObject(javaClass->scaleResolutionDownTo);
			auto scalabilityMode = obj.getString(javaClass->scalabilityMode);
			auto numTemporalLayers = obj.getObject(javaClass->numTemporalLayers);
			auto bitratePriority = obj.getObject(javaClass->bitratePriority);
			auto networkPriority = obj.getObject(javaClass->networkPriority);
			auto adaptivePtime = obj.getObject(javaClass->adaptivePtime);
			auto codec = obj.getObject(javaClass->codec);

			webrtc::RtpEncodingParameters params;

			if (active.get()) {
				params.active = Boolean::getValue(env, active);
			}
			if (ssrc.get()) {
				params.ssrc = static_cast<uint32_t>(Long::getValue(env, ssrc));
			}
			if (minBitrate.get()) {
				params.min_bitrate_bps.emplace(Integer::getValue(env, minBitrate));
			}
			if (maxBitrate.get()) {
				params.max_bitrate_bps.emplace(Integer::getValue(env, maxBitrate));
			}
			if (maxFramerate.get()) {
				params.max_framerate.emplace(Double::getValue(env, maxFramerate));
			}
			if (scaleResolution.get()) {
				params.scale_resolution_down_by.emplace(Double::getValue(env, scaleResolution));
			}
			if (rid.get()) {
				params.rid = JavaString::toNative(env, rid);
			}
			if (scaleResolutionDownTo.get()) {
				params.scale_resolution_down_to = RTCResolutionRestriction::toNative(env, scaleResolutionDownTo);
			}
			if (scalabilityMode.get()) {
				params.scalability_mode = JavaString::toNative(env, scalabilityMode);
			}
			if (numTemporalLayers.get()) {
				params.num_temporal_layers = Integer::getValue(env, numTemporalLayers);
			}
			if (bitratePriority.get()) {
				params.bitrate_priority = Double::getValue(env, bitratePriority);
			}
			if (networkPriority.get()) {
				params.network_priority = JavaEnums::toNative<webrtc::Priority>(env, networkPriority);
			}
			if (adaptivePtime.get()) {
				params.adaptive_ptime = Boolean::getValue(env, adaptivePtime);
			}
			if (codec.get()) {
				params.codec = static_cast<webrtc::RtpCodec>(RTCRtpCodecCapability::toNative(env, codec));
			}

			return params;
		}

		JavaRTCRtpEncodingParametersClass::JavaRTCRtpEncodingParametersClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG"RTCRtpEncodingParameters");

			ctor = GetMethod(env, cls, "<init>", "()V");

			ssrc = GetFieldID(env, cls, "ssrc", LONG_SIG);
			active = GetFieldID(env, cls, "active", BOOLEAN_SIG);
			minBitrate = GetFieldID(env, cls, "minBitrate", INTEGER_SIG);
			maxBitrate = GetFieldID(env, cls, "maxBitrate", INTEGER_SIG);
			maxFramerate = GetFieldID(env, cls, "maxFramerate", DOUBLE_SIG);
			scaleResolution = GetFieldID(env, cls, "scaleResolutionDownBy", DOUBLE_SIG);
			rid = GetFieldID(env, cls, "rid", STRING_SIG);
			scaleResolutionDownTo = GetFieldID(env, cls, "scaleResolutionDownTo", "L" PKG "RTCResolutionRestriction;");
			scalabilityMode = GetFieldID(env, cls, "scalabilityMode", STRING_SIG);
			numTemporalLayers = GetFieldID(env, cls, "numTemporalLayers", INTEGER_SIG);
			bitratePriority = GetFieldID(env, cls, "bitratePriority", DOUBLE_SIG);
			networkPriority = GetFieldID(env, cls, "networkPriority", "L" PKG "RTCPriorityType;");
			adaptivePtime = GetFieldID(env, cls, "adaptivePtime", BOOLEAN_SIG);
			codec = GetFieldID(env, cls, "codec", "L" PKG "RTCRtpCodecCapability;");
		}
	}
}
