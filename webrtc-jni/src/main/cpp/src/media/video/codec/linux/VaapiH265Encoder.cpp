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

#include "media/video/codec/linux/VaapiH265Encoder.h"

#include "api/video/encoded_image.h"
#include "api/video/i420_buffer.h"
#include "modules/video_coding/include/video_codec_interface.h"
#include "modules/video_coding/include/video_error_codes.h"
#include "rtc_base/logging.h"
#include "third_party/libyuv/include/libyuv/convert_from.h"

#include <algorithm>
#include <cstring>

namespace jni
{
	namespace
	{
		// Key frames come when WebRTC asks for them; this only bounds how
		// long a stream may go without one if it never does.
		constexpr uint32_t kIntraPeriod = 3000;

		// H.265 slice types (7.4.7.1) and NAL unit types (table 7-1).
		constexpr uint8_t kSliceTypeP = 1;
		constexpr uint8_t kSliceTypeI = 2;
		constexpr uint8_t kNalTrailR = 1;
		constexpr uint8_t kNalIdrWRadl = 19;

		// VA-API's picture coding types.
		constexpr uint32_t kCodingTypeI = 1;
		constexpr uint32_t kCodingTypeP = 2;

		constexpr uint32_t kInitialQp = 26;

		// How many seconds of video the rate control may buffer, as a
		// multiple of the bitrate.
		constexpr uint32_t kHrdBufferNumerator = 3;
		constexpr uint32_t kHrdBufferDenominator = 2;

		// The lowest H.265 level whose limits a stream fits in, going by its
		// picture size and luma sample rate, per table A.8 of the standard;
		// general_level_idc is 30 times the level.
		uint8_t LevelIdc(uint32_t pictureSize, uint32_t framerate)
		{
			struct Level
			{
				uint8_t idc;
				uint64_t maxLumaPs;
				uint64_t maxLumaSr;
			};

			static const Level levels[] = {
				{ 93, 983040, 33177600 },
				{ 120, 2228224, 66846720 },
				{ 123, 2228224, 133693440 },
				{ 150, 8912896, 267386880 },
				{ 153, 8912896, 534773760 },
				{ 156, 8912896, 1069547520 }
			};

			const uint64_t sampleRate = static_cast<uint64_t>(pictureSize) * framerate;

			for (const Level & level : levels) {
				if (pictureSize <= level.maxLumaPs && sampleRate <= level.maxLumaSr) {
					return level.idc;
				}
			}

			return 156;
		}

		void InvalidatePicture(VAPictureHEVC & picture)
		{
			picture = {};
			picture.picture_id = VA_INVALID_SURFACE;
			picture.flags = VA_PICTURE_HEVC_INVALID;
		}

		// A coding tool is used where the driver allows it.
		bool Allowed(uint32_t feature)
		{
			return feature != VA_FEATURE_NOT_SUPPORTED;
		}
	}

	VaapiH265Encoder::VaapiH265Encoder(VaapiLibrary & library, VAEntrypoint entrypoint,
		const webrtc::SdpVideoFormat & format) :
		library(library),
		va(library.Api()),
		entrypoint(entrypoint),
		implementationName("VA-API (" + library.Vendor() + ")"),
		// FFmpeg's choice for a driver that does not say: 32x32 coding tree
		// blocks, coding blocks of at least 16x16, transform blocks of 4x4 to
		// 32x32 and three levels of transform hierarchy.
		log2CtbSize(5),
		log2MinCbSize(4),
		log2MinTbSize(2),
		log2MaxTbSize(5),
		maxTransformDepthInter(3),
		maxTransformDepthIntra(3),
		amp(true),
		sao(true),
		signDataHiding(false),
		transformSkip(false),
		cuQpDelta(true),
		config(VA_INVALID_ID),
		context(VA_INVALID_ID),
		inputSurface(VA_INVALID_SURFACE),
		reconstructed{ VA_INVALID_SURFACE, VA_INVALID_SURFACE },
		codedBuffer(VA_INVALID_ID),
		codecSettings(),
		widthInCtbs(0),
		heightInCtbs(0),
		bitrateBps(0),
		framerate(30),
		ratesChanged(false),
		pictureOrder(0),
		current(0),
		referenceValid(false),
		callback(nullptr),
		outputProcessor(webrtc::kVideoCodecH265, format)
	{
		QueryCapabilities();
	}

	VaapiH265Encoder::~VaapiH265Encoder()
	{
		Release();
	}

	bool VaapiH265Encoder::Check(VAStatus status, const char * operation) const
	{
		if (status == VA_STATUS_SUCCESS) {
			return true;
		}

		RTC_LOG(LS_WARNING) << "VA-API H.265: " << operation << " failed: " << library.ErrorString(status);

		return false;
	}

