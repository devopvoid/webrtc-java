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

#include "media/VideoGeometry.h"

extern "C" {
#include <libavutil/display.h>
#include <libavutil/mathematics.h>
}

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <utility>

namespace
{
	// The size of a display matrix.
	constexpr size_t kMatrixSize = 9 * sizeof(int32_t);

	// How far a pixel may be from square, either way.
	constexpr int64_t kMaxPixelAspect = 4;

	// How far from a quarter turn an angle may be, in degrees, and still count
	// as one: the matrix holds fixed-point numbers.
	constexpr double kAngleTolerance = 2.0;

	int GetRotation(const uint8_t * data, size_t size)
	{
		if (data == nullptr || size < kMatrixSize) {
			return 0;
		}

		const int32_t * matrix = reinterpret_cast<const int32_t *>(data);

		// A negative determinant is a mirror image.
		const int64_t determinant = static_cast<int64_t>(matrix[0]) * matrix[4]
				- static_cast<int64_t>(matrix[1]) * matrix[3];

		if (determinant <= 0) {
			return 0;
		}

		// Counter-clockwise, in the range of -180 to 180.
		const double angle = av_display_rotation_get(matrix);

		if (std::isnan(angle)) {
			return 0;
		}

		const long quarters = std::lround(angle / 90.0);

		if (std::fabs(angle - quarters * 90.0) > kAngleTolerance) {
			return 0;
		}

		return static_cast<int>((4 - quarters % 4) % 4) * 90;
	}

	bool IsNonSquare(AVRational ratio)
	{
		return ratio.num > 0 && ratio.den > 0 && ratio.num != ratio.den;
	}

	// The container goes first, as it does everywhere in FFmpeg: that is where
	// a wrong value in the stream gets corrected. But where it has 1:1, that
	// need not be a statement: Matroska gives it to any track without a display
	// size, whatever the stream says.
	AVRational GetPixelAspect(const AVStream * stream, const AVFrame * frame)
	{
		if (stream != nullptr && IsNonSquare(stream->sample_aspect_ratio)) {
			return stream->sample_aspect_ratio;
		}

		return frame != nullptr ? frame->sample_aspect_ratio : stream->codecpar->sample_aspect_ratio;
	}

	// Rounded to the nearest even number, for the chroma planes of I420.
	int ScaleToEven(int size, int64_t num, int64_t den)
	{
		return static_cast<int>(2 * av_rescale(size, num, 2 * den));
	}

	ffmpeg::VideoSize ToSquarePixels(int width, int height, AVRational aspect)
	{
		const int64_t num = aspect.num;
		const int64_t den = aspect.den;

		if (!IsNonSquare(aspect) || num > kMaxPixelAspect * den || den > kMaxPixelAspect * num) {
			return { width, height };
		}

		if (num > den) {
			return { ScaleToEven(width, num, den), height };
		}

		return { width, ScaleToEven(height, den, num) };
	}
}

namespace ffmpeg
{
	int GetFrameRotation(const AVFrame * frame)
	{
		const AVFrameSideData * matrix = av_frame_get_side_data(frame, AV_FRAME_DATA_DISPLAYMATRIX);

		return matrix != nullptr ? GetRotation(matrix->data, matrix->size) : 0;
	}

	VideoSize GetSquarePixelSize(const AVStream * stream, const AVFrame * frame)
	{
		return ToSquarePixels(frame->width, frame->height, GetPixelAspect(stream, frame));
	}

	VideoSize GetDisplaySize(const AVStream * stream)
	{
		const AVCodecParameters * parameters = stream->codecpar;

		VideoSize size = ToSquarePixels(parameters->width, parameters->height,
				GetPixelAspect(stream, nullptr));

		const AVPacketSideData * matrix = av_packet_side_data_get(parameters->coded_side_data,
				parameters->nb_coded_side_data, AV_PKT_DATA_DISPLAYMATRIX);

		if (matrix != nullptr && GetRotation(matrix->data, matrix->size) % 180 != 0) {
			std::swap(size.width, size.height);
		}

		return size;
	}
}
