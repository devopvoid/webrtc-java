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

#include "media/video/codec/linux/VaapiAv1Encoder.h"

#include "api/video/encoded_image.h"
#include "api/video_codecs/scalability_mode.h"
#include "modules/video_coding/include/video_codec_interface.h"
#include "modules/video_coding/include/video_error_codes.h"
#include "rtc_base/logging.h"

#include <algorithm>
#include <optional>

namespace jni
{
	namespace
	{
		// Key frames come when WebRTC asks for them; this only bounds how
		// long a stream may go without one if it never does.
		constexpr uint32_t kKeyFrameInterval = 3000;

		// Superblocks of 64x64; surfaces are kept to whole superblocks, as
		// FFmpeg's VA-API encoder does.
		constexpr uint32_t kSuperblockSize = 64;

		// AMD's driver codes the width in multiples of 8 and the height in
		// multiples of 2, and pads the picture to that without a render size,
		// so that a decoder would show the padding. The encoder asks WebRTC
		// for frames of that size.
		constexpr uint32_t kSizeAlignment = 8;

		// The largest tile, section A.3 of the AV1 specification. A frame
		// that needs more than one is left to libaom.
		constexpr uint32_t kMaxTileWidth = 4096;
		constexpr uint32_t kMaxTileArea = 4096 * 2304;

		// OBU types and frame types, sections 6.2.2 and 6.8.2.
		constexpr uint8_t kObuSequenceHeader = 1;
		constexpr uint8_t kObuFrameHeader = 3;
		constexpr uint32_t kKeyFrameType = 0;
		constexpr uint32_t kInterFrameType = 1;

		constexpr int kRefsPerFrame = 7;
		constexpr uint8_t kPrimaryRefNone = 7;
		// The reference frames LAST and GOLDEN, and their index among the
		// seven.
		constexpr uint32_t kLastFrame = 1;
		constexpr uint32_t kGoldenFrame = 4;
		constexpr int kGoldenIndex = 3;
		// LAST is the first reference of a frame, and the one its probabilities
		// and other state carry over from.
		constexpr uint8_t kPrimaryRefLast = 0;

		// What a frame that cannot be encoded returns: libaom takes over the
		// stream, which stays in AV1, rather than WebRTC changing the codec.
		constexpr int32_t kEncodeFailed = WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
		constexpr uint32_t kOrderHintBits = 8;

		// TxMode: largest, or chosen per block.
		constexpr uint32_t kTxModeLargest = 1;
		constexpr uint32_t kTxModeSelect = 2;
		// The bits of VAConfigAttribValEncAV1Ext2::tx_mode_support.
		constexpr uint32_t kTxModeLargestSupported = 0x02;
		constexpr uint32_t kTxModeSelectSupported = 0x04;

		// The quantizer index the frame header starts from, FFmpeg's; the
		// rate control sets that of each frame, within these bounds.
		constexpr uint8_t kQIndex = 128;
		constexpr uint8_t kMinQIndex = 1;
		constexpr uint8_t kMaxQIndex = 255;

		constexpr uint32_t kPackedHeaders = VA_ENC_PACKED_HEADER_SEQUENCE | VA_ENC_PACKED_HEADER_PICTURE;

		// The levels of annex A.3: seq_level_idx, the largest picture, the
		// largest width and height, the largest display rate in samples per
		// second and the largest bitrate of the main tier.
		struct Level
		{
			uint8_t index;
			uint64_t maxPictureSize;
			uint32_t maxWidth;
			uint32_t maxHeight;
			uint64_t maxDisplayRate;
			uint64_t maxBitrate;
		};

		constexpr Level kLevels[] = {
			{ 0, 147456, 2048, 1152, 4423680, 1500000 },
			{ 1, 278784, 2816, 1584, 8363520, 3000000 },
			{ 4, 665856, 4352, 2448, 19975680, 6000000 },
			{ 5, 1065024, 5504, 3096, 31950720, 10000000 },
			{ 8, 2359296, 6144, 3456, 70778880, 12000000 },
			{ 9, 2359296, 6144, 3456, 141557760, 20000000 },
			{ 12, 8912896, 8192, 4352, 267386880, 30000000 },
			{ 13, 8912896, 8192, 4352, 534773760, 40000000 },
			{ 14, 8912896, 8192, 4352, 1069547520, 60000000 },
			{ 16, 35651584, 16384, 8704, 1069547520, 60000000 },
		};

		// The level of a stream that fits none.
		constexpr uint8_t kMaxParametersLevel = 31;

		// ref_frame_idx: every reference frame names the first reference of
		// the frame, but golden, which names the second where there is one.
		std::array<uint8_t, kRefsPerFrame> RefFrameIdx(const VaapiTemporalLayers::Frame & frame)
		{
			std::array<uint8_t, kRefsPerFrame> refFrameIdx = {};

			if (!frame.references.empty()) {
				refFrameIdx.fill(static_cast<uint8_t>(frame.references[0]));
			}
			if (frame.references.size() > 1) {
				refFrameIdx[kGoldenIndex] = static_cast<uint8_t>(frame.references[1]);
			}

			return refFrameIdx;
		}

		uint32_t Align(uint32_t value, uint32_t alignment)
		{
			return (value + alignment - 1) / alignment * alignment;
		}

		int FloorLog2(uint32_t value)
		{
			int log = 0;

			while (value > 1) {
				value >>= 1;
				log++;
			}

			return log;
		}

		// tile_log2 of section 5.9.15.
		int TileLog2(uint32_t blockSize, uint32_t target)
		{
			int k = 0;

			while ((blockSize << k) < target) {
				k++;
			}

			return k;
		}

		// Writes the bits of an OBU's payload, most significant first.
		class BitWriter
		{
			public:
				void Put(uint32_t value, int bits)
				{
					for (int i = bits - 1; i >= 0; i--) {
						PutBit((value >> i) & 1);
					}
				}

				void PutBit(uint32_t bit)
				{
					if (count % 8 == 0) {
						bytes.push_back(0);
					}
					if (bit) {
						bytes.back() |= static_cast<uint8_t>(0x80 >> (count % 8));
					}

					count++;
				}

				// trailing_bits, section 5.3.4: a one, then zeros to the next
				// byte.
				void TrailingBits()
				{
					PutBit(1);

					while (count % 8 != 0) {
						PutBit(0);
					}
				}

				uint32_t Position() const
				{
					return count;
				}

				const std::vector<uint8_t> & Bytes() const
				{
					return bytes;
				}

			private:
				std::vector<uint8_t> bytes;
				uint32_t count = 0;
		};

		// An OBU of the payload: the header, with the extension that names
		// the temporal layer if there is one, and obu_size as a leb128 of the
		// given fixed length, which is what the driver rewrites the size of
		// the frame header OBU as.
		std::vector<uint8_t> MakeObu(uint8_t type, const std::vector<uint8_t> & payload, uint32_t sizeLength,
			std::optional<int> temporalId = std::nullopt)
		{
			std::vector<uint8_t> obu;
			obu.push_back(static_cast<uint8_t>((type << 3) | (temporalId ? 0x04 : 0) | 0x02));

			if (temporalId) {
				// temporal_id, and spatial_id 0.
				obu.push_back(static_cast<uint8_t>(*temporalId << 5));
			}

			const uint32_t size = static_cast<uint32_t>(payload.size());

			for (uint32_t i = 0; i < sizeLength; i++) {
				uint8_t byte = static_cast<uint8_t>((size >> (7 * i)) & 0x7F);

				if (i + 1 < sizeLength) {
					byte |= 0x80;
				}

				obu.push_back(byte);
			}

			obu.insert(obu.end(), payload.begin(), payload.end());

			return obu;
		}
	}

	VaapiAv1Encoder::VaapiAv1Encoder(VaapiLibrary & library, VAEntrypoint entrypoint,
		const webrtc::SdpVideoFormat & format) :
		library(library),
		entrypoint(entrypoint),
		implementationName("VA-API (" + library.Vendor() + ")"),
		session(library, "VA-API AV1"),
		initialized(false),
		codecSettings(),
		bitrateBps(0),
		framerate(30),
		ratesChanged(false),
		obuSizeLength(0),
		txMode(0),
		referenceValid(false),
		frameNumber(0),
		callback(nullptr),
		outputProcessor(webrtc::kVideoCodecAV1, format)
	{
		QueryCapabilities();
	}

	VaapiAv1Encoder::~VaapiAv1Encoder()
	{
		Release();
	}

	void VaapiAv1Encoder::QueryCapabilities()
	{
		VAConfigAttrib attributes[2] = {};
		attributes[0].type = VAConfigAttribEncPackedHeaders;
		attributes[1].type = VAConfigAttribEncAV1Ext2;

		if (library.Api().GetConfigAttributes(library.Display(), VAProfileAV1Profile0, entrypoint, attributes, 2) !=
			VA_STATUS_SUCCESS)
		{
			return;
		}

		// The headers are written here; a driver that does not take them is
		// not used.
		if (attributes[0].value == VA_ATTRIB_NOT_SUPPORTED || (attributes[0].value & kPackedHeaders) != kPackedHeaders) {
			return;
		}
		if (attributes[1].value == VA_ATTRIB_NOT_SUPPORTED) {
			return;
		}

		VAConfigAttribValEncAV1Ext2 ext2;
		ext2.value = attributes[1].value;

		// Coded lossless frames, which ONLY_4X4 is for, are not made.
		if (ext2.bits.tx_mode_support & kTxModeSelectSupported) {
			txMode = kTxModeSelect;
		}
		else if (ext2.bits.tx_mode_support & kTxModeLargestSupported) {
			txMode = kTxModeLargest;
		}
		else {
			return;
		}

		obuSizeLength = ext2.bits.obu_size_bytes_minus1 + 1;
	}

	int32_t VaapiAv1Encoder::InitEncode(const webrtc::VideoCodec * settings, const Settings & encoderSettings)
	{
		if (settings == nullptr || settings->width == 0 || settings->height == 0) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}
		if (settings->numberOfSimulcastStreams > 1) {
			return WEBRTC_VIDEO_CODEC_ERR_SIMULCAST_PARAMETERS_NOT_SUPPORTED;
		}
		// Temporal layers; spatial ones are left to libaom.
		const webrtc::ScalabilityMode mode = settings->GetScalabilityMode().value_or(webrtc::ScalabilityMode::kL1T1);

		if (mode != webrtc::ScalabilityMode::kL1T1 && mode != webrtc::ScalabilityMode::kL1T2
			&& mode != webrtc::ScalabilityMode::kL1T3)
		{
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}
		if (obuSizeLength == 0) {
			RTC_LOG(LS_WARNING) << implementationName << " AV1: the driver takes no packed headers, or does not say "
				"how it codes them";
			return WEBRTC_VIDEO_CODEC_ERROR;
		}
		if (settings->width < kSizeAlignment || settings->height < kSizeAlignment) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}
		// One tile, which is all this encoder writes.
		if (settings->width > kMaxTileWidth || uint64_t(Align(settings->width, kSuperblockSize))
			* Align(settings->height, kSuperblockSize) > kMaxTileArea)
		{
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}

		Release();

		// The first frames of a source can come before WebRTC knows the
		// alignment the encoder asks for, so the encoder takes the aligned
		// size below and drops the last rows and columns of such frames.
		codecSettings = *settings;
		codecSettings.width -= codecSettings.width % kSizeAlignment;
		codecSettings.height -= codecSettings.height % kSizeAlignment;
		bitrateBps = std::max(1u, codecSettings.startBitrate) * 1000;
		framerate = std::max(1u, codecSettings.maxFramerate);
		ratesChanged = true;
		referenceValid = false;
		frameNumber = 0;
		outputProcessor.Reset();
		layers.Configure(mode, bitrateBps, VaapiTemporalLayers::LayerRateControl(library, VAProfileAV1Profile0,
			entrypoint, webrtc::ScalabilityModeToNumTemporalLayers(mode)));

		if (!outputProcessor.SetScalabilityMode(mode)) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}

		const uint32_t surfaceWidth = Align(codecSettings.width, kSuperblockSize);
		const uint32_t surfaceHeight = Align(codecSettings.height, kSuperblockSize);

		if (!session.Create(VAProfileAV1Profile0, entrypoint, codecSettings.width, codecSettings.height, surfaceWidth,
			surfaceHeight, kPackedHeaders, VaapiTemporalLayers::kReconstructedSurfaces))
		{
			Release();
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		WriteSequenceHeader(sequenceHeader);

		initialized = true;

		RTC_LOG(LS_INFO) << implementationName << " AV1 initialized: " << codecSettings.width << "x"
			<< codecSettings.height << " at " << bitrateBps << " bps, " << webrtc::ScalabilityModeToString(mode);

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t VaapiAv1Encoder::RegisterEncodeCompleteCallback(webrtc::EncodedImageCallback * encodeCallback)
	{
		callback = encodeCallback;

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t VaapiAv1Encoder::Release()
	{
		session.Destroy();

		initialized = false;
		referenceValid = false;

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t VaapiAv1Encoder::Encode(const webrtc::VideoFrame & frame,
		const std::vector<webrtc::VideoFrameType> * frameTypes)
	{
		if (!initialized || callback == nullptr) {
			return WEBRTC_VIDEO_CODEC_UNINITIALIZED;
		}

		bool keyFrame = !referenceValid || frameNumber >= kKeyFrameInterval;

		if (frameTypes != nullptr) {
			keyFrame |= std::any_of(frameTypes->begin(), frameTypes->end(), [](webrtc::VideoFrameType type) {
				return type == webrtc::VideoFrameType::kVideoFrameKey;
			});
		}

		if (keyFrame) {
			frameNumber = 0;
		}

		const auto layerFrame = outputProcessor.NextFrameConfig(keyFrame);

		// No layer is encoded now, as when WebRTC gives none a bitrate.
		if (!layerFrame) {
			return WEBRTC_VIDEO_CODEC_OK;
		}

		const auto layerPlan = layers.Plan(keyFrame, *layerFrame);

		if (!layerPlan) {
			RTC_LOG(LS_WARNING) << implementationName << " AV1: a frame refers to a slot that holds no frame";
			return kEncodeFailed;
		}

		std::vector<uint8_t> output;

		// A frame up to the alignment larger is cropped; one of another size,
		// which raced with a change of size, is scaled.
		const bool encoded = session.Upload(frame, static_cast<int>(codecSettings.width),
			static_cast<int>(codecSettings.height), kSizeAlignment - 1) && Submit(keyFrame, *layerPlan)
			&& session.ReadOutput(output);

		session.DropBuffers();


		if (!encoded) {
			return kEncodeFailed;
		}

		webrtc::EncodedImage image;
		image._encodedWidth = codecSettings.width;
		image._encodedHeight = codecSettings.height;
		image.SetRtpTimestamp(frame.rtp_timestamp());
		image.capture_time_ms_ = frame.render_time_ms();
		image.ntp_time_ms_ = frame.ntp_time_ms();
		image.rotation_ = frame.rotation();

		webrtc::CodecSpecificInfo info;

		if (!outputProcessor.Process(output, keyFrame, *layerFrame, image, info)) {
			return kEncodeFailed;
		}

		layers.Commit(*layerPlan);

		referenceValid = true;
		frameNumber++;
		ratesChanged = false;

		callback->OnEncodedImage(image, &info);

		return WEBRTC_VIDEO_CODEC_OK;
	}

	bool VaapiAv1Encoder::Submit(bool keyFrame, const VaapiTemporalLayers::Frame & frame)
	{
		const uint32_t sbCols = Align(codecSettings.width, kSuperblockSize) / kSuperblockSize;
		const uint32_t sbRows = Align(codecSettings.height, kSuperblockSize) / kSuperblockSize;
		const bool layered = layers.Count() > 1;

		if (keyFrame) {
			VAEncSequenceParameterBufferAV1 sequence = {};
			sequence.seq_profile = 0;
			sequence.seq_level_idx = Level();
			sequence.seq_tier = 0;
			// Key frames only where asked for.
			sequence.intra_period = kKeyFrameInterval;
			sequence.ip_period = 1;
			sequence.hierarchical_flag = layered ? 1 : 0;
			sequence.bits_per_second = bitrateBps;
			sequence.seq_fields.bits.enable_order_hint = 1;
			sequence.seq_fields.bits.enable_cdef = 1;
			sequence.order_hint_bits_minus_1 = kOrderHintBits - 1;

			if (!session.AddBuffer(VAEncSequenceParameterBufferType, &sequence, sizeof(sequence))) {
				return false;
			}
		}

		if ((keyFrame || ratesChanged) && !layers.AddRateControl(session, bitrateBps, framerate, keyFrame)) {
			return false;
		}

		std::vector<uint8_t> frameHeader;
		FrameHeaderLayout layout;
		WriteFrameHeader(frameHeader, layout, keyFrame, frame);

		VAEncPictureParameterBufferAV1 picture = {};
		picture.frame_width_minus_1 = static_cast<uint16_t>(codecSettings.width - 1);
		picture.frame_height_minus_1 = static_cast<uint16_t>(codecSettings.height - 1);
		picture.reconstructed_frame = session.Reconstructed(frame.surface);
		picture.coded_buf = session.CodedBuffer();
		picture.refresh_frame_flags = frame.refreshFlags;

		for (VASurfaceID & reference : picture.reference_frames) {
			reference = VA_INVALID_SURFACE;
		}

		if (keyFrame) {
			picture.primary_ref_frame = kPrimaryRefNone;
			picture.picture_flags.bits.frame_type = kKeyFrameType;
			picture.picture_flags.bits.error_resilient_mode = 1;
		}
		else {
			for (int slot = 0; slot < layers.SlotCount(); slot++) {
				if (layers.Surface(slot) >= 0) {
					picture.reference_frames[slot] = session.Reconstructed(layers.Surface(slot));
				}
			}

			const std::array<uint8_t, kRefsPerFrame> refFrameIdx = RefFrameIdx(frame);
			std::copy(refFrameIdx.begin(), refFrameIdx.end(), picture.ref_frame_idx);

			picture.primary_ref_frame = kPrimaryRefLast;
			picture.ref_frame_ctrl_l0.fields.search_idx0 = kLastFrame;

			if (frame.references.size() > 1) {
				picture.ref_frame_ctrl_l0.fields.search_idx1 = kGoldenFrame;
			}

			picture.picture_flags.bits.frame_type = kInterFrameType;
		}

		if (layered) {
			picture.temporal_id = static_cast<uint8_t>(frame.temporalId);
			picture.hierarchical_level_plus1 = static_cast<uint8_t>(frame.temporalId + 1);
			picture.tile_group_obu_hdr_info.bits.obu_extension_flag = 1;
			picture.tile_group_obu_hdr_info.bits.temporal_id = static_cast<uint8_t>(frame.temporalId);
		}

		picture.order_hint = static_cast<uint8_t>(frameNumber);
		picture.base_qindex = kQIndex;
		picture.min_base_qindex = kMinQIndex;
		picture.max_base_qindex = kMaxQIndex;
		picture.mode_control_flags.bits.tx_mode = txMode;

		picture.tile_cols = 1;
		picture.tile_rows = 1;
		picture.width_in_sbs_minus_1[0] = static_cast<uint16_t>(sbCols - 1);
		picture.height_in_sbs_minus_1[0] = static_cast<uint16_t>(sbRows - 1);
		picture.tile_group_obu_hdr_info.bits.obu_has_size_field = 1;

		// Where the driver writes what its rate control decides, and the size
		// of the frame header OBU, which comes after the sequence header in
		// a key frame.
		picture.bit_offset_qindex = layout.qindexOffset;
		picture.bit_offset_loopfilter_params = layout.loopFilterOffset;
		picture.bit_offset_cdef_params = layout.cdefOffset;
		picture.size_in_bits_cdef_params = layout.cdefSize;
		picture.size_in_bits_frame_hdr_obu = layout.size;
		picture.byte_offset_frame_hdr_obu_size = (keyFrame ? static_cast<uint32_t>(sequenceHeader.size()) : 0)
			+ (layered ? 2 : 1);

		VAEncTileGroupBufferAV1 tileGroup = {};
		tileGroup.tg_start = 0;
		tileGroup.tg_end = 0;

		return session.AddBuffer(VAEncPictureParameterBufferType, &picture, sizeof(picture))
			&& (!keyFrame || session.AddPackedHeader(VAEncPackedHeaderSequence, sequenceHeader, sequenceHeader.size() * 8))
			&& session.AddPackedHeader(VAEncPackedHeaderPicture, frameHeader, layout.size)
			&& session.AddBuffer(VAEncSliceParameterBufferType, &tileGroup, sizeof(tileGroup))
			&& session.Encode();
	}

	void VaapiAv1Encoder::WriteSequenceHeader(std::vector<uint8_t> & obu) const
	{
		// sequence_header_obu, section 5.5, with the tools FFmpeg's VA-API
		// encoder turns on: order hints and CDEF.
		const int widthBits = FloorLog2(codecSettings.width) + 1;
		const int heightBits = FloorLog2(codecSettings.height) + 1;
		const uint8_t level = Level();

		BitWriter writer;
		// seq_profile, still_picture, reduced_still_picture_header.
		writer.Put(0, 3);
		writer.Put(0, 1);
		writer.Put(0, 1);
		// timing_info_present_flag, initial_display_delay_present_flag.
		writer.Put(0, 1);
		writer.Put(0, 1);
		// operating_points_cnt_minus_1: with temporal layers, an operating
		// point for each, from all layers down to the lowest. Then
		// operating_point_idc, of spatial layer 0 and those temporal layers,
		// seq_level_idx, and seq_tier above level 3.3.
		const int temporalLayers = layers.Count();

		writer.Put(temporalLayers - 1, 5);

		for (int point = 0; point < temporalLayers; point++) {
			const uint32_t idc = temporalLayers > 1 ? 0x100 | ((1u << (temporalLayers - point)) - 1) : 0;

			writer.Put(idc, 12);
			writer.Put(level, 5);

			if (level > 7) {
				writer.Put(0, 1);
			}
		}

		writer.Put(widthBits - 1, 4);
		writer.Put(heightBits - 1, 4);
		writer.Put(codecSettings.width - 1, widthBits);
		writer.Put(codecSettings.height - 1, heightBits);
		// frame_id_numbers_present_flag, use_128x128_superblock,
		// enable_filter_intra, enable_intra_edge_filter,
		// enable_interintra_compound, enable_masked_compound,
		// enable_warped_motion, enable_dual_filter.
		writer.Put(0, 8);
		// enable_order_hint, enable_jnt_comp, enable_ref_frame_mvs.
		writer.Put(1, 1);
		writer.Put(0, 1);
		writer.Put(0, 1);
		// seq_choose_screen_content_tools and seq_force_screen_content_tools:
		// no screen content tools, so seq_force_integer_mv is not coded.
		writer.Put(0, 1);
		writer.Put(0, 1);
		writer.Put(kOrderHintBits - 1, 3);
		// enable_superres, enable_cdef, enable_restoration.
		writer.Put(0, 1);
		writer.Put(1, 1);
		writer.Put(0, 1);
		// color_config, section 5.5.2: 8 bit 4:2:0 without a color
		// description: high_bitdepth, mono_chrome,
		// color_description_present_flag, color_range,
		// chroma_sample_position, separate_uv_delta_q.
		writer.Put(0, 1);
		writer.Put(0, 1);
		writer.Put(0, 1);
		writer.Put(0, 1);
		writer.Put(0, 2);
		writer.Put(0, 1);
		// film_grain_params_present.
		writer.Put(0, 1);
		writer.TrailingBits();

		obu = MakeObu(kObuSequenceHeader, writer.Bytes(), obuSizeLength);
	}

	void VaapiAv1Encoder::WriteFrameHeader(std::vector<uint8_t> & obu, FrameHeaderLayout & layout, bool keyFrame,
		const VaapiTemporalLayers::Frame & frame) const
	{
		// uncompressed_header, section 5.9, as FFmpeg's VA-API encoder writes
		// it, for the sequence header above.
		const bool layered = layers.Count() > 1;
		const std::array<uint8_t, kRefsPerFrame> refFrameIdx = RefFrameIdx(frame);
		const uint32_t miCols = 2 * ((codecSettings.width + 7) >> 3);
		const uint32_t miRows = 2 * ((codecSettings.height + 7) >> 3);
		const uint32_t sbCols = (miCols + 15) >> 4;
		const uint32_t sbRows = (miRows + 15) >> 4;
		// The OBU header, its extension, and obu_size come before the payload.
		const uint32_t payloadOffset = ((layered ? 2 : 1) + obuSizeLength) * 8;

		BitWriter writer;
		// show_existing_frame, frame_type, show_frame.
		writer.Put(0, 1);
		writer.Put(keyFrame ? kKeyFrameType : kInterFrameType, 2);
		writer.Put(1, 1);

		if (!keyFrame) {
			// error_resilient_mode; a key frame that is shown has it set.
			writer.Put(0, 1);
		}

		// disable_cdf_update, frame_size_override_flag, order_hint.
		writer.Put(0, 1);
		writer.Put(0, 1);
		writer.Put(frameNumber & ((1u << kOrderHintBits) - 1), kOrderHintBits);

		if (!keyFrame) {
			// primary_ref_frame, refresh_frame_flags.
			writer.Put(kPrimaryRefLast, 3);
			writer.Put(frame.refreshFlags, 8);
			// frame_refs_short_signaling, then ref_frame_idx.
			writer.Put(0, 1);

			for (int i = 0; i < kRefsPerFrame; i++) {
				writer.Put(refFrameIdx[i], 3);
			}
		}

		// frame_size() takes the size of the sequence header; render_size():
		// render_and_frame_size_different.
		writer.Put(0, 1);

		if (!keyFrame) {
			// allow_high_precision_mv, is_filter_switchable,
			// interpolation_filter (EIGHTTAP), is_motion_mode_switchable.
			writer.Put(0, 1);
			writer.Put(0, 1);
			writer.Put(0, 2);
			writer.Put(0, 1);
		}

		// disable_frame_end_update_cdf.
		writer.Put(0, 1);

		// tile_info, section 5.9.15: one uniform tile, which takes no more
		// tile columns or rows where it could.
		writer.Put(1, 1);

		if (TileLog2(1, std::min(sbCols, 64u)) > 0) {
			writer.Put(0, 1);
		}
		if (TileLog2(1, std::min(sbRows, 64u)) > 0) {
			writer.Put(0, 1);
		}

		// quantization_params: base_q_idx, no delta coded for DeltaQYDc,
		// DeltaQUDc and DeltaQUAc, using_qmatrix.
		layout.qindexOffset = payloadOffset + writer.Position();
		writer.Put(kQIndex, 8);
		writer.Put(0, 1);
		writer.Put(0, 1);
		writer.Put(0, 1);
		writer.Put(0, 1);
		// segmentation_enabled; delta_q_present, base_q_idx being above 0.
		writer.Put(0, 1);
		writer.Put(0, 1);

		// loop_filter_params: loop_filter_level[0] and [1] of 0, which leave
		// out those of U and V, loop_filter_sharpness,
		// loop_filter_delta_enabled.
		layout.loopFilterOffset = payloadOffset + writer.Position();
		writer.Put(0, 6);
		writer.Put(0, 6);
		writer.Put(0, 3);
		writer.Put(0, 1);

		// cdef_params: cdef_damping_minus_3, cdef_bits, and the strengths of
		// the one filter.
		layout.cdefOffset = payloadOffset + writer.Position();
		writer.Put(0, 2);
		writer.Put(0, 2);
		writer.Put(0, 4);
		writer.Put(0, 2);
		writer.Put(0, 4);
		writer.Put(0, 2);
		layout.cdefSize = payloadOffset + writer.Position() - layout.cdefOffset;

		// read_tx_mode: tx_mode_select.
		writer.Put(txMode == kTxModeSelect ? 1 : 0, 1);

		if (!keyFrame) {
			// reference_select.
			writer.Put(0, 1);
		}

		// reduced_tx_set.
		writer.Put(0, 1);

		if (!keyFrame) {
			// global_motion_params: is_global of each reference frame.
			writer.Put(0, kRefsPerFrame);
		}

		writer.TrailingBits();

		obu = MakeObu(kObuFrameHeader, writer.Bytes(), obuSizeLength,
			layered ? std::optional<int>(frame.temporalId) : std::nullopt);
		layout.size = static_cast<uint32_t>(obu.size() * 8);
	}

	uint8_t VaapiAv1Encoder::Level() const
	{
		const uint64_t pictureSize = uint64_t(codecSettings.width) * codecSettings.height;
		const uint64_t displayRate = pictureSize * std::max(1u, codecSettings.maxFramerate);
		const uint64_t bitrate = uint64_t(std::max(codecSettings.maxBitrate, codecSettings.startBitrate)) * 1000;

		for (const auto & level : kLevels) {
			if (pictureSize <= level.maxPictureSize && codecSettings.width <= level.maxWidth
				&& codecSettings.height <= level.maxHeight && displayRate <= level.maxDisplayRate
				&& bitrate <= level.maxBitrate)
			{
				return level.index;
			}
		}

		return kMaxParametersLevel;
	}

	void VaapiAv1Encoder::SetRates(const RateControlParameters & parameters)
	{
		const uint32_t bitrate = parameters.bitrate.get_sum_bps();

		// A zero bitrate pauses the stream; the encoder gets no frames then.
		if (bitrate == 0) {
			return;
		}

		bitrateBps = bitrate;
		outputProcessor.OnRatesUpdated(parameters.bitrate);
		layers.OnRatesUpdated(parameters.bitrate);

		if (parameters.framerate_fps >= 1.0) {
			framerate = static_cast<uint32_t>(parameters.framerate_fps + 0.5);
		}

		// Applied with the next frame.
		ratesChanged = true;
	}

	webrtc::VideoEncoder::EncoderInfo VaapiAv1Encoder::GetEncoderInfo() const
	{
		EncoderInfo info;
		info.implementation_name = implementationName;
		info.is_hardware_accelerated = true;
		info.supports_native_handle = false;
		info.supports_simulcast = false;
		info.scaling_settings = outputProcessor.GetScalingSettings();
		info.requested_resolution_alignment = kSizeAlignment;

		layers.FillFpsAllocation(info);

		return info;
	}
}
