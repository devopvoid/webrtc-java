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

#include "api/AudioTrackSink.h"
#include "api/RTCRtpPacketInfo.h"
#include "api/WebRTCUtils.h"
#include "JavaClasses.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	AudioTrackSink::AudioTrackSink(JNIEnv * env, const JavaGlobalRef<jobject> & sink, bool packetInfos,
		bool reuseData) :
		sink(sink),
		packetInfos(packetInfos),
		reuseData(reuseData),
		reusedArray(nullptr),
		reusedSize(0),
		reusedInUse(false),
		javaClass(JavaClasses::get<JavaAudioTrackSinkClass>(env))
	{
	}

	void AudioTrackSink::OnData(const void * data, int bitsPerSample, int sampleRate, size_t channels, size_t frames)
	{
		Deliver(data, bitsPerSample, sampleRate, channels, frames, nullptr);
	}

	void AudioTrackSink::OnData(const void * data, int bitsPerSample, int sampleRate, size_t channels, size_t frames,
		std::optional<int64_t> absoluteCaptureTimestampMs)
	{
		Deliver(data, bitsPerSample, sampleRate, channels, frames, nullptr);
	}

	void AudioTrackSink::OnData(const void * data, int bitsPerSample, int sampleRate, size_t channels, size_t frames,
		std::optional<int64_t> absoluteCaptureTimestampMs, const webrtc::RtpPacketInfos & packetInfos)
	{
		Deliver(data, bitsPerSample, sampleRate, channels, frames, &packetInfos);
	}

	void AudioTrackSink::Deliver(const void * data, int bitsPerSample, int sampleRate, size_t channels, size_t frames,
		const webrtc::RtpPacketInfos * infos)
	{
		JNIEnv * env = AttachCurrentThread();

		if (env == nullptr) {
			return;
		}

		const jbyte * buffer = static_cast<const jbyte *>(data);
		jsize dataSize = static_cast<jsize>(frames * channels * (bitsPerSample / 8));

		// The reused array belongs to one call at a time. A call that finds it
		// in use, should a source deliver on two threads at once, falls back
		// to an array of its own.
		const bool reusing = reuseData && !reusedInUse.exchange(true, std::memory_order_acquire);

		JavaLocalRef<jbyteArray> ownArray(env, reusing ? nullptr : env->NewByteArray(dataSize));
		jbyteArray dataArray = ownArray.get();

		if (reusing) {
			if (reusedArray.get() == nullptr || reusedSize != dataSize) {
				// Its length is that of the audio, as with a new array per call.
				JavaLocalRef<jbyteArray> array(env, env->NewByteArray(dataSize));

				reusedArray = (array.get() != nullptr)
					? JavaGlobalRef<jbyteArray>(env, array.get())
					: JavaGlobalRef<jbyteArray>(nullptr);
				reusedSize = dataSize;
			}

			dataArray = reusedArray.get();
		}

		if (dataArray != nullptr) {
			env->SetByteArrayRegion(dataArray, 0, dataSize, buffer);

			if (packetInfos) {
				try {
					JavaLocalRef<jobjectArray> infoArray = (infos != nullptr)
						? RTCRtpPacketInfo::toJavaArray(env, *infos)
						: RTCRtpPacketInfo::toJavaArray(env, webrtc::RtpPacketInfos());

					env->CallVoidMethod(sink, javaClass->onDataWithPacketInfos, dataArray,
						static_cast<jint>(bitsPerSample), static_cast<jint>(sampleRate),
						static_cast<jint>(channels), static_cast<jint>(frames), infoArray.get());
				}
				catch (...) {
					ThrowCxxJavaException(env);
				}
			}
			else {
				env->CallVoidMethod(sink, javaClass->onData, dataArray, static_cast<jint>(bitsPerSample),
					static_cast<jint>(sampleRate), static_cast<jint>(channels), static_cast<jint>(frames));
			}
		}

		if (reusing) {
			reusedInUse.store(false, std::memory_order_release);
		}

		ReportPendingException(env);
	}

	AudioTrackSink::JavaAudioTrackSinkClass::JavaAudioTrackSinkClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG_AUDIO"AudioTrackSink");

		onData = GetMethod(env, cls, "onData", "([BIIII)V");
		onDataWithPacketInfos = GetMethod(env, cls, "onData", "([BIIII[L" PKG "RTCRtpPacketInfo;)V");
	}
}
