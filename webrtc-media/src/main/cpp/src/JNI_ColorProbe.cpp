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

// Test-only entry point: exercises the production color resolver and scaler
// with constant planes, avoiding lossy codecs and hardware-dependent results.
#include <jni.h>
#include "media/VideoDecoder.h"
#include "media/JniBoundary.h"
#include <cstring>

namespace ffmpeg {
struct ColorProbe {
	static jintArray Convert(JNIEnv * env, jint format, jint matrix, jint range,
			jint y, jint u, jint v)
	{
		VideoDecoder decoder;
		AVFrame * source = av_frame_alloc();
		AVFrame * converted = nullptr;
		if (source == nullptr) return nullptr;
		source->width = source->height = 32;
		source->format = format == 0 ? AV_PIX_FMT_YUV444P
				: format == 1 ? AV_PIX_FMT_RGB24
				: format == 2 ? AV_PIX_FMT_YUVJ420P : AV_PIX_FMT_YUV420P;
		source->colorspace = static_cast<AVColorSpace>(matrix);
		source->color_range = static_cast<AVColorRange>(range);
		source->color_primaries = AVCOL_PRI_BT709;
		source->color_trc = AVCOL_TRC_BT709;
		int error = av_frame_get_buffer(source, 32);
		if (error >= 0) {
			if (format == 1) {
				for (int row = 0; row < 32; ++row) {
					for (int col = 0; col < 32; ++col) {
						uint8_t * pixel = source->data[0] + row * source->linesize[0] + col * 3;
						pixel[0] = y; pixel[1] = u; pixel[2] = v;
					}
				}
			}
			else {
				const int values[] = { y, u, v };
				for (int plane = 0; plane < 3; ++plane) {
					int size = format >= 2 && plane > 0 ? 16 : 32;
					for (int row = 0; row < size; ++row) {
						std::memset(source->data[plane] + row * source->linesize[plane], values[plane], size);
					}
				}
			}
			error = decoder.MakeI420(source, &converted);
		}
		jintArray result = nullptr;
		if (error >= 0) {
			jint values[] = { converted->data[0][0], converted->data[1][0], converted->data[2][0],
				converted->color_primaries, converted->color_trc, converted->colorspace, converted->color_range };
			result = env->NewIntArray(7);
			if (result != nullptr) env->SetIntArrayRegion(result, 0, 7, values);
		}
		av_frame_free(&converted);
		av_frame_free(&source);
		return result;
	}
};
}

extern "C" JNIEXPORT jintArray JNICALL Java_dev_onvoid_webrtc_media_player_ColorProbe_convert
(JNIEnv * env, jclass, jint format, jint matrix, jint range, jint y, jint u, jint v)
{
	return ffmpeg::CallFromJava(env, static_cast<jintArray>(nullptr), [&]() {
		return ffmpeg::ColorProbe::Convert(env, format, matrix, range, y, u, v);
	});
}
