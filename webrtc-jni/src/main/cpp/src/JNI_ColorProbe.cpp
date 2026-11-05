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

// The Java class exists only in media tests. Observe the actual WebRTC frame
// after the extension table and CustomVideoSource have handled it.
#include <jni.h>
#include "JavaBoundary.h"
#include "api/ExtensionApi.h"
#include "media/video/CustomVideoSource.h"
#include "api/make_ref_counted.h"
#include <cstring>

namespace {
class ColorSink : public webrtc::VideoSinkInterface<webrtc::VideoFrame> {
public:
	jint values[7] = { -1, -1, -1, -1, -1, -1, -1 };
	void OnFrame(const webrtc::VideoFrame & frame) override {
		auto pixels = frame.video_frame_buffer()->ToI420();
		values[0] = pixels->DataY()[0];
		values[1] = pixels->DataU()[0];
		values[2] = pixels->DataV()[0];
		if (frame.color_space()) {
			const auto & color = *frame.color_space();
			values[3] = static_cast<int>(color.primaries());
			values[4] = static_cast<int>(color.transfer());
			values[5] = static_cast<int>(color.matrix());
			values[6] = static_cast<int>(color.range());
		}
	}
};
}

extern "C" JNIEXPORT jintArray JNICALL Java_dev_onvoid_webrtc_media_player_ColorProbe_push
(JNIEnv * env, jclass, jintArray description, jboolean legacy)
{
	return jni::CallFromJava(env, [&]() -> jintArray {
		if (description == nullptr || env->GetArrayLength(description) != 7) return nullptr;
		jint data[7];
		env->GetIntArrayRegion(description, 0, 7, data);
		if (env->ExceptionCheck()) return nullptr;
		auto source = webrtc::make_ref_counted<jni::CustomVideoSource>(std::make_shared<jni::SyncClock>());
		ColorSink sink;
		auto * videoSource = static_cast<webrtc::VideoSourceInterface<webrtc::VideoFrame> *>(source.get());
		videoSource->AddOrUpdateSink(&sink, webrtc::VideoSinkWants());
		uint8_t y[32 * 32], u[16 * 16], v[16 * 16];
		std::memset(y, data[0], sizeof(y));
		std::memset(u, data[1], sizeof(u));
		std::memset(v, data[2], sizeof(v));
		wj_i420_frame frame = {};
		frame.width = frame.height = 32;
		frame.y = y; frame.u = u; frame.v = v;
		frame.stride_y = 32; frame.stride_u = frame.stride_v = 16;
		wj_color_space color = { static_cast<uint8_t>(data[3]), static_cast<uint8_t>(data[4]),
			static_cast<uint8_t>(data[5]), static_cast<uint8_t>(data[6]) };
		const auto * api = jni::GetExtensionApi();
		int status = legacy ? api->video_source_push(source.get(), &frame)
				: api->video_source_push_color(source.get(), &frame, &color);
		videoSource->RemoveSink(&sink);
		if (status != WEBRTC_JAVA_OK) return nullptr;
		jintArray result = env->NewIntArray(7);
		if (result != nullptr) env->SetIntArrayRegion(result, 0, 7, sink.values);
		return result;
	});
}
