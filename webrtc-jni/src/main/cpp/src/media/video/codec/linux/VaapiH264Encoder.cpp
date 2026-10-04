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

#include "media/video/codec/linux/VaapiH264Encoder.h"

#include "api/video/encoded_image.h"
#include "api/video/i420_buffer.h"
#include "modules/video_coding/include/video_codec_interface.h"
#include "modules/video_coding/include/video_error_codes.h"
#include "rtc_base/logging.h"
#include "third_party/libyuv/include/libyuv/convert_from.h"

#include <algorithm>
#include <cstring>
#include <span>

namespace jni
{
	namespace
	{
		// frame_num counts to 2^8 before it wraps.
		constexpr uint32_t kLog2MaxFrameNum = 8;
		constexpr uint32_t kMaxFrameNum = 1 << kLog2MaxFrameNum;

		// Key frames come when WebRTC asks for them; this only bounds how
		// long a stream may go without one if it never does.
		constexpr uint32_t kIntraPeriod = 3000;

		// H.264 slice types.
		constexpr uint8_t kSliceTypeP = 0;
		constexpr uint8_t kSliceTypeI = 2;

		constexpr uint32_t kInitialQp = 26;

		// How many seconds of video the rate control may buffer, as a
		// multiple of the bitrate.
		constexpr uint32_t kHrdBufferNumerator = 3;
		constexpr uint32_t kHrdBufferDenominator = 2;

		// The lowest H.264 level whose limits a stream fits in, going by its
		// frame size and macroblock rate, per table A-1 of the standard.
		uint8_t LevelIdc(uint32_t frameSizeMbs, uint32_t framerate)
		{
			struct Level
			{
				uint8_t idc;
				uint32_t maxMbps;
				uint32_t maxFs;
			};

			static const Level levels[] = {
				{ 31, 108000, 3600 },
				{ 32, 216000, 5120 },
				{ 40, 245760, 8192 },
				{ 42, 522240, 8704 },
				{ 50, 589824, 22080 },
				{ 51, 983040, 36864 },
				{ 52, 2073600, 36864 }
			};

			const uint32_t mbps = frameSizeMbs * framerate;

			for (const Level & level : levels) {
				if (frameSizeMbs <= level.maxFs && mbps <= level.maxMbps) {
					return level.idc;
				}
			}

			return 52;
		}

		void InvalidatePicture(VAPictureH264 & picture)
		{
			picture = {};
			picture.picture_id = VA_INVALID_SURFACE;
			picture.flags = VA_PICTURE_H264_INVALID;
		}
	}

	VaapiH264Encoder::VaapiH264Encoder(VaapiLibrary & library, const webrtc::SdpVideoFormat & format) :
		library(library),
		va(library.Api()),
		implementationName("VA-API (" + library.Vendor() + ")"),
		config(VA_INVALID_ID),
		context(VA_INVALID_ID),
		inputSurface(VA_INVALID_SURFACE),
		reconstructed{ VA_INVALID_SURFACE, VA_INVALID_SURFACE },
		codedBuffer(VA_INVALID_ID),
		codecSettings(),
		widthInMbs(0),
		heightInMbs(0),
		bitrateBps(0),
		framerate(30),
		ratesChanged(false),
		frameNum(0),
		idrPicId(0),
		current(0),
		referenceValid(false),
		callback(nullptr),
		outputProcessor(webrtc::kVideoCodecH264, format)
	{
	}

	VaapiH264Encoder::~VaapiH264Encoder()
	{
		Release();
	}

	bool VaapiH264Encoder::Check(VAStatus status, const char * operation) const
	{
		if (status == VA_STATUS_SUCCESS) {
			return true;
		}

		RTC_LOG(LS_WARNING) << "VA-API: " << operation << " failed: " << library.ErrorString(status);

		return false;
	}

