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

#include "media/video/codec/VideoCodecInfo.h"
#include "Exception.h"
#include "JavaClasses.h"
#include "JavaHashMap.h"
#include "JavaObject.h"
#include "JavaString.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

#include "api/video_codecs/scalability_mode.h"
#include "modules/video_coding/svc/scalability_mode_util.h"

#include <string>

namespace jni
{
	namespace VideoCodecInfo
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::SdpVideoFormat & format)
		{
			const auto javaClass = JavaClasses::get<JavaVideoCodecInfoClass>(env);

			JavaHashMap parameters(env);

			for (const auto & [key, value] : format.parameters) {
				parameters.put(JavaString::toJava(env, key), JavaString::toJava(env, value));
			}

			std::vector<std::string> modes;

			for (webrtc::ScalabilityMode mode : format.scalability_modes) {
				modes.emplace_back(webrtc::ScalabilityModeToString(mode));
			}

			JavaLocalRef<jstring> name = JavaString::toJava(env, format.name);
			JavaLocalRef<jobject> parameterMap = parameters;
			JavaLocalRef<jobjectArray> modeArray = JavaString::createArray(env, modes);

			jobject info = env->NewObject(javaClass->cls, javaClass->ctor, name.get(),
				parameterMap.get(), modeArray.get());

			ExceptionCheck(env);

			return JavaLocalRef<jobject>(env, info);
		}

		webrtc::SdpVideoFormat toNative(JNIEnv * env, const JavaRef<jobject> & info)
		{
			const auto javaClass = JavaClasses::get<JavaVideoCodecInfoClass>(env);

			JavaObject obj(env, info);

			webrtc::SdpVideoFormat format(JavaString::toNative(env, obj.getString(javaClass->name)));

			// The field always holds a HashMap.
			for (const auto & entry : JavaHashMap(env, obj.getObject(javaClass->parameters))) {
				std::string key = JavaString::toNative(env, static_java_ref_cast<jstring>(env, entry.first));
				std::string value = JavaString::toNative(env, static_java_ref_cast<jstring>(env, entry.second));

				format.parameters.emplace(std::move(key), std::move(value));
			}

			JavaLocalRef<jobjectArray> modes = obj.getObjectArray(javaClass->scalabilityModes);
			jsize count = modes.get() != nullptr ? env->GetArrayLength(modes.get()) : 0;

			for (jsize i = 0; i < count; i++) {
				JavaLocalRef<jstring> mode(env, static_cast<jstring>(env->GetObjectArrayElement(modes.get(), i)));
				auto scalabilityMode = webrtc::ScalabilityModeFromString(JavaString::toNative(env, mode));

				// A mode WebRTC does not know cannot be negotiated anyway.
				if (scalabilityMode.has_value()) {
					format.scalability_modes.push_back(*scalabilityMode);
				}
			}

			return format;
		}

		JavaLocalRef<jobjectArray> toJavaArray(JNIEnv * env, const std::vector<webrtc::SdpVideoFormat> & formats)
		{
			const auto javaClass = JavaClasses::get<JavaVideoCodecInfoClass>(env);

			jobjectArray array = env->NewObjectArray(static_cast<jsize>(formats.size()), javaClass->cls, nullptr);

			ExceptionCheck(env);

			for (size_t i = 0; i < formats.size(); i++) {
				JavaLocalRef<jobject> info = toJava(env, formats[i]);

				env->SetObjectArrayElement(array, static_cast<jsize>(i), info.get());
			}

			return JavaLocalRef<jobjectArray>(env, array);
		}

		std::vector<webrtc::SdpVideoFormat> toNativeList(JNIEnv * env, const JavaRef<jobject> & list)
		{
			std::vector<webrtc::SdpVideoFormat> formats;

			if (list.get() == nullptr) {
				return formats;
			}

			const auto javaClass = JavaClasses::get<JavaVideoCodecInfoClass>(env);

			JavaLocalRef<jobjectArray> array(env,
				static_cast<jobjectArray>(env->CallObjectMethod(list, javaClass->listToArray)));

			ExceptionCheck(env);

			jsize count = env->GetArrayLength(array.get());

			for (jsize i = 0; i < count; i++) {
				JavaLocalRef<jobject> info(env, env->GetObjectArrayElement(array.get(), i));

				if (info.get() == nullptr) {
					continue;
				}
				if (!env->IsInstanceOf(info.get(), javaClass->cls)) {
					throw Exception("The list of supported codecs holds an object that is not a VideoCodecInfo");
				}

				formats.push_back(toNative(env, info));
			}

			return formats;
		}

		JavaVideoCodecInfoClass::JavaVideoCodecInfoClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG_CODEC"VideoCodecInfo");

			ctor = GetMethod(env, cls, "<init>", "(" STRING_SIG MAP_SIG "[" STRING_SIG ")V");

			name = GetFieldID(env, cls, "name", STRING_SIG);
			parameters = GetFieldID(env, cls, "parameters", "Ljava/util/HashMap;");
			scalabilityModes = GetFieldID(env, cls, "scalabilityModes", "[" STRING_SIG);

			jclass listClass = FindClass(env, "java/util/List");

			listToArray = GetMethod(env, listClass, "toArray", "()[Ljava/lang/Object;");
		}
	}
}
