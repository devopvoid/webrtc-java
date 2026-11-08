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

#include "JNI_RecordingProbe.h"
#include "media/ErrorText.h"
#include "media/JniBoundary.h"

#include <algorithm>
#include <string>
#include <vector>

extern "C" {
#include <libavformat/avformat.h>
}

// The Java class this belongs to exists in the tests only, so that nothing but
// them can reach it. It reads a recorded file packet by packet, which shows
// what a reader of the file's header alone does not: where each stream begins
// and whether it begins at a key frame.

namespace
{
	// What is reported per stream, in the order RecordingProbe reads it.
	constexpr size_t kValues = 5;
}

JNIEXPORT jlongArray JNICALL Java_dev_onvoid_webrtc_media_recorder_RecordingProbe_streams
(JNIEnv * env, jclass caller, jstring jPath)
{
	return ffmpeg::CallFromJava<jlongArray>(env, nullptr, [&]() -> jlongArray {
		std::string path;

		{
			ffmpeg::JavaStringChars chars(env, jPath);

			if (chars.get() == nullptr) {
				return nullptr;
			}

			path = chars.utf8();
		}

		AVFormatContext * context = nullptr;
		int result = avformat_open_input(&context, path.c_str(), nullptr, nullptr);

		if (result < 0) {
			ffmpeg::ThrowIOException(env, "Opening " + path + " failed: " + ffmpeg::ErrorText(result));
			return nullptr;
		}

		// Per stream: its type (0 video, 1 audio, 2 other), whether its first
		// packet is a key frame (1) or not (0), its first and last
		// presentation time in microseconds, and its number of packets.
		std::vector<jlong> values(context->nb_streams * kValues, 0);
		AVPacket * packet = av_packet_alloc();

		for (unsigned i = 0; i < context->nb_streams; i++) {
			const AVMediaType type = context->streams[i]->codecpar->codec_type;

			values[i * kValues] = type == AVMEDIA_TYPE_VIDEO ? 0 : type == AVMEDIA_TYPE_AUDIO ? 1 : 2;
		}

		while (packet != nullptr && av_read_frame(context, packet) >= 0) {
			const unsigned index = static_cast<unsigned>(packet->stream_index);

			if (index < context->nb_streams && packet->pts != AV_NOPTS_VALUE) {
				const int64_t pts = av_rescale_q(packet->pts, context->streams[index]->time_base,
						AVRational{ 1, 1000000 });
				jlong * stream = values.data() + index * kValues;

				if (stream[4] == 0) {
					stream[1] = (packet->flags & AV_PKT_FLAG_KEY) != 0 ? 1 : 0;
					stream[2] = pts;
					stream[3] = pts;
				}

				stream[3] = std::max<jlong>(stream[3], pts);
				stream[4]++;
			}

			av_packet_unref(packet);
		}

		av_packet_free(&packet);
		avformat_close_input(&context);

		const jsize size = static_cast<jsize>(values.size());
		jlongArray array = env->NewLongArray(size);

		if (array != nullptr) {
			env->SetLongArrayRegion(array, 0, size, values.data());
		}

		return array;
	});
}
