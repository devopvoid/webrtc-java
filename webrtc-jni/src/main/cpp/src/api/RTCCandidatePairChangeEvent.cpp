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

#include "api/RTCCandidatePairChangeEvent.h"
#include "api/RTCIceCandidate.h"
#include "JavaClasses.h"
#include "JavaString.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

#include "api/jsep.h"

#include <string>

namespace jni
{
	namespace RTCCandidatePairChangeEvent
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::CandidatePairChangeEvent & event)
		{
			const auto javaClass = JavaClasses::get<JavaRTCCandidatePairChangeEventClass>(env);

			const webrtc::Candidate & local = event.selected_candidate_pair.local_candidate();
			const webrtc::Candidate & remote = event.selected_candidate_pair.remote_candidate();

			// The pair belongs to a transport, not to an m-line; WebRTC reports
			// removed candidates in the same form.
			const webrtc::IceCandidate localCandidate(event.transport_name, -1, local);
			const webrtc::IceCandidate remoteCandidate(event.transport_name, -1, remote);

			const auto typeName = remote.type_name();

			jobject jEvent = env->NewObject(javaClass->cls, javaClass->ctor,
				RTCIceCandidate::toJava(env, &localCandidate).get(),
				RTCIceCandidate::toJava(env, &remoteCandidate).get(),
				static_cast<jlong>(event.last_data_received_ms),
				JavaString::toJava(env, event.reason).get(),
				static_cast<jlong>(event.estimated_disconnected_time_ms),
				JavaString::toJava(env, remote.address().ipaddr().ToString()).get(),
				static_cast<jint>(remote.address().port()),
				JavaString::toJava(env, std::string(typeName.data(), typeName.size())).get());

			return JavaLocalRef<jobject>(env, jEvent);
		}

		JavaRTCCandidatePairChangeEventClass::JavaRTCCandidatePairChangeEventClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG"RTCCandidatePairChangeEvent");

			ctor = GetMethod(env, cls, "<init>", "(L" PKG "RTCIceCandidate;L" PKG "RTCIceCandidate;J" STRING_SIG "J" STRING_SIG "I" STRING_SIG ")V");
		}
	}
}
