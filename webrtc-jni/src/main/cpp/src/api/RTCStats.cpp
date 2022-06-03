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

#include "api/RTCStats.h"
#include "JavaBigInteger.h"
#include "JavaClasses.h"
#include "JavaEnums.h"
#include "JavaHashMap.h"
#include "JavaPrimitive.h"
#include "JavaString.h"
#include "JNI_WebRTC.h"

#include "rtc_base/logging.h"
#include "rtc_base/string_encode.h"

#include <map>
#include <string>

namespace jni
{
	namespace RTCStats
	{
		std::map<std::string, uint8_t> initTypeMap()
		{
			std::string typeNames[] = {
				"codec",
				"inbound-rtp",
				"outbound-rtp",
				"remote-inbound-rtp",
				"remote-outbound-rtp",
				"media-source",
				"csrc",
				"peer-connection",
				"data-channel",
				"stream",
				"track",
				"sender",
				"receiver",
				"transport",
				"candidate-pair",
				"local-candidate",
				"remote-candidate",
				"certificate",
				"ice-server"
			};

			std::map<std::string, uint8_t> map;
			uint8_t index = 0;

			for (const auto & name : typeNames) {
				map[name] = index++;
			}

			return map;
		}

		const std::map<std::string, uint8_t> typeMap = initTypeMap();


		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::RTCStats & stats)
		{
			const auto javaClass = JavaClasses::get<JavaRTCStatsClass>(env);

			JavaHashMap memberMap(env);

			for (const auto * member : stats.Members()) {
				if (!member->is_defined()) {
					continue;
				}

				JavaLocalRef<jstring> key = JavaString::toJava(env, member->name());
				JavaLocalRef<jobject> value = toJava(env, *member);

				memberMap.put(key, value);
			}

			JavaLocalRef<jobject> type = nullptr;

			auto result = typeMap.find(stats.type());
			if (result != typeMap.end()) {
				type = jni::JavaEnums::toJava(env, static_cast<RTCStatsType>(result->second));
			}
			else {
				RTC_LOG(LS_WARNING) << "No Java Enum for '" << stats.type() << "' found";
			}

			jobject obj = env->NewObject(javaClass->cls, javaClass->ctor,
				stats.timestamp_us(),
				type ? type.get() : type,
				JavaString::toJava(env, stats.id()).get(),
				((JavaLocalRef<jobject>)memberMap).get());

			return JavaLocalRef<jobject>(env, obj);
		}

		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::RTCStatsMemberInterface & member)
		{
			switch (member.type()) {
				case webrtc::RTCStatsMemberInterface::kBool:
					return Boolean::create(env, *member.cast_to<webrtc::RTCStatsMember<bool>>());

				case webrtc::RTCStatsMemberInterface::kInt32:
					return Integer::create(env, *member.cast_to<webrtc::RTCStatsMember<int32_t>>());

				case webrtc::RTCStatsMemberInterface::kUint32:
					return Long::create(env, *member.cast_to<webrtc::RTCStatsMember<uint32_t>>());

				case webrtc::RTCStatsMemberInterface::kInt64:
					return Long::create(env, *member.cast_to<webrtc::RTCStatsMember<int64_t>>());

				case webrtc::RTCStatsMemberInterface::kUint64:
					return JavaBigInteger::toJava(env, rtc::ToString(*member.cast_to<webrtc::RTCStatsMember<uint64_t>>()));

				case webrtc::RTCStatsMemberInterface::kDouble:
					return Double::create(env, *member.cast_to<webrtc::RTCStatsMember<double>>());

				case webrtc::RTCStatsMemberInterface::kString:
					return jni::static_java_ref_cast<jobject>(env,
						JavaString::toJava(env, *member.cast_to<webrtc::RTCStatsMember<std::string>>()));

				case webrtc::RTCStatsMemberInterface::kSequenceBool:
					return jni::static_java_ref_cast<jobject>(env,
						Boolean::createArray(env, *member.cast_to<webrtc::RTCStatsMember<std::vector<bool>>>()));

				case webrtc::RTCStatsMemberInterface::kSequenceInt32:
					return jni::static_java_ref_cast<jobject>(env,
						Integer::createArray(env, *member.cast_to<webrtc::RTCStatsMember<std::vector<int32_t>>>()));

				case webrtc::RTCStatsMemberInterface::kSequenceUint32:
				{
					const std::vector<uint32_t> & v = *member.cast_to<webrtc::RTCStatsMember<std::vector<uint32_t>>>();
					return jni::static_java_ref_cast<jobject>(env,
						Long::createArray(env, std::vector<int64_t>(v.begin(), v.end())));
				}

				case webrtc::RTCStatsMemberInterface::kSequenceInt64:
					return jni::static_java_ref_cast<jobject>(env,
						Long::createArray(env, *member.cast_to<webrtc::RTCStatsMember<std::vector<int64_t>>>()));

				case webrtc::RTCStatsMemberInterface::kSequenceUint64:
				{
					const std::vector<uint64_t> & v = *member.cast_to<webrtc::RTCStatsMember<std::vector<uint64_t>>>();
					std::vector<std::string> r;

					std::transform(v.begin(), v.end(), std::back_inserter(r),
						[](uint64_t n) { return rtc::ToString(n); });

					return jni::static_java_ref_cast<jobject>(env, JavaBigInteger::createArray(env, r));
				}

				case webrtc::RTCStatsMemberInterface::kSequenceDouble:
					return jni::static_java_ref_cast<jobject>(env,
						Double::createArray(env, *member.cast_to<webrtc::RTCStatsMember<std::vector<double>>>()));

				case webrtc::RTCStatsMemberInterface::kSequenceString:
					return jni::static_java_ref_cast<jobject>(env,
						JavaString::createArray(env, *member.cast_to<webrtc::RTCStatsMember<std::vector<std::string>>>()));

				case webrtc::RTCStatsMemberInterface::kMapStringDouble:
				{
					std::map<std::string, double> map = *member.cast_to<webrtc::RTCStatsMember<std::map<std::string, double>>>();

					JavaHashMap memberMap(env);

					for (const auto& item : map) {
						memberMap.put(jni::static_java_ref_cast<jobject>(env, JavaString::toJava(env, item.first)),
							Double::create(env, item.second));
					}

					return jni::static_java_ref_cast<jobject>(env, memberMap);
				}

				case webrtc::RTCStatsMemberInterface::kMapStringUint64:
				{
					std::map<std::string, uint64_t> map = *member.cast_to<webrtc::RTCStatsMember<std::map<std::string, uint64_t>>>();

					JavaHashMap memberMap(env);

					for (const auto& item : map) {
						memberMap.put(jni::static_java_ref_cast<jobject>(env, JavaString::toJava(env, item.first)),
							JavaBigInteger::toJava(env, rtc::ToString(item.second)));
					}

					return jni::static_java_ref_cast<jobject>(env, memberMap);
				}
			}

			return nullptr;
		}

		JavaRTCStatsClass::JavaRTCStatsClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG"RTCStats");

			ctor = GetMethod(env, cls, "<init>", "(JL" PKG "RTCStatsType;" STRING_SIG MAP_SIG ")V");
		}
	}
}