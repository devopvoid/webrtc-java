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
	AudioTrackSink::AudioTrackSink(JNIEnv * env, const JavaGlobalRef<jobject> & sink, bool packetInfos) :
		sink(sink),
		packetInfos(packetInfos),
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

		JavaLocalRef<jbyteArray> dataArray(env, env->NewByteArray(dataSize));

		if (dataArray.get() != nullptr) {
			env->SetByteArrayRegion(dataArray.get(), 0, dataSize, buffer);

			if (packetInfos) {
				try {
					JavaLocalRef<jobjectArray> infoArray = (infos != nullptr)
						? RTCRtpPacketInfo::toJavaArray(env, *infos)
						: RTCRtpPacketInfo::toJavaArray(env, webrtc::RtpPacketInfos());

					env->CallVoidMethod(sink, javaClass->onDataWithPacketInfos, dataArray.get(),
						static_cast<jint>(bitsPerSample), static_cast<jint>(sampleRate),
						static_cast<jint>(channels), static_cast<jint>(frames), infoArray.get());
				}
				catch (...) {
					ThrowCxxJavaException(env);
				}
			}
			else {
				env->CallVoidMethod(sink, javaClass->onData, dataArray.get(), static_cast<jint>(bitsPerSample),
					static_cast<jint>(sampleRate), static_cast<jint>(channels), static_cast<jint>(frames));
			}
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
