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

#include "media/video/codec/nvdec/NvdecVideoDecoder.h"

#include "api/video/i420_buffer.h"
#include "api/video/video_frame.h"
#include "modules/video_coding/include/video_error_codes.h"
#include "rtc_base/logging.h"
#include "third_party/libyuv/include/libyuv/convert.h"

#include <algorithm>
#include <iterator>

namespace jni
{
	namespace
	{
		cudaVideoCodec CudaCodecOf(webrtc::VideoCodecType codec)
		{
			return codec == webrtc::kVideoCodecVP9 ? cudaVideoCodec_VP9 : cudaVideoCodec_H264;
		}

		// How many entries of a frame that never came out are kept.
		constexpr size_t kMaxPendingFrames = 64;
	}

	NvdecVideoDecoder::NvdecVideoDecoder(NvdecLibrary & library, webrtc::VideoCodecType codec) :
		library(library),
		codec(codec),
		cudaCodec(CudaCodecOf(codec)),
		implementationName("NVDEC (" + library.DeviceName() + ")"),
		callback(nullptr),
		context(nullptr),
		parser(nullptr),
		decoder(nullptr),
		started(false),
		failed(false),
		surfaceHeight(0),
		left(0),
		top(0),
		width(0),
		height(0),
		counter(0)
	{
	}

	NvdecVideoDecoder::~NvdecVideoDecoder()
	{
		Release();
	}

	bool NvdecVideoDecoder::Configure(const Settings & settings)
	{
		if (settings.codec_type() != codec) {
			return false;
		}

		Release();

		if (!library.RetainContext(&context)) {
			RTC_LOG(LS_WARNING) << implementationName << " has no CUDA context";
			context = nullptr;

			return false;
		}

		NvdecContextScope scope(library, context);

		if (!scope.IsCurrent()) {
			Release();

			return false;
		}

		CUVIDPARSERPARAMS params = {};
		params.CodecType = cudaCodec;
		// The sequence callback says how many surfaces the decoder needs.
		params.ulMaxNumDecodeSurfaces = 1;
		// Each picture is shown as soon as it is decoded.
		params.ulMaxDisplayDelay = 0;
		params.pUserData = this;
		params.pfnSequenceCallback = &NvdecVideoDecoder::OnSequence;
		params.pfnDecodePicture = &NvdecVideoDecoder::OnDecode;
		params.pfnDisplayPicture = &NvdecVideoDecoder::OnDisplay;

		if (library.Api().cuvidCreateVideoParser(&parser, &params) != CUDA_SUCCESS) {
			RTC_LOG(LS_WARNING) << implementationName << " failed to create a parser";
			parser = nullptr;

			Release();

			return false;
		}

		RTC_LOG(LS_INFO) << implementationName << " configured";

		return true;
	}

	int32_t NvdecVideoDecoder::Decode(const webrtc::EncodedImage & image, int64_t renderTimeMs)
	{
		if (parser == nullptr || callback == nullptr) {
			return WEBRTC_VIDEO_CODEC_UNINITIALIZED;
		}
		if (image.size() == 0) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}
		if (failed) {
			return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
		}

		// The layers of a VP9 frame with spatial layers reach a decoder back to
		// back, without the superframe index that tells where one ends. libvpx
		// takes them that way; NVDEC is not known to.
		if (codec == webrtc::kVideoCodecVP9
			&& (image.SpatialIndex().value_or(0) > 0 || image.SpatialLayerFrameSize(1).has_value()))
		{
			return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
		}

