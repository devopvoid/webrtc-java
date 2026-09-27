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

#include "api/RTCEncodedFrameTransformer.h"
#include "JavaClasses.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

#include <variant>
#include <vector>

namespace jni
{
	namespace
	{
		constexpr const char * kVideoFrameCtorSig =
			"(JIJJIL" "java/lang/String;" "JZIIL" "java/lang/String;" "JII)V";

		constexpr const char * kAudioFrameCtorSig =
			"(JIJJIL" "java/lang/String;" "JII[I)V";

		jlong RtpTimestamp(const webrtc::TransformableFrameInterface & frame)
		{
			uint32_t timestamp = std::visit([](auto t) { return t.value; }, frame.GetRtpTimestampInfo());

			return static_cast<jlong>(timestamp);
		}

		jlong CaptureTimeUs(const webrtc::TransformableFrameInterface & frame)
		{
			std::optional<webrtc::Timestamp> time = frame.CaptureTime();

			return time.has_value() ? static_cast<jlong>(time->us()) : -1;
		}

		// Drops whatever the Java side left pending. Nothing called on the
		// worker may throw into C++, and a pending exception must not reach
		// the next JNI call either.
		bool ClearException(JNIEnv * env)
		{
			if (env->ExceptionCheck()) {
				env->ExceptionDescribe();
				env->ExceptionClear();

				return true;
			}

			return false;
		}
	}

	RTCEncodedFrameTransformer::RTCEncodedFrameTransformer(JNIEnv * env, jobject transformer) :
		transformer(env, transformer),
		javaFrameClass(JavaClasses::get<JavaEncodedFrameClass>(env)),
		javaVideoFrameClass(JavaClasses::get<JavaEncodedVideoFrameClass>(env)),
		javaAudioFrameClass(JavaClasses::get<JavaEncodedAudioFrameClass>(env))
	{
	}

	bool RTCEncodedFrameTransformer::Transform(JNIEnv * env, webrtc::TransformableFrameInterface & frame,
		webrtc::MediaType mediaType)
	{
		// The worker stays attached for as long as it runs, so the local
		// references of one frame must not pile up behind the next.
		if (env->PushLocalFrame(8) != JNI_OK) {
			ClearException(env);

			return false;
		}

		jobject jFrame = mediaType == webrtc::MediaType::VIDEO
			? NewVideoFrame(env, static_cast<webrtc::TransformableVideoFrameInterface &>(frame))
			: NewAudioFrame(env, static_cast<webrtc::TransformableAudioFrameInterface &>(frame));

		bool forward = false;

		if (jFrame != nullptr && !ClearException(env)) {
			// The Java side catches whatever the transform throws, reports it
			// and treats the frame as dropped; it also invalidates the Java
			// frame before returning, so nothing can reach the native frame
			// once it has moved on.
			forward = env->CallStaticBooleanMethod(javaFrameClass->cls, javaFrameClass->dispatch,
				transformer.get(), jFrame) == JNI_TRUE;

			if (ClearException(env)) {
				forward = false;
			}
		}
		else {
			ClearException(env);
		}

		env->PopLocalFrame(nullptr);

		return forward;
	}

	jstring RTCEncodedFrameTransformer::MimeType(JNIEnv * env, const std::string & mimeType)
	{
		auto found = mimeTypes.find(mimeType);

		if (found != mimeTypes.end()) {
			return found->second->get();
		}

		jstring text = env->NewStringUTF(mimeType.c_str());

		if (text == nullptr) {
			return nullptr;
		}

		auto ref = std::make_unique<JavaGlobalRef<jstring>>(env, text);
		jstring global = ref->get();

		env->DeleteLocalRef(text);

		mimeTypes.emplace(mimeType, std::move(ref));

		return global;
	}

	jobject RTCEncodedFrameTransformer::NewVideoFrame(JNIEnv * env, webrtc::TransformableVideoFrameInterface & frame)
	{
		const webrtc::VideoFrameMetadata metadata = frame.Metadata();
		const std::optional<std::string> rid = frame.Rid();
		const std::optional<int64_t> frameId = metadata.GetFrameId();

		jstring mimeType = MimeType(env, frame.GetMimeType());
		jstring jRid = nullptr;

		if (rid.has_value() && !rid->empty()) {
			jRid = env->NewStringUTF(rid->c_str());
		}

		return env->NewObject(javaVideoFrameClass->cls, javaVideoFrameClass->ctor,
			reinterpret_cast<jlong>(&frame),
			static_cast<jint>(frame.GetData().size()),
			RtpTimestamp(frame),
			static_cast<jlong>(frame.GetSsrc()),
			static_cast<jint>(frame.GetPayloadType()),
			mimeType,
			CaptureTimeUs(frame),
			frame.IsKeyFrame() ? JNI_TRUE : JNI_FALSE,
			static_cast<jint>(metadata.GetWidth()),
			static_cast<jint>(metadata.GetHeight()),
			jRid,
			static_cast<jlong>(frameId.value_or(-1)),
			static_cast<jint>(metadata.GetSpatialIndex()),
			static_cast<jint>(metadata.GetTemporalIndex()));
	}

	jobject RTCEncodedFrameTransformer::NewAudioFrame(JNIEnv * env, webrtc::TransformableAudioFrameInterface & frame)
	{
		const std::span<const uint32_t> sources = frame.GetContributingSources();
		const std::optional<uint16_t> sequenceNumber = frame.SequenceNumber();
		const std::optional<uint8_t> audioLevel = frame.AudioLevel();

		jstring mimeType = MimeType(env, frame.GetMimeType());
		jintArray csrcs = nullptr;

		if (!sources.empty()) {
			csrcs = env->NewIntArray(static_cast<jsize>(sources.size()));

			if (csrcs == nullptr) {
				return nullptr;
			}

			std::vector<jint> values(sources.begin(), sources.end());

			env->SetIntArrayRegion(csrcs, 0, static_cast<jsize>(values.size()), values.data());
		}

		return env->NewObject(javaAudioFrameClass->cls, javaAudioFrameClass->ctor,
			reinterpret_cast<jlong>(&frame),
			static_cast<jint>(frame.GetData().size()),
			RtpTimestamp(frame),
			static_cast<jlong>(frame.GetSsrc()),
			static_cast<jint>(frame.GetPayloadType()),
			mimeType,
			CaptureTimeUs(frame),
			static_cast<jint>(sequenceNumber.has_value() ? *sequenceNumber : -1),
			static_cast<jint>(audioLevel.has_value() ? *audioLevel : -1),
			csrcs);
	}

	RTCEncodedFrameTransformer::JavaEncodedFrameClass::JavaEncodedFrameClass(JNIEnv * env)
	{
		cls = FindClass(env, PKG"RTCEncodedFrame");

		dispatch = GetStaticMethod(env, cls, "dispatch",
			"(L" PKG "RTCEncodedFrameTransformer;L" PKG "RTCEncodedFrame;)Z");
	}

	RTCEncodedFrameTransformer::JavaEncodedVideoFrameClass::JavaEncodedVideoFrameClass(JNIEnv * env)
	{
		cls = FindClass(env, PKG"RTCEncodedVideoFrame");

		ctor = GetMethod(env, cls, "<init>", kVideoFrameCtorSig);
	}

	RTCEncodedFrameTransformer::JavaEncodedAudioFrameClass::JavaEncodedAudioFrameClass(JNIEnv * env)
	{
		cls = FindClass(env, PKG"RTCEncodedAudioFrame");

		ctor = GetMethod(env, cls, "<init>", kAudioFrameCtorSig);
	}
}