	void VaapiH265Encoder::QueryCapabilities()
	{
		VAConfigAttrib attributes[2] = {};
		attributes[0].type = VAConfigAttribEncHEVCBlockSizes;
		attributes[1].type = VAConfigAttribEncHEVCFeatures;

		if (va.GetConfigAttributes(library.Display(), VAProfileHEVCMain, entrypoint, attributes, 2) !=
			VA_STATUS_SUCCESS)
		{
			return;
		}

		if (attributes[0].value != VA_ATTRIB_NOT_SUPPORTED) {
			VAConfigAttribValEncHEVCBlockSizes sizes;
			sizes.value = attributes[0].value;

			log2CtbSize = sizes.bits.log2_max_coding_tree_block_size_minus3 + 3;
			log2MinCbSize = sizes.bits.log2_min_luma_coding_block_size_minus3 + 3;
			log2MinTbSize = sizes.bits.log2_min_luma_transform_block_size_minus2 + 2;
			log2MaxTbSize = sizes.bits.log2_max_luma_transform_block_size_minus2 + 2;
			maxTransformDepthInter = sizes.bits.max_max_transform_hierarchy_depth_inter;
			maxTransformDepthIntra = sizes.bits.max_max_transform_hierarchy_depth_intra;
		}

		if (attributes[1].value != VA_ATTRIB_NOT_SUPPORTED) {
			VAConfigAttribValEncHEVCFeatures features;
			features.value = attributes[1].value;

			amp = Allowed(features.bits.amp);
			sao = Allowed(features.bits.sao);
			signDataHiding = Allowed(features.bits.sign_data_hiding);
			transformSkip = Allowed(features.bits.transform_skip);
			cuQpDelta = Allowed(features.bits.cu_qp_delta);
		}
	}

	int32_t VaapiH265Encoder::InitEncode(const webrtc::VideoCodec * settings, const Settings & encoderSettings)
	{
		if (settings == nullptr || settings->width == 0 || settings->height == 0) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}
		if (settings->numberOfSimulcastStreams > 1) {
			return WEBRTC_VIDEO_CODEC_ERR_SIMULCAST_PARAMETERS_NOT_SUPPORTED;
		}

		// The coded picture is kept to whole coding blocks, so that it is the
		// frame and needs no cropping. WebRTC is asked for frames of that
		// alignment, but the first frames of a source can come before it
		// knows, so the encoder takes the aligned size below and drops the
		// rows and columns beyond it.
		const uint32_t alignment = 1u << log2MinCbSize;

		if (settings->width < alignment || settings->height < alignment) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}

		Release();

		const uint32_t ctbSize = 1u << log2CtbSize;

		codecSettings = *settings;
		codecSettings.width -= codecSettings.width % alignment;
		codecSettings.height -= codecSettings.height % alignment;
		widthInCtbs = (codecSettings.width + ctbSize - 1) / ctbSize;
		heightInCtbs = (codecSettings.height + ctbSize - 1) / ctbSize;
		bitrateBps = std::max(1u, codecSettings.startBitrate) * 1000;
		framerate = std::max(1u, codecSettings.maxFramerate);
		ratesChanged = true;
		pictureOrder = 0;
		current = 0;
		referenceValid = false;
		outputProcessor.Reset();

		if (!CreateSession()) {
			Release();
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		RTC_LOG(LS_INFO) << implementationName << " H.265 initialized: " << codecSettings.width << "x"
			<< codecSettings.height << " at " << bitrateBps << " bps, CTB " << ctbSize << ", min CB " << alignment;

		return WEBRTC_VIDEO_CODEC_OK;
	}

	bool VaapiH265Encoder::CreateSession()
	{
		VADisplay display = library.Display();

		VAConfigAttrib attributes[2] = {};
		attributes[0].type = VAConfigAttribRTFormat;
		attributes[0].value = VA_RT_FORMAT_YUV420;
		attributes[1].type = VAConfigAttribRateControl;
		attributes[1].value = VA_RC_CBR;

		if (!Check(va.CreateConfig(display, VAProfileHEVCMain, entrypoint, attributes, 2, &config),
			"creating the configuration"))
		{
			config = VA_INVALID_ID;
			return false;
		}

		// Whole coding tree blocks, which the driver may write all of.
		const unsigned int width = widthInCtbs << log2CtbSize;
		const unsigned int height = heightInCtbs << log2CtbSize;

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

	int32_t VaapiH265Encoder::RegisterEncodeCompleteCallback(webrtc::EncodedImageCallback * encodeCallback)
	{
		callback = encodeCallback;

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t VaapiH265Encoder::Release()
	{
		DestroySession();

		return WEBRTC_VIDEO_CODEC_OK;
	}

	void VaapiH265Encoder::DestroySession()
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

	int32_t VaapiH265Encoder::Encode(const webrtc::VideoFrame & frame,
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
			pictureOrder = 0;
		}

		std::vector<uint8_t> output;

		if (!Upload(frame) || !Submit(idr) || !ReadOutput(output)) {
			DestroyFrameBuffers();
			return WEBRTC_VIDEO_CODEC_ERROR;
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
		// stream nothing can decode.
		if (!outputProcessor.Process(output, idr, image, info)) {
			RTC_LOG(LS_WARNING) << implementationName << " H.265: the driver wrote no parameter sets";
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		// The frame just encoded is the reference of the next one.
		referenceValid = true;
		current = 1 - current;
		pictureOrder++;
		ratesChanged = false;

		callback->OnEncodedImage(image, &info);

		return WEBRTC_VIDEO_CODEC_OK;
	}

	bool VaapiH265Encoder::Upload(const webrtc::VideoFrame & frame)
	{
		webrtc::scoped_refptr<webrtc::I420BufferInterface> i420 = frame.video_frame_buffer()->ToI420();

		if (!i420) {
			return false;
		}

		const int width = static_cast<int>(codecSettings.width);
		const int height = static_cast<int>(codecSettings.height);

		// A frame up to a coding block larger is cropped to the size the
		// encoder took, which needs no copy; one of another size raced with
		// a change of size, and is scaled.
		const int alignment = 1 << log2MinCbSize;
		const bool cropped = i420->width() >= width && i420->width() - width < alignment
			&& i420->height() >= height && i420->height() - height < alignment;

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

	bool VaapiH265Encoder::Submit(bool idr)
	{
		if (idr) {
			VAEncSequenceParameterBufferHEVC sequence = {};
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

		VAEncPictureParameterBufferHEVC picture = {};
		FillPicture(picture, idr);

		VAEncSliceParameterBufferHEVC slice = {};
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

	bool VaapiH265Encoder::ReadOutput(std::vector<uint8_t> & output)
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
			RTC_LOG(LS_WARNING) << "VA-API H.265: a frame did not fit the output buffer";
			return false;
		}

		return true;
	}

	bool VaapiH265Encoder::AddBuffer(VABufferType type, void * data, size_t size)
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

	bool VaapiH265Encoder::AddMiscParameter(VAEncMiscParameterType type, const void * data, size_t size)
	{
		// A VAEncMiscParameterBuffer: the type, followed by the parameter.
		std::vector<uint8_t> buffer(sizeof(VAEncMiscParameterBuffer) + size);

		auto * header = reinterpret_cast<VAEncMiscParameterBuffer *>(buffer.data());
		header->type = type;
		std::memcpy(header->data, data, size);

		return AddBuffer(VAEncMiscParameterBufferType, buffer.data(), buffer.size());
	}

	void VaapiH265Encoder::DestroyFrameBuffers()
	{
		for (VABufferID buffer : frameBuffers) {
			va.DestroyBuffer(library.Display(), buffer);
		}

		frameBuffers.clear();
	}

	void VaapiH265Encoder::FillSequence(VAEncSequenceParameterBufferHEVC & sequence) const
	{
		// Main profile, main tier.
		sequence.general_profile_idc = 1;
		sequence.general_level_idc = LevelIdc(codecSettings.width * codecSettings.height, framerate);
		sequence.general_tier_flag = 0;
		sequence.intra_period = kIntraPeriod;
		sequence.intra_idr_period = kIntraPeriod;
		sequence.ip_period = 1;
		sequence.bits_per_second = bitrateBps;
		sequence.pic_width_in_luma_samples = static_cast<uint16_t>(codecSettings.width);
		sequence.pic_height_in_luma_samples = static_cast<uint16_t>(codecSettings.height);

		sequence.seq_fields.bits.chroma_format_idc = 1;
		sequence.seq_fields.bits.amp_enabled_flag = amp ? 1 : 0;
		sequence.seq_fields.bits.sample_adaptive_offset_enabled_flag = sao ? 1 : 0;
		// No temporal motion vector prediction, which would need a collocated
		// picture to be named.
		sequence.seq_fields.bits.sps_temporal_mvp_enabled_flag = 0;
		sequence.seq_fields.bits.low_delay_seq = 1;

		sequence.log2_min_luma_coding_block_size_minus3 = static_cast<uint8_t>(log2MinCbSize - 3);
		sequence.log2_diff_max_min_luma_coding_block_size = static_cast<uint8_t>(log2CtbSize - log2MinCbSize);
		sequence.log2_min_transform_block_size_minus2 = static_cast<uint8_t>(log2MinTbSize - 2);
		sequence.log2_diff_max_min_transform_block_size = static_cast<uint8_t>(log2MaxTbSize - log2MinTbSize);
		sequence.max_transform_hierarchy_depth_inter = static_cast<uint8_t>(maxTransformDepthInter);
		sequence.max_transform_hierarchy_depth_intra = static_cast<uint8_t>(maxTransformDepthIntra);

		sequence.vui_parameters_present_flag = 1;
		sequence.vui_fields.bits.vui_timing_info_present_flag = 1;
		sequence.vui_num_units_in_tick = 1;
		sequence.vui_time_scale = framerate;
	}

	VAPictureHEVC VaapiH265Encoder::Reference() const
	{
		VAPictureHEVC reference = {};
		reference.picture_id = reconstructed[1 - current];
		reference.pic_order_cnt = pictureOrder - 1;
		// A short-term reference before the current picture.
		reference.flags = VA_PICTURE_HEVC_RPS_ST_CURR_BEFORE;

		return reference;
	}

	void VaapiH265Encoder::FillPicture(VAEncPictureParameterBufferHEVC & picture, bool idr) const
	{
		picture.decoded_curr_pic.picture_id = reconstructed[current];
		picture.decoded_curr_pic.pic_order_cnt = pictureOrder;
		picture.decoded_curr_pic.flags = 0;

		for (VAPictureHEVC & reference : picture.reference_frames) {
			InvalidatePicture(reference);
		}

		if (!idr) {
			picture.reference_frames[0] = Reference();
		}

		picture.coded_buf = codedBuffer;
		// No collocated picture: there is no temporal motion vector
		// prediction.
		picture.collocated_ref_pic_index = 0xFF;
		picture.last_picture = 0;
		picture.pic_init_qp = kInitialQp;
		picture.diff_cu_qp_delta_depth = 0;
		picture.num_ref_idx_l0_default_active_minus1 = 0;
		picture.num_ref_idx_l1_default_active_minus1 = 0;
		picture.slice_pic_parameter_set_id = 0;
		picture.nal_unit_type = idr ? kNalIdrWRadl : kNalTrailR;

		picture.pic_fields.bits.idr_pic_flag = idr ? 1 : 0;
		picture.pic_fields.bits.coding_type = idr ? kCodingTypeI : kCodingTypeP;
		picture.pic_fields.bits.reference_pic_flag = 1;
		picture.pic_fields.bits.sign_data_hiding_enabled_flag = signDataHiding ? 1 : 0;
		picture.pic_fields.bits.transform_skip_enabled_flag = transformSkip ? 1 : 0;
		// Needed for the rate control to change the QP within a picture.
		picture.pic_fields.bits.cu_qp_delta_enabled_flag = cuQpDelta ? 1 : 0;
	}

	void VaapiH265Encoder::FillSlice(VAEncSliceParameterBufferHEVC & slice, bool idr) const
	{
		slice.slice_segment_address = 0;
		slice.num_ctu_in_slice = widthInCtbs * heightInCtbs;
		slice.slice_type = idr ? kSliceTypeI : kSliceTypeP;
		slice.slice_pic_parameter_set_id = 0;
		slice.num_ref_idx_l0_active_minus1 = 0;
		slice.num_ref_idx_l1_active_minus1 = 0;

		for (VAPictureHEVC & reference : slice.ref_pic_list0) {
			InvalidatePicture(reference);
		}
		for (VAPictureHEVC & reference : slice.ref_pic_list1) {
			InvalidatePicture(reference);
		}

		if (!idr) {
			slice.ref_pic_list0[0] = Reference();
		}

		slice.max_num_merge_cand = 5;
		slice.slice_qp_delta = 0;

		slice.slice_fields.bits.last_slice_of_pic_flag = 1;
		slice.slice_fields.bits.slice_sao_luma_flag = sao ? 1 : 0;
		slice.slice_fields.bits.slice_sao_chroma_flag = sao ? 1 : 0;
	}

	void VaapiH265Encoder::SetRates(const RateControlParameters & parameters)
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

	webrtc::VideoEncoder::EncoderInfo VaapiH265Encoder::GetEncoderInfo() const
	{
		EncoderInfo info;
		info.implementation_name = implementationName;
		info.is_hardware_accelerated = true;
		info.supports_native_handle = false;
		info.supports_simulcast = false;
		info.scaling_settings = outputProcessor.GetScalingSettings();
		// Whole coding blocks, so that the coded picture is the frame.
		info.requested_resolution_alignment = 1 << log2MinCbSize;

		return info;
	}
}