		// The parser needs the parameters of the stream, which a key frame
		// carries. Until one has come, WebRTC is asked for it.
		if (!started && image.FrameType() != webrtc::VideoFrameType::kVideoFrameKey) {
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		NvdecContextScope scope(library, context);

		if (!scope.IsCurrent()) {
			return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
		}

		// The timestamp identifies the frame when it is shown.
		const int64_t timestamp = ++counter;

		pendingFrames[timestamp] = PendingFrame {
			image.RtpTimestamp(),
			image.ntp_time_ms_,
			renderTimeMs,
			image.rotation_
		};

		if (pendingFrames.size() > kMaxPendingFrames) {
			pendingFrames.erase(pendingFrames.begin());
		}

		CUVIDSOURCEDATAPACKET packet = {};
		packet.flags = CUVID_PKT_TIMESTAMP | CUVID_PKT_ENDOFPICTURE;
		packet.payload = image.data();
		packet.payload_size = image.size();
		packet.timestamp = timestamp;

		const CUresult result = library.Api().cuvidParseVideoData(parser, &packet);

		if (result != CUDA_SUCCESS || failed) {
			RTC_LOG(LS_WARNING) << implementationName << " failed to decode a frame, result=" << result;

			failed = true;

			return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
		}

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t NvdecVideoDecoder::RegisterDecodeCompleteCallback(webrtc::DecodedImageCallback * decodeCallback)
	{
		callback = decodeCallback;

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t NvdecVideoDecoder::Release()
	{
		if (context != nullptr) {
			{
				NvdecContextScope scope(library, context);

				if (parser != nullptr) {
					library.Api().cuvidDestroyVideoParser(parser);
					parser = nullptr;
				}

				DestroyDecoder();
			}

			library.ReleaseContext();
			context = nullptr;
		}

		started = false;
		failed = false;
		pendingFrames.clear();

		return WEBRTC_VIDEO_CODEC_OK;
	}

	webrtc::VideoDecoder::DecoderInfo NvdecVideoDecoder::GetDecoderInfo() const
	{
		DecoderInfo info;
		info.implementation_name = implementationName;
		info.is_hardware_accelerated = true;

		return info;
	}

	const char * NvdecVideoDecoder::ImplementationName() const
	{
		return implementationName.c_str();
	}

	int CUDAAPI NvdecVideoDecoder::OnSequence(void * decoder, CUVIDEOFORMAT * format)
	{
		return static_cast<NvdecVideoDecoder *>(decoder)->HandleSequence(format);
	}

	int CUDAAPI NvdecVideoDecoder::OnDecode(void * decoder, CUVIDPICPARAMS * picture)
	{
		return static_cast<NvdecVideoDecoder *>(decoder)->HandleDecode(picture);
	}

	int CUDAAPI NvdecVideoDecoder::OnDisplay(void * decoder, CUVIDPARSERDISPINFO * info)
	{
		return static_cast<NvdecVideoDecoder *>(decoder)->HandleDisplay(info);
	}

	int NvdecVideoDecoder::HandleSequence(CUVIDEOFORMAT * format)
	{
		const int pictureLeft = format->display_area.left;
		const int pictureTop = format->display_area.top;
		const int pictureWidth = format->display_area.right - format->display_area.left;
		const int pictureHeight = format->display_area.bottom - format->display_area.top;

		// Check, rather than trust what was negotiated: a stream that is
		// something else is not for this decoder.
		if (format->codec != cudaCodec || format->chroma_format != cudaVideoChromaFormat_420
			|| format->bit_depth_luma_minus8 != 0 || format->bit_depth_chroma_minus8 != 0)
		{
			RTC_LOG(LS_INFO) << implementationName << " does not decode this stream, 4:2:0 8 bit only";
			failed = true;

			return 0;
		}
		if (pictureWidth <= 0 || pictureHeight <= 0 || (pictureLeft % 2) != 0 || (pictureTop % 2) != 0) {
			RTC_LOG(LS_INFO) << implementationName << " does not take a picture that does not start on an even row";
			failed = true;

			return 0;
		}
		if (!library.Supports(cudaCodec, format->coded_width, format->coded_height)) {
			RTC_LOG(LS_INFO) << implementationName << " does not decode " << format->coded_width << "x"
				<< format->coded_height;
			failed = true;

			return 0;
		}

		// The stream changed its parameters, as at a key frame of another
		// size: the decoder is made again.
		DestroyDecoder();

		// The surface is aligned to 2, which the decoder asks for.
		const unsigned int alignedWidth = (format->coded_width + 1) & ~1u;
		const unsigned int alignedHeight = (format->coded_height + 1) & ~1u;
		const unsigned int surfaces = std::max<unsigned int>(format->min_num_decode_surfaces, 2) + 1;

		CUVIDDECODECREATEINFO info = {};
		info.ulWidth = format->coded_width;
		info.ulHeight = format->coded_height;
		info.ulNumDecodeSurfaces = surfaces;
		info.CodecType = cudaCodec;
		info.ChromaFormat = cudaVideoChromaFormat_420;
		info.ulCreationFlags = cudaVideoCreate_PreferCUVID;
		info.bitDepthMinus8 = 0;
		info.ulMaxWidth = format->coded_width;
		info.ulMaxHeight = format->coded_height;
		info.display_area.left = 0;
		info.display_area.top = 0;
		info.display_area.right = static_cast<short>(alignedWidth);
		info.display_area.bottom = static_cast<short>(alignedHeight);
		info.OutputFormat = cudaVideoSurfaceFormat_NV12;
		info.DeinterlaceMode = cudaVideoDeinterlaceMode_Weave;
		info.ulTargetWidth = alignedWidth;
		info.ulTargetHeight = alignedHeight;
		info.ulNumOutputSurfaces = 2;

		if (library.Api().cuvidCreateDecoder(&decoder, &info) != CUDA_SUCCESS) {
			RTC_LOG(LS_WARNING) << implementationName << " failed to create a decoder for " << format->coded_width
				<< "x" << format->coded_height;
			decoder = nullptr;
			failed = true;

			return 0;
		}

		surfaceHeight = alignedHeight;
		left = pictureLeft;
		top = pictureTop;
		width = pictureWidth;
		height = pictureHeight;
		started = true;

		return static_cast<int>(surfaces);
	}

	int NvdecVideoDecoder::HandleDecode(CUVIDPICPARAMS * picture)
	{
		if (decoder == nullptr) {
			failed = true;

			return 0;
		}
		if (library.Api().cuvidDecodePicture(decoder, picture) != CUDA_SUCCESS) {
			RTC_LOG(LS_WARNING) << implementationName << " failed to decode a picture";
			failed = true;

			return 0;
		}

		return 1;
	}

	int NvdecVideoDecoder::HandleDisplay(CUVIDPARSERDISPINFO * info)
	{
		if (info == nullptr) {
			// The end of the stream.
			return 1;
		}
		if (decoder == nullptr) {
			failed = true;

			return 0;
		}

		CUVIDPROCPARAMS process = {};
		process.progressive_frame = info->progressive_frame;
		process.second_field = info->repeat_first_field + 1;
		process.top_field_first = info->top_field_first;
		process.unpaired_field = info->repeat_first_field < 0;

		unsigned long long devicePointer = 0;
		unsigned int pitch = 0;

		if (library.Api().cuvidMapVideoFrame(decoder, info->picture_index, &devicePointer, &pitch, &process)
			!= CUDA_SUCCESS)
		{
			RTC_LOG(LS_WARNING) << implementationName << " failed to map a picture";
			failed = true;

			return 0;
		}

		const bool downloaded = Download(devicePointer, pitch);

		library.Api().cuvidUnmapVideoFrame(decoder, devicePointer);

		if (!downloaded) {
			RTC_LOG(LS_WARNING) << implementationName << " failed to copy a picture";
			failed = true;

			return 0;
		}

		auto found = pendingFrames.find(info->timestamp);

		if (found == pendingFrames.end()) {
			RTC_LOG(LS_WARNING) << implementationName << " showed a picture for no input, time " << info->timestamp;

			return 1;
		}

		const PendingFrame pending = found->second;

		// Frames that were not shown come out never.
		pendingFrames.erase(pendingFrames.begin(), std::next(found));

		webrtc::scoped_refptr<webrtc::I420Buffer> i420 = webrtc::I420Buffer::Create(width, height);

		const int yStride = width;
		const int uvStride = (width + 1) / 2 * 2;

		if (libyuv::NV12ToI420(nv12.data(), yStride, nv12.data() + static_cast<size_t>(yStride) * height, uvStride,
			i420->MutableDataY(), i420->StrideY(), i420->MutableDataU(), i420->StrideU(), i420->MutableDataV(),
			i420->StrideV(), width, height) != 0)
		{
			failed = true;

			return 0;
		}

		webrtc::VideoFrame frame = webrtc::VideoFrame::Builder()
			.set_video_frame_buffer(i420)
			.set_rtp_timestamp(pending.rtpTimestamp)
			.set_timestamp_ms(pending.renderTimeMs)
			.set_ntp_time_ms(pending.ntpTimeMs)
			.set_rotation(pending.rotation)
			.build();

		callback->Decoded(frame, std::nullopt, std::nullopt);

		return 1;
	}

	bool NvdecVideoDecoder::Download(unsigned long long devicePointer, unsigned int pitch)
	{
		const size_t yStride = static_cast<size_t>(width);
		const size_t uvStride = static_cast<size_t>((width + 1) / 2 * 2);
		const size_t uvHeight = static_cast<size_t>((height + 1) / 2);

		nv12.resize(yStride * height + uvStride * uvHeight);

		CUDA_MEMCPY2D copy = {};
		copy.srcMemoryType = CU_MEMORYTYPE_DEVICE;
		copy.dstMemoryType = CU_MEMORYTYPE_HOST;

		// The luma, from the picture within the surface.
		copy.srcDevice = devicePointer;
		copy.srcPitch = pitch;
		copy.srcXInBytes = static_cast<size_t>(left);
		copy.srcY = static_cast<size_t>(top);
		copy.dstHost = nv12.data();
		copy.dstPitch = yStride;
		copy.WidthInBytes = yStride;
		copy.Height = static_cast<size_t>(height);

		if (library.Api().cuMemcpy2D(&copy) != CUDA_SUCCESS) {
			return false;
		}

		// The chroma follows the whole surface: interleaved U and V, half the
		// rows. Its columns are in bytes, so they start where the luma does.
		copy.srcDevice = devicePointer + static_cast<unsigned long long>(pitch) * surfaceHeight;
		copy.srcXInBytes = static_cast<size_t>(left);
		copy.srcY = static_cast<size_t>(top / 2);
		copy.dstHost = nv12.data() + yStride * height;
		copy.dstPitch = uvStride;
		copy.WidthInBytes = uvStride;
		copy.Height = uvHeight;

		return library.Api().cuMemcpy2D(&copy) == CUDA_SUCCESS;
	}

	void NvdecVideoDecoder::DestroyDecoder()
	{
		if (decoder != nullptr) {
			library.Api().cuvidDestroyDecoder(decoder);
			decoder = nullptr;
		}
	}
}