	int32_t VaapiH264Encoder::InitEncode(const webrtc::VideoCodec * settings, const Settings & encoderSettings)
	{
		if (settings == nullptr || settings->width == 0 || settings->height == 0) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}
		if (settings->numberOfSimulcastStreams > 1) {
			return WEBRTC_VIDEO_CODEC_ERR_SIMULCAST_PARAMETERS_NOT_SUPPORTED;
		}
		// NV12 has chroma at half the resolution in both directions, and the
		// frame is cropped in units of two pixels. The first frame of a source
		// can be odd, since WebRTC asks the source for
		// requested_resolution_alignment only once the encoder has been set up.
		// Refusing it would leave the whole session to the software encoder, so
		// the encoder takes the even size below it and drops the last row and
		// column of such frames.
		if (settings->width < 2 || settings->height < 2) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}

		Release();

		codecSettings = *settings;
		codecSettings.width &= ~1;
		codecSettings.height &= ~1;
		widthInMbs = (codecSettings.width + 15) / 16;
		heightInMbs = (codecSettings.height + 15) / 16;
		bitrateBps = std::max(1u, codecSettings.startBitrate) * 1000;
		framerate = std::max(1u, codecSettings.maxFramerate);
		ratesChanged = true;
		frameNum = 0;
		current = 0;
		referenceValid = false;
		outputProcessor.Reset();

		if (!CreateSession()) {
			Release();
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		RTC_LOG(LS_INFO) << implementationName << " initialized: " << codecSettings.width << "x"
			<< codecSettings.height << " at " << bitrateBps << " bps";

		return WEBRTC_VIDEO_CODEC_OK;
	}

	bool VaapiH264Encoder::CreateSession()
	{
		VADisplay display = library.Display();

		VAConfigAttrib attributes[2] = {};
		attributes[0].type = VAConfigAttribRTFormat;
		attributes[0].value = VA_RT_FORMAT_YUV420;
		attributes[1].type = VAConfigAttribRateControl;
		attributes[1].value = VA_RC_CBR;

		if (!Check(va.CreateConfig(display, VAProfileH264ConstrainedBaseline, library.Entrypoint(), attributes, 2,
			&config), "creating the configuration"))
		{
			config = VA_INVALID_ID;
			return false;
		}

		// Whole macroblocks; the stream is cropped to the frame size.
		const unsigned int width = widthInMbs * 16;
		const unsigned int height = heightInMbs * 16;

		VASurfaceID surfaces[3] = { VA_INVALID_SURFACE, VA_INVALID_SURFACE, VA_INVALID_SURFACE };

		if (!Check(va.CreateSurfaces(display, VA_RT_FORMAT_YUV420, width, height, surfaces, 3, nullptr, 0),
			"creating surfaces"))
		{
			return false;
		}

		inputSurface = surfaces[0];
		reconstructed[0] = surfaces[1];
		reconstructed[1] = surfaces[2];

		if (!Check(va.CreateContext(display, config, width, height, VA_PROGRESSIVE, surfaces, 3, &context),
			"creating the context"))
		{
			context = VA_INVALID_ID;
			return false;
		}

		// Room for a frame that does not compress at all.
		const unsigned int codedSize = width * height * 3 / 2 + 64 * 1024;

		if (!Check(va.CreateBuffer(display, context, VAEncCodedBufferType, codedSize, 1, nullptr, &codedBuffer),
			"creating the output buffer"))
		{
			codedBuffer = VA_INVALID_ID;
			return false;
		}

		return true;
	}

	int32_t VaapiH264Encoder::RegisterEncodeCompleteCallback(webrtc::EncodedImageCallback * encodeCallback)
	{
		callback = encodeCallback;

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t VaapiH264Encoder::Release()
	{
		DestroySession();

		return WEBRTC_VIDEO_CODEC_OK;
	}

	void VaapiH264Encoder::DestroySession()
	{
		VADisplay display = library.Display();

		DestroyFrameBuffers();

		if (codedBuffer != VA_INVALID_ID) {
			va.DestroyBuffer(display, codedBuffer);
			codedBuffer = VA_INVALID_ID;
		}
		if (context != VA_INVALID_ID) {
			va.DestroyContext(display, context);
			context = VA_INVALID_ID;
		}

		VASurfaceID surfaces[3] = { inputSurface, reconstructed[0], reconstructed[1] };

		if (inputSurface != VA_INVALID_SURFACE) {
			va.DestroySurfaces(display, surfaces, 3);
		}

		inputSurface = VA_INVALID_SURFACE;
		reconstructed[0] = VA_INVALID_SURFACE;
		reconstructed[1] = VA_INVALID_SURFACE;

		if (config != VA_INVALID_ID) {
			va.DestroyConfig(display, config);
			config = VA_INVALID_ID;
		}

		referenceValid = false;
	}

	int32_t VaapiH264Encoder::Encode(const webrtc::VideoFrame & frame,
		const std::vector<webrtc::VideoFrameType> * frameTypes)
	{
		if (context == VA_INVALID_ID || callback == nullptr) {
			return WEBRTC_VIDEO_CODEC_UNINITIALIZED;
		}

		bool idr = !referenceValid;

		if (frameTypes != nullptr) {
			idr |= std::any_of(frameTypes->begin(), frameTypes->end(), [](webrtc::VideoFrameType type) {
				return type == webrtc::VideoFrameType::kVideoFrameKey;
			});
		}

		if (idr) {
			frameNum = 0;
		}

		std::vector<uint8_t> output;

		if (!Upload(frame) || !Submit(idr) || !ReadOutput(output)) {
			DestroyFrameBuffers();
			return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
		}

		DestroyFrameBuffers();

		webrtc::EncodedImage image;
		image._encodedWidth = codecSettings.width;
		image._encodedHeight = codecSettings.height;
		image.SetRtpTimestamp(frame.rtp_timestamp());
		image.capture_time_ms_ = frame.render_time_ms();
		image.ntp_time_ms_ = frame.ntp_time_ms();
		image.rotation_ = frame.rotation();

		webrtc::CodecSpecificInfo info;

		// A driver that writes no parameter sets into key frames makes a
		// stream nothing can decode; better in software then.
		if (!outputProcessor.Process(output, idr, image, info)) {
			return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
		}

		// The frame just encoded is the reference of the next one.
		referenceValid = true;
		current = 1 - current;
		frameNum = (frameNum + 1) % kMaxFrameNum;
		ratesChanged = false;

		if (idr) {
			idrPicId = (idrPicId + 1) & 0xFFFF;
		}

		callback->OnEncodedImage(image, &info);

		return WEBRTC_VIDEO_CODEC_OK;
	}

	bool VaapiH264Encoder::Upload(const webrtc::VideoFrame & frame)
	{
		webrtc::scoped_refptr<webrtc::I420BufferInterface> i420 = frame.video_frame_buffer()->ToI420();

		if (!i420) {
			return false;
		}

		const int width = static_cast<int>(codecSettings.width);
		const int height = static_cast<int>(codecSettings.height);

		// WebRTC initializes the encoder again when the frame size changes,
		// so a frame of another size is one that raced with that. An odd frame
		// is cropped to the even size the encoder took, which needs no copy.
		const bool cropped = i420->width() - width >= 0 && i420->width() - width <= 1
			&& i420->height() - height >= 0 && i420->height() - height <= 1;

		if (!cropped) {
			webrtc::scoped_refptr<webrtc::I420Buffer> scaled = webrtc::I420Buffer::Create(width, height);
			scaled->ScaleFrom(*i420);
			i420 = scaled;
		}

		VADisplay display = library.Display();
		VAImage image = {};
		image.image_id = VA_INVALID_ID;

		// Writing into the surface directly where the driver allows it,
		// otherwise into an image that is copied into the surface.
		bool derived = va.DeriveImage(display, inputSurface, &image) == VA_STATUS_SUCCESS
			&& image.format.fourcc == VA_FOURCC_NV12;

		if (!derived) {
			if (image.image_id != VA_INVALID_ID) {
				va.DestroyImage(display, image.image_id);
			}

			VAImageFormat format = {};
			format.fourcc = VA_FOURCC_NV12;
			format.byte_order = VA_LSB_FIRST;
			format.bits_per_pixel = 12;

			if (!Check(va.CreateImage(display, &format, width, height, &image), "creating an image")) {
				return false;
			}
		}

		void * data = nullptr;
		bool uploaded = Check(va.MapBuffer(display, image.buf, &data), "mapping an image");

		if (uploaded) {
			uint8_t * base = static_cast<uint8_t *>(data);

			libyuv::I420ToNV12(i420->DataY(), i420->StrideY(), i420->DataU(), i420->StrideU(),
				i420->DataV(), i420->StrideV(),
				base + image.offsets[0], static_cast<int>(image.pitches[0]),
				base + image.offsets[1], static_cast<int>(image.pitches[1]),
				width, height);

			va.UnmapBuffer(display, image.buf);

			if (!derived) {
				uploaded = Check(va.PutImage(display, inputSurface, image.image_id, 0, 0, width, height,
					0, 0, width, height), "copying an image");
			}
		}

		va.DestroyImage(display, image.image_id);

		return uploaded;
	}

	bool VaapiH264Encoder::Submit(bool idr)
	{
		if (idr) {
			VAEncSequenceParameterBufferH264 sequence = {};
			FillSequence(sequence);

			if (!AddBuffer(VAEncSequenceParameterBufferType, &sequence, sizeof(sequence))) {
				return false;
			}
		}

		if (idr || ratesChanged) {
			VAEncMiscParameterRateControl rateControl = {};
			rateControl.bits_per_second = bitrateBps;
			rateControl.target_percentage = 100;
			rateControl.window_size = 1000;
			rateControl.initial_qp = kInitialQp;

			VAEncMiscParameterFrameRate frameRate = {};
			// The numerator in the low 16 bits, the denominator in the high.
			frameRate.framerate = framerate | (1u << 16);

			VAEncMiscParameterHRD hrd = {};
			hrd.buffer_size = static_cast<uint32_t>(
				static_cast<uint64_t>(bitrateBps) * kHrdBufferNumerator / kHrdBufferDenominator);
			hrd.initial_buffer_fullness = hrd.buffer_size / 2;

			if (!AddMiscParameter(VAEncMiscParameterTypeRateControl, &rateControl, sizeof(rateControl)) ||
				!AddMiscParameter(VAEncMiscParameterTypeFrameRate, &frameRate, sizeof(frameRate)) ||
				!AddMiscParameter(VAEncMiscParameterTypeHRD, &hrd, sizeof(hrd)))
			{
				return false;
			}
		}

		VAEncPictureParameterBufferH264 picture = {};
		FillPicture(picture, idr);

		VAEncSliceParameterBufferH264 slice = {};
		FillSlice(slice, idr);

		if (!AddBuffer(VAEncPictureParameterBufferType, &picture, sizeof(picture)) ||
			!AddBuffer(VAEncSliceParameterBufferType, &slice, sizeof(slice)))
		{
			return false;
		}

		VADisplay display = library.Display();

		if (!Check(va.BeginPicture(display, context, inputSurface), "beginning a picture")) {
			return false;
		}

		bool rendered = Check(va.RenderPicture(display, context, frameBuffers.data(),
			static_cast<int>(frameBuffers.size())), "rendering a picture");

		// A picture that was begun has to be ended.
		bool ended = Check(va.EndPicture(display, context), "ending a picture");

		return rendered && ended && Check(va.SyncSurface(display, inputSurface), "waiting for a picture");
	}

	bool VaapiH264Encoder::ReadOutput(std::vector<uint8_t> & output)
	{
		VADisplay display = library.Display();
		void * data = nullptr;

		if (!Check(va.MapBuffer(display, codedBuffer, &data), "mapping the output")) {
			return false;
		}

		bool complete = true;

		for (auto * segment = static_cast<VACodedBufferSegment *>(data); segment != nullptr;
			segment = static_cast<VACodedBufferSegment *>(segment->next))
		{
			if (segment->status & VA_CODED_BUF_STATUS_SLICE_OVERFLOW_MASK) {
				complete = false;
			}

			const uint8_t * bytes = static_cast<const uint8_t *>(segment->buf);
			output.insert(output.end(), bytes, bytes + segment->size);
		}

		va.UnmapBuffer(display, codedBuffer);

		if (!complete || output.empty()) {
			RTC_LOG(LS_WARNING) << "VA-API: a frame did not fit the output buffer";
			return false;
		}

		return true;
	}

	bool VaapiH264Encoder::AddBuffer(VABufferType type, void * data, size_t size)
	{
		VABufferID buffer = VA_INVALID_ID;

		if (!Check(va.CreateBuffer(library.Display(), context, type, static_cast<unsigned int>(size), 1, data,
			&buffer), "creating a parameter buffer"))
		{
			return false;
		}

		frameBuffers.push_back(buffer);

		return true;
	}

	bool VaapiH264Encoder::AddMiscParameter(VAEncMiscParameterType type, const void * data, size_t size)
	{
		// A VAEncMiscParameterBuffer: the type, followed by the parameter.
		std::vector<uint8_t> buffer(sizeof(VAEncMiscParameterBuffer) + size);

		auto * header = reinterpret_cast<VAEncMiscParameterBuffer *>(buffer.data());
		header->type = type;
		std::memcpy(header->data, data, size);

		return AddBuffer(VAEncMiscParameterBufferType, buffer.data(), buffer.size());
	}

	void VaapiH264Encoder::DestroyFrameBuffers()
	{
		for (VABufferID buffer : frameBuffers) {
			va.DestroyBuffer(library.Display(), buffer);
		}

		frameBuffers.clear();
	}

	void VaapiH264Encoder::FillSequence(VAEncSequenceParameterBufferH264 & sequence) const
	{
		sequence.seq_parameter_set_id = 0;
		sequence.level_idc = LevelIdc(widthInMbs * heightInMbs, framerate);
		sequence.intra_period = kIntraPeriod;
		sequence.intra_idr_period = kIntraPeriod;
		sequence.ip_period = 1;
		sequence.bits_per_second = bitrateBps;
		sequence.max_num_ref_frames = 1;
		sequence.picture_width_in_mbs = static_cast<uint16_t>(widthInMbs);
		sequence.picture_height_in_mbs = static_cast<uint16_t>(heightInMbs);

		sequence.seq_fields.bits.chroma_format_idc = 1;
		sequence.seq_fields.bits.frame_mbs_only_flag = 1;
		sequence.seq_fields.bits.direct_8x8_inference_flag = 1;
		sequence.seq_fields.bits.log2_max_frame_num_minus4 = kLog2MaxFrameNum - 4;
		// Display order follows frame_num, which there are no B-frames to
		// break.
		sequence.seq_fields.bits.pic_order_cnt_type = 2;

		const uint32_t croppedRight = widthInMbs * 16 - codecSettings.width;
		const uint32_t croppedBottom = heightInMbs * 16 - codecSettings.height;

		if (croppedRight > 0 || croppedBottom > 0) {
			// In units of two luma samples for 4:2:0 frames.
			sequence.frame_cropping_flag = 1;
			sequence.frame_crop_right_offset = croppedRight / 2;
			sequence.frame_crop_bottom_offset = croppedBottom / 2;
		}

		sequence.vui_parameters_present_flag = 1;
		sequence.vui_fields.bits.timing_info_present_flag = 1;
		sequence.num_units_in_tick = 1;
		sequence.time_scale = framerate * 2;
	}

	void VaapiH264Encoder::FillPicture(VAEncPictureParameterBufferH264 & picture, bool idr) const
	{
		picture.CurrPic.picture_id = reconstructed[current];
		picture.CurrPic.frame_idx = frameNum;
		picture.CurrPic.flags = 0;
		picture.CurrPic.TopFieldOrderCnt = static_cast<int32_t>(frameNum * 2);
		picture.CurrPic.BottomFieldOrderCnt = picture.CurrPic.TopFieldOrderCnt;

		for (VAPictureH264 & reference : picture.ReferenceFrames) {
			InvalidatePicture(reference);
		}

		if (!idr) {
			const uint32_t previous = (frameNum + kMaxFrameNum - 1) % kMaxFrameNum;

			VAPictureH264 & reference = picture.ReferenceFrames[0];
			reference.picture_id = reconstructed[1 - current];
			reference.frame_idx = previous;
			reference.flags = VA_PICTURE_H264_SHORT_TERM_REFERENCE;
			reference.TopFieldOrderCnt = static_cast<int32_t>(previous * 2);
			reference.BottomFieldOrderCnt = reference.TopFieldOrderCnt;
		}

		picture.coded_buf = codedBuffer;
		picture.pic_parameter_set_id = 0;
		picture.seq_parameter_set_id = 0;
		picture.last_picture = 0;
		picture.frame_num = static_cast<uint16_t>(frameNum);
		picture.pic_init_qp = kInitialQp;
		picture.num_ref_idx_l0_active_minus1 = 0;
		picture.num_ref_idx_l1_active_minus1 = 0;

		picture.pic_fields.bits.idr_pic_flag = idr ? 1 : 0;
		picture.pic_fields.bits.reference_pic_flag = 1;
		// CAVLC: Constrained Baseline has no CABAC.
		picture.pic_fields.bits.entropy_coding_mode_flag = 0;
		picture.pic_fields.bits.transform_8x8_mode_flag = 0;
		picture.pic_fields.bits.deblocking_filter_control_present_flag = 1;
	}

	void VaapiH264Encoder::FillSlice(VAEncSliceParameterBufferH264 & slice, bool idr) const
	{
		slice.macroblock_address = 0;
		slice.num_macroblocks = widthInMbs * heightInMbs;
		slice.macroblock_info = VA_INVALID_ID;
		slice.slice_type = idr ? kSliceTypeI : kSliceTypeP;
		slice.pic_parameter_set_id = 0;
		slice.idr_pic_id = static_cast<uint16_t>(idrPicId);
		slice.pic_order_cnt_lsb = 0;
		slice.num_ref_idx_active_override_flag = 0;
		slice.num_ref_idx_l0_active_minus1 = 0;

		for (VAPictureH264 & reference : slice.RefPicList0) {
			InvalidatePicture(reference);
		}
		for (VAPictureH264 & reference : slice.RefPicList1) {
			InvalidatePicture(reference);
		}

		if (!idr) {
			const uint32_t previous = (frameNum + kMaxFrameNum - 1) % kMaxFrameNum;

			VAPictureH264 & reference = slice.RefPicList0[0];
			reference.picture_id = reconstructed[1 - current];
			reference.frame_idx = previous;
			reference.flags = VA_PICTURE_H264_SHORT_TERM_REFERENCE;
			reference.TopFieldOrderCnt = static_cast<int32_t>(previous * 2);
			reference.BottomFieldOrderCnt = reference.TopFieldOrderCnt;
		}

		slice.slice_qp_delta = 0;
		slice.disable_deblocking_filter_idc = 0;
	}

	void VaapiH264Encoder::SetRates(const RateControlParameters & parameters)
	{
		const uint32_t bitrate = parameters.bitrate.get_sum_bps();

		// A zero bitrate pauses the stream; the encoder gets no frames then.
		if (bitrate == 0) {
			return;
		}

		bitrateBps = bitrate;

		if (parameters.framerate_fps >= 1.0) {
			framerate = static_cast<uint32_t>(parameters.framerate_fps + 0.5);
		}

		// Applied with the next frame.
		ratesChanged = true;
	}

	webrtc::VideoEncoder::EncoderInfo VaapiH264Encoder::GetEncoderInfo() const
	{
		EncoderInfo info;
		info.implementation_name = implementationName;
		info.is_hardware_accelerated = true;
		info.supports_native_handle = false;
		info.supports_simulcast = false;
		info.scaling_settings = outputProcessor.GetScalingSettings();
		// NV12 needs even dimensions.
		info.requested_resolution_alignment = 2;

		return info;
	}
}
