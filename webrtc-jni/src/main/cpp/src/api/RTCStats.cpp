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

#include "api/stats/attribute.h"
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

			JavaHashMap attributeMap(env);

			for (const auto & attribute : stats.Attributes()) {
				if (!attribute.has_value()) {
					continue;
				}

				JavaLocalRef<jstring> key = JavaString::toJava(env, attribute.name());
				JavaLocalRef<jobject> value = toJava(env, attribute);

				attributeMap.put(key, value);
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
				stats.timestamp(),
				type ? type.get() : type,
				JavaString::toJava(env, stats.id()).get(),
				((JavaLocalRef<jobject>)attributeMap).get());

			return JavaLocalRef<jobject>(env, obj);
		}

		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::Attribute & attribute)
		{
			if (attribute.holds_alternative<bool>()) {
				return Boolean::create(env, attribute.get<bool>());
			}
			else if (attribute.holds_alternative<int32_t>()) {
				return Integer::create(env, attribute.get<int32_t>());
			}
			else if (attribute.holds_alternative<uint32_t>()) {
				return Long::create(env, attribute.get<uint32_t>());
			}
			else if (attribute.holds_alternative<int64_t>()) {
				return Long::create(env, attribute.get<int64_t>());
			}
			else if (attribute.holds_alternative<uint64_t>()) {
				return JavaBigInteger::toJava(env, std::to_string(attribute.get<uint64_t>()));
			}
			else if (attribute.holds_alternative<double>()) {
				return Double::create(env, attribute.get<double>());
			}
			else if (attribute.holds_alternative<std::string>()) {
				return jni::static_java_ref_cast<jobject>(env, JavaString::toJava(env, attribute.get<std::string>()));
			}
			else if (attribute.holds_alternative<std::vector<bool>>()) {
				return jni::static_java_ref_cast<jobject>(env, Boolean::createArray(env, attribute.get<std::vector<bool>>()));
			}
			else if (attribute.holds_alternative<std::vector<int32_t>>()) {
				return jni::static_java_ref_cast<jobject>(env, Integer::createArray(env, attribute.get<std::vector<int32_t>>()));
			}
			else if (attribute.holds_alternative<std::vector<uint32_t>>()) {
				const std::vector<uint32_t> & v = attribute.get<std::vector<uint32_t>>();
				return jni::static_java_ref_cast<jobject>(env, Long::createArray(env, std::vector<int64_t>(v.begin(), v.end())));
			}
			else if (attribute.holds_alternative<std::vector<int64_t>>()) {
				return jni::static_java_ref_cast<jobject>(env, Long::createArray(env, attribute.get<std::vector<int64_t>>()));
			}
			else if (attribute.holds_alternative<std::vector<uint64_t>>()) {
				const std::vector<uint64_t> & v = attribute.get<std::vector<uint64_t>>();
				std::vector<std::string> r;

				std::transform(v.begin(), v.end(), std::back_inserter(r),
					[](uint64_t n) { return std::to_string(n); });

				return jni::static_java_ref_cast<jobject>(env, JavaBigInteger::createArray(env, r));
			}
			else if (attribute.holds_alternative<std::vector<double>>()) {
				return jni::static_java_ref_cast<jobject>(env, Double::createArray(env, attribute.get<std::vector<double>>()));
			}
			else if (attribute.holds_alternative<std::vector<std::string>>()) {
				return jni::static_java_ref_cast<jobject>(env, JavaString::createArray(env, attribute.get<std::vector<std::string>>()));
			}
			else if (attribute.holds_alternative<std::map<std::string, uint64_t>>()) {
				std::map<std::string, uint64_t> map = attribute.get<std::map<std::string, uint64_t>>();

				JavaHashMap memberMap(env);

				for (const auto & item : map) {
					memberMap.put(jni::static_java_ref_cast<jobject>(env, JavaString::toJava(env, item.first)),
						JavaBigInteger::toJava(env, std::to_string(item.second)));
				}

				return jni::static_java_ref_cast<jobject>(env, memberMap);
			}
			else if (attribute.holds_alternative<std::map<std::string, double>>()) {
				std::map<std::string, double> map = attribute.get<std::map<std::string, double>>();

				JavaHashMap memberMap(env);

				for (const auto& item : map) {
					memberMap.put(jni::static_java_ref_cast<jobject>(env, JavaString::toJava(env, item.first)),
						Double::create(env, item.second));
				}

				return jni::static_java_ref_cast<jobject>(env, memberMap);
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