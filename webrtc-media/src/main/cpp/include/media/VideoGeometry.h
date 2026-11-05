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

#ifndef WEBRTC_JAVA_MEDIA_VIDEO_GEOMETRY_H_
#define WEBRTC_JAVA_MEDIA_VIDEO_GEOMETRY_H_

extern "C" {
#include <libavformat/avformat.h>
#include <libavutil/frame.h>
}

namespace ffmpeg
{
	struct VideoSize
	{
		int width;
		int height;
	};

	// How far a picture has to be turned clockwise to stand upright, as its
	// display matrix says: 0, 90, 180 or 270. WebRTC carries a quarter turn
	// and nothing else, so a matrix that mirrors the picture or turns it by
	// another angle counts as 0: read as a turn, a mirrored picture would
	// end up on its head.
	int GetFrameRotation(const AVFrame * frame);

	// The size that shows the pixels of a picture of the stream as squares,
	// which is all WebRTC knows. It is the size of the picture itself if its
	// pixels are square. Otherwise one side grows, so that no detail is lost,
	// to an even number of pixels. A ratio beyond 4:1 is taken for a broken
	// one and ignored, as it would only make for a huge picture. Without a
	// stream, the frame is all there is to go by.
	VideoSize GetSquarePixelSize(const AVStream * stream, const AVFrame * frame);

	// The size of the video of a stream as it is shown, with square pixels
	// and turned upright.
	VideoSize GetDisplaySize(const AVStream * stream);
}

#endif
