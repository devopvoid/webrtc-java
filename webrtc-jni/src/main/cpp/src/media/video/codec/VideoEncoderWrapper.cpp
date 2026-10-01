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

#include "media/video/codec/VideoEncoderWrapper.h"
#include "media/video/codec/EncodedImage.h"
#include "media/video/codec/JavaLocalFrame.h"
#include "media/video/codec/VideoCodecUtils.h"
#include "api/VideoFrame.h"
#include "JavaClasses.h"
#include "JavaPrimitive.h"
#include "JavaString.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

#include "api/video/render_resolution.h"
#include "api/video/video_codec_constants.h"
#include "modules/video_coding/codecs/interface/common_constants.h"
#include "modules/video_coding/include/video_error_codes.h"
#include "modules/video_coding/utility/vp8_header_parser.h"
#include "modules/video_coding/utility/vp9_uncompressed_header_parser.h"
#include "rtc_base/logging.h"
#include "rtc_base/time_utils.h"

#include <algorithm>

namespace jni
{
	namespace
	{
		// Enough for the objects of one call into the encoder.
		constexpr jint kLocalFrameCapacity = 32;

		// How many frames the encoder may hold back before the oldest records
		// are given up, so that one that never produces output does not make
		// them grow without bound. Several seconds of video.
		constexpr size_t kMaxPendingFrames = 300;

		// The QP thresholds WebRTC's own encoders use for quality scaling.
		constexpr int kLowVp8QpThreshold = 29;
		constexpr int kHighVp8QpThreshold = 95;
		// The QP is parsed from the VP9 bitstream, whose range is 0 to 255.
		constexpr int kLowVp9QpThreshold = 96;
		constexpr int kHighVp9QpThreshold = 185;
		constexpr int kLowH264QpThreshold = 24;
		constexpr int kHighH264QpThreshold = 37;

		std::optional<int> OptionalInt(JNIEnv * env, jobject value)
		{
			if (value == nullptr) {
				return std::nullopt;
			}

			return Integer::getValue(env, value);
		}
	}

	VideoEncoderWrapper::VideoEncoderWrapper(JNIEnv * env, jobject encoder, const webrtc::SdpVideoFormat & format) :
		encoder(env, encoder),
		javaClass(JavaClasses::get<JavaVideoEncoderClass>(env)),
		format(format),
		callback(nullptr),
		initialized(false),
		numberOfCores(1),
		codecSettings(),
		gofIndex(0)
	{
		UpdateEncoderInfo(env);
	}

	VideoEncoderWrapper::~VideoEncoderWrapper()
	{
		JNIEnv * env = AttachCurrentThread();

		if (env != nullptr) {
			InvalidateCallback(env);
		}
	}

	int32_t VideoEncoderWrapper::InitEncode(const webrtc::VideoCodec * settings, const Settings & encoderSettings)
	{
		JNIEnv * env = AttachCurrentThread();

		if (env == nullptr || settings == nullptr) {
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		codecSettings = *settings;
		capabilities = encoderSettings.capabilities;
		numberOfCores = encoderSettings.number_of_cores;

		return InitEncodeInternal(env);
	}

	int32_t VideoEncoderWrapper::InitEncodeInternal(JNIEnv * env)
	{
		JavaLocalFrame localFrame(env, kLocalFrameCapacity);

		bool automaticResizeOn;

		switch (codecSettings.codecType) {
			case webrtc::kVideoCodecVP8:
				automaticResizeOn = codecSettings.VP8()->automaticResizeOn;
				break;

			case webrtc::kVideoCodecVP9:
				automaticResizeOn = codecSettings.VP9()->automaticResizeOn;
				gof.SetGofInfoVP9(webrtc::TemporalStructureMode::kTemporalStructureMode1);
				gofIndex = 0;
				break;

			default:
				automaticResizeOn = true;
				break;
		}

		const bool lossNotification = capabilities.has_value() && capabilities->loss_notification;
		const bool screenContent = codecSettings.mode == webrtc::VideoCodecMode::kScreensharing;

		jobject settings = env->NewObject(javaClass->settingsClass, javaClass->settingsCtor,
			static_cast<jint>(numberOfCores),
			static_cast<jint>(codecSettings.width),
			static_cast<jint>(codecSettings.height),
			static_cast<jint>(codecSettings.startBitrate),
			static_cast<jint>(codecSettings.minBitrate),
			static_cast<jint>(codecSettings.maxBitrate),
			static_cast<jint>(codecSettings.maxFramerate),
			static_cast<jint>(codecSettings.numberOfSimulcastStreams),
			automaticResizeOn ? JNI_TRUE : JNI_FALSE,
			screenContent ? JNI_TRUE : JNI_FALSE,
			lossNotification ? JNI_TRUE : JNI_FALSE);

		if (ClearCodecException(env, "initEncode") || settings == nullptr) {
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		// A callback of its own for each initialization, so that frames of an
		// earlier one cannot reach this one.
		InvalidateCallback(env);

		jobject jCallback = env->NewObject(javaClass->callbackClass, javaClass->callbackCtor,
			reinterpret_cast<jlong>(this));

		if (ClearCodecException(env, "initEncode") || jCallback == nullptr) {
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		javaCallback = std::make_unique<JavaGlobalRef<jobject>>(env, jCallback);

		jobject status = env->CallObjectMethod(encoder, javaClass->initEncode, settings, jCallback);
		int32_t result = ToNativeCodecStatus(env, status, "initEncode");

		RTC_LOG(LS_INFO) << "Java encoder initEncode: " << result;

		// Some properties of the encoder may depend on its settings.
		UpdateEncoderInfo(env);

		initialized = result == WEBRTC_VIDEO_CODEC_OK;

		return result;
	}

	int32_t VideoEncoderWrapper::RegisterEncodeCompleteCallback(webrtc::EncodedImageCallback * encodeCallback)
	{
		callback = encodeCallback;

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t VideoEncoderWrapper::Release()
	{
		JNIEnv * env = AttachCurrentThread();

		if (env == nullptr) {
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		return ReleaseInternal(env);
	}

	int32_t VideoEncoderWrapper::ReleaseInternal(JNIEnv * env)
	{
		JavaLocalFrame localFrame(env, kLocalFrameCapacity);

		jobject status = env->CallObjectMethod(encoder, javaClass->release);
		int32_t result = ToNativeCodecStatus(env, status, "release");

		RTC_LOG(LS_INFO) << "Java encoder release: " << result;

		InvalidateCallback(env);

		{
			webrtc::MutexLock lock(&frameExtraInfosLock);
			frameExtraInfos.clear();
		}

		initialized = false;

		return result;
	}

	int32_t VideoEncoderWrapper::Encode(const webrtc::VideoFrame & frame,
		const std::vector<webrtc::VideoFrameType> * frameTypes)
	{
		if (!initialized) {
			return WEBRTC_VIDEO_CODEC_UNINITIALIZED;
		}

		JNIEnv * env = AttachCurrentThread();

		if (env == nullptr) {
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		JavaLocalFrame localFrame(env, kLocalFrameCapacity);

		const auto javaFrameClass = JavaClasses::get<JavaVideoFrameClass>(env);
		jobject jFrame = nullptr;

		try {
			jFrame = VideoFrame::toJava(env, frame).release();
		}
		catch (...) {
			ClearCodecException(env, "encode");
		}

		if (jFrame == nullptr) {
			RTC_LOG(LS_WARNING) << "Java encoder: dropping a frame that cannot be converted to I420";

			return WEBRTC_VIDEO_CODEC_OK;
		}

		std::vector<jint> types;

		if (frameTypes != nullptr) {
			for (webrtc::VideoFrameType type : *frameTypes) {
				types.push_back(static_cast<jint>(type));
			}
		}
		if (types.empty()) {
			types.push_back(static_cast<jint>(webrtc::VideoFrameType::kVideoFrameDelta));
		}

		jobject info = nullptr;
		jintArray jTypes = env->NewIntArray(static_cast<jsize>(types.size()));

		if (jTypes != nullptr) {
			env->SetIntArrayRegion(jTypes, 0, static_cast<jsize>(types.size()), types.data());

			info = env->CallStaticObjectMethod(javaClass->encodeInfoClass, javaClass->encodeInfoFromNative, jTypes);
		}

		int32_t result;

		if (ClearCodecException(env, "encode") || info == nullptr) {
			result = WEBRTC_VIDEO_CODEC_ERROR;
		}
		else {
			{
				webrtc::MutexLock lock(&frameExtraInfosLock);

				if (frameExtraInfos.size() >= kMaxPendingFrames) {
					frameExtraInfos.pop_front();
				}

				frameExtraInfos.push_back(FrameExtraInfo {
					frame.timestamp_us() * webrtc::kNumNanosecsPerMicrosec,
					frame.rtp_timestamp()
				});
			}

			jobject status = env->CallObjectMethod(encoder, javaClass->encode, jFrame, info);
			result = ToNativeCodecStatus(env, status, "encode");
		}

		// An encoder that needs the frame any longer has retained it.
		env->CallVoidMethod(jFrame, javaFrameClass->release);
		ClearCodecException(env, "encode");

		return HandleReturnCode(env, result, "encode");
	}

	void VideoEncoderWrapper::SetRates(const RateControlParameters & parameters)
	{
		JNIEnv * env = AttachCurrentThread();

		if (env == nullptr) {
			return;
		}

		JavaLocalFrame localFrame(env, kLocalFrameCapacity);

		jobjectArray layers = env->NewObjectArray(webrtc::kMaxSpatialLayers, javaClass->intArrayClass, nullptr);

		if (ClearCodecException(env, "setRates") || layers == nullptr) {
			return;
		}

		for (size_t spatial = 0; spatial < webrtc::kMaxSpatialLayers; spatial++) {
			jint bitrates[webrtc::kMaxTemporalStreams];

			for (size_t temporal = 0; temporal < webrtc::kMaxTemporalStreams; temporal++) {
				bitrates[temporal] = static_cast<jint>(parameters.bitrate.GetBitrate(spatial, temporal));
			}

			jintArray layer = env->NewIntArray(webrtc::kMaxTemporalStreams);

			if (layer == nullptr) {
				ClearCodecException(env, "setRates");
				return;
			}

			env->SetIntArrayRegion(layer, 0, webrtc::kMaxTemporalStreams, bitrates);
			env->SetObjectArrayElement(layers, static_cast<jsize>(spatial), layer);
			env->DeleteLocalRef(layer);
		}

		jobject allocation = env->NewObject(javaClass->bitrateAllocationClass, javaClass->bitrateAllocationCtor, layers);
		jobject jParameters = nullptr;

		if (allocation != nullptr) {
			jParameters = env->NewObject(javaClass->rateControlClass, javaClass->rateControlCtor, allocation,
				static_cast<jdouble>(parameters.framerate_fps));
		}

		if (ClearCodecException(env, "setRates") || jParameters == nullptr) {
			return;
		}

		jobject status = env->CallObjectMethod(encoder, javaClass->setRates, jParameters);
		int32_t result = ToNativeCodecStatus(env, status, "setRates");

		if (result < 0) {
			RTC_LOG(LS_WARNING) << "Java encoder setRates: " << result;
		}
	}

	webrtc::VideoEncoder::EncoderInfo VideoEncoderWrapper::GetEncoderInfo() const
	{
		return encoderInfo;
	}

	void VideoEncoderWrapper::OnEncodedFrame(JNIEnv * env, jobject jImage)
	{
		JavaLocalRef<jobject> imageRef(env, env->NewLocalRef(jImage));

		webrtc::EncodedImage image = EncodedImage::toNative(env, imageRef);
		const int64_t captureTimeNs = EncodedImage::getCaptureTimeNs(env, imageRef);

		FrameExtraInfo extraInfo;

		{
			webrtc::MutexLock lock(&frameExtraInfosLock);

			// Frames come out in the order they went in, though an encoder
			// may drop some, so the records of older frames are stale.
			while (!frameExtraInfos.empty() && frameExtraInfos.front().captureTimeNs < captureTimeNs) {
				frameExtraInfos.pop_front();
			}

			if (frameExtraInfos.empty() || frameExtraInfos.front().captureTimeNs != captureTimeNs) {
				RTC_LOG(LS_WARNING) << "Java encoder produced a frame for no frame it was given, capture time "
					<< captureTimeNs << " ns";
				return;
			}

			extraInfo = frameExtraInfos.front();
			frameExtraInfos.pop_front();
		}

		image.SetRtpTimestamp(extraInfo.rtpTimestamp);
		image.capture_time_ms_ = captureTimeNs / webrtc::kNumNanosecsPerMillisec;

		if (image.qp_ < 0) {
			image.qp_ = ParseQp(image);
		}

		webrtc::CodecSpecificInfo info = ParseCodecSpecificInfo(image);
		webrtc::EncodedImageCallback * encodeCallback = callback.load();

		if (encodeCallback != nullptr) {
			encodeCallback->OnEncodedImage(image, &info);
		}
	}

	int32_t VideoEncoderWrapper::HandleReturnCode(JNIEnv * env, int32_t status, const char * method)
	{
		if (status >= 0) {
			// OK or NO_OUTPUT, or OK with a request.
			return status;
		}

		RTC_LOG(LS_WARNING) << "Java encoder " << method << ": " << status;

		if (status == WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE || status == WEBRTC_VIDEO_CODEC_UNINITIALIZED) {
			return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
		}

		// Any other error may be one the encoder recovers from.
		if (ReleaseInternal(env) == WEBRTC_VIDEO_CODEC_OK && InitEncodeInternal(env) == WEBRTC_VIDEO_CODEC_OK) {
			RTC_LOG(LS_WARNING) << "Java encoder reset";

			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		RTC_LOG(LS_WARNING) << "Java encoder could not be reset";

		return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
	}

	void VideoEncoderWrapper::InvalidateCallback(JNIEnv * env)
	{
		if (javaCallback == nullptr) {
			return;
		}

		// Waits for a frame that is being handed over right now.
		env->CallVoidMethod(javaCallback->get(), javaClass->callbackInvalidate);
		ClearCodecException(env, "invalidate");

		javaCallback.reset();
	}

	void VideoEncoderWrapper::UpdateEncoderInfo(JNIEnv * env)
	{
		JavaLocalFrame localFrame(env, kLocalFrameCapacity);

		encoderInfo.supports_native_handle = false;
		encoderInfo.scaling_settings = GetScalingSettings(env);
		encoderInfo.resolution_bitrate_limits = GetResolutionBitrateLimits(env);

		jobject name = env->CallObjectMethod(encoder, javaClass->getImplementationName);

		if (!ClearCodecException(env, "getImplementationName") && name != nullptr) {
			try {
				encoderInfo.implementation_name = JavaString::toNative(env,
					JavaLocalRef<jstring>(env, static_cast<jstring>(env->NewLocalRef(name))));
			}
			catch (...) {
				ClearCodecException(env, "getImplementationName");
			}
		}

		jboolean hardware = env->CallBooleanMethod(encoder, javaClass->isHardwareEncoder);

		if (!ClearCodecException(env, "isHardwareEncoder")) {
			encoderInfo.is_hardware_accelerated = hardware == JNI_TRUE;
		}

		jobject info = env->CallObjectMethod(encoder, javaClass->getEncoderInfo);

		if (!ClearCodecException(env, "getEncoderInfo") && info != nullptr) {
			encoderInfo.requested_resolution_alignment = std::max(1,
				static_cast<int>(env->GetIntField(info, javaClass->infoRequestedResolutionAlignment)));
			encoderInfo.apply_alignment_to_all_simulcast_layers =
				env->GetBooleanField(info, javaClass->infoApplyAlignmentToAllSimulcastLayers) == JNI_TRUE;
		}
	}

	webrtc::VideoEncoder::ScalingSettings VideoEncoderWrapper::GetScalingSettings(JNIEnv * env) const
	{
		jobject settings = env->CallObjectMethod(encoder, javaClass->getScalingSettings);

		if (ClearCodecException(env, "getScalingSettings") || settings == nullptr) {
			return ScalingSettings::kOff;
		}
		if (env->GetBooleanField(settings, javaClass->scalingOn) != JNI_TRUE) {
			return ScalingSettings::kOff;
		}

		std::optional<int> low;
		std::optional<int> high;

		try {
			low = OptionalInt(env, env->GetObjectField(settings, javaClass->scalingLow));
			high = OptionalInt(env, env->GetObjectField(settings, javaClass->scalingHigh));
		}
		catch (...) {
			ClearCodecException(env, "getScalingSettings");
		}

		if (low && high) {
			return ScalingSettings(*low, *high);
		}

		switch (codecSettings.codecType) {
			case webrtc::kVideoCodecVP8:
				return ScalingSettings(low.value_or(kLowVp8QpThreshold), high.value_or(kHighVp8QpThreshold));

			case webrtc::kVideoCodecVP9:
				return ScalingSettings(low.value_or(kLowVp9QpThreshold), high.value_or(kHighVp9QpThreshold));

			case webrtc::kVideoCodecH264:
				return ScalingSettings(low.value_or(kLowH264QpThreshold), high.value_or(kHighH264QpThreshold));

			default:
				// Without a QP to go by, quality scaling cannot work.
				return ScalingSettings::kOff;
		}
	}

	std::vector<webrtc::VideoEncoder::ResolutionBitrateLimits> VideoEncoderWrapper::GetResolutionBitrateLimits(
		JNIEnv * env) const
	{
		std::vector<ResolutionBitrateLimits> limits;

		jobjectArray array = static_cast<jobjectArray>(
			env->CallObjectMethod(encoder, javaClass->getResolutionBitrateLimits));

		if (ClearCodecException(env, "getResolutionBitrateLimits") || array == nullptr) {
			return limits;
		}

		const jsize count = env->GetArrayLength(array);

		for (jsize i = 0; i < count; i++) {
			jobject entry = env->GetObjectArrayElement(array, i);

			if (entry == nullptr) {
				continue;
			}

			limits.emplace_back(
				env->GetIntField(entry, javaClass->limitsFrameSizePixels),
				env->GetIntField(entry, javaClass->limitsMinStartBitrateBps),
				env->GetIntField(entry, javaClass->limitsMinBitrateBps),
				env->GetIntField(entry, javaClass->limitsMaxBitrateBps));

			env->DeleteLocalRef(entry);
		}

		return limits;
	}

	int VideoEncoderWrapper::ParseQp(const webrtc::EncodedImage & image)
	{
		int qp = -1;
		bool success = false;

		switch (codecSettings.codecType) {
			case webrtc::kVideoCodecVP8:
				success = webrtc::vp8::GetQp(image.data(), image.size(), &qp);
				break;

			case webrtc::kVideoCodecVP9:
				success = webrtc::vp9::GetQp(image.data(), image.size(), &qp);
				break;

			case webrtc::kVideoCodecH264:
				h264BitstreamParser.ParseBitstream(image);
				qp = h264BitstreamParser.GetLastSliceQp().value_or(-1);
				success = qp >= 0;
				break;

			default:
				break;
		}

		return success ? qp : -1;
	}

	webrtc::CodecSpecificInfo VideoEncoderWrapper::ParseCodecSpecificInfo(const webrtc::EncodedImage & image)
	{
		const bool keyFrame = image.frame_type() == webrtc::VideoFrameType::kVideoFrameKey;

		webrtc::CodecSpecificInfo info;
		info.codecType = codecSettings.codecType;

		// The encoder produces a single layer, so the frame dependencies are
		// those of a stream without layers.
		auto layerFrames = svcController.NextFrameConfig(/*restart=*/keyFrame);
		info.generic_frame_info = svcController.OnEncodeDone(layerFrames[0]);

		if (keyFrame) {
			info.template_structure = svcController.DependencyStructure();
			info.template_structure->resolutions = {
				webrtc::RenderResolution(image._encodedWidth, image._encodedHeight)
			};
		}

		switch (codecSettings.codecType) {
			case webrtc::kVideoCodecVP8:
				info.codecSpecific.VP8.nonReference = false;
				info.codecSpecific.VP8.temporalIdx = webrtc::kNoTemporalIdx;
				info.codecSpecific.VP8.layerSync = false;
				info.codecSpecific.VP8.keyIdx = webrtc::kNoKeyIdx;
				break;

			case webrtc::kVideoCodecVP9:
				if (keyFrame) {
					gofIndex = 0;
				}

				info.codecSpecific.VP9.inter_pic_predicted = !keyFrame;
				info.codecSpecific.VP9.flexible_mode = false;
				info.codecSpecific.VP9.ss_data_available = keyFrame;
				info.codecSpecific.VP9.temporal_idx = webrtc::kNoTemporalIdx;
				info.codecSpecific.VP9.temporal_up_switch = true;
				info.codecSpecific.VP9.inter_layer_predicted = false;
				info.codecSpecific.VP9.gof_idx = static_cast<uint8_t>(gofIndex++ % gof.num_frames_in_gof);
				info.codecSpecific.VP9.num_spatial_layers = 1;
				info.codecSpecific.VP9.first_frame_in_picture = true;
				info.codecSpecific.VP9.spatial_layer_resolution_present = keyFrame;

				if (keyFrame) {
					info.codecSpecific.VP9.width[0] = image._encodedWidth;
					info.codecSpecific.VP9.height[0] = image._encodedHeight;
					info.codecSpecific.VP9.gof.CopyGofInfoVP9(gof);
				}
				break;

			case webrtc::kVideoCodecH264: {
				// Mode 1 may split a NAL unit across packets; mode 0 has to
				// send each NAL unit in a packet of its own.
				auto mode = format.parameters.find("packetization-mode");
				const bool nonInterleaved = mode != format.parameters.end() && mode->second == "1";

				info.codecSpecific.H264.packetization_mode = nonInterleaved
					? webrtc::H264PacketizationMode::NonInterleaved
					: webrtc::H264PacketizationMode::SingleNalUnit;
				info.codecSpecific.H264.temporal_idx = webrtc::kNoTemporalIdx;
				info.codecSpecific.H264.base_layer_sync = false;
				info.codecSpecific.H264.idr_frame = keyFrame;
				break;
			}

			default:
				break;
		}

		return info;
	}

	VideoEncoderWrapper::JavaVideoEncoderClass::JavaVideoEncoderClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG_CODEC"VideoEncoder");

		initEncode = GetMethod(env, cls, "initEncode",
			"(L" PKG_CODEC "VideoEncoder$Settings;L" PKG_CODEC "VideoEncoder$Callback;)L" PKG_CODEC "VideoCodecStatus;");
		release = GetMethod(env, cls, "release", "()L" PKG_CODEC "VideoCodecStatus;");
		encode = GetMethod(env, cls, "encode",
			"(L" PKG_VIDEO "VideoFrame;L" PKG_CODEC "VideoEncoder$EncodeInfo;)L" PKG_CODEC "VideoCodecStatus;");
		setRates = GetMethod(env, cls, "setRates",
			"(L" PKG_CODEC "VideoEncoder$RateControlParameters;)L" PKG_CODEC "VideoCodecStatus;");
		getScalingSettings = GetMethod(env, cls, "getScalingSettings", "()L" PKG_CODEC "VideoEncoder$ScalingSettings;");
		getResolutionBitrateLimits = GetMethod(env, cls, "getResolutionBitrateLimits",
			"()[L" PKG_CODEC "VideoEncoder$ResolutionBitrateLimits;");
		getEncoderInfo = GetMethod(env, cls, "getEncoderInfo", "()L" PKG_CODEC "VideoEncoder$EncoderInfo;");
		getImplementationName = GetMethod(env, cls, "getImplementationName", "()" STRING_SIG);
		isHardwareEncoder = GetMethod(env, cls, "isHardwareEncoder", "()Z");

		settingsClass = FindClass(env, PKG_CODEC"VideoEncoder$Settings");
		settingsCtor = GetMethod(env, settingsClass, "<init>", "(IIIIIIIIZZZ)V");

		encodeInfoClass = FindClass(env, PKG_CODEC"VideoEncoder$EncodeInfo");
		encodeInfoFromNative = GetStaticMethod(env, encodeInfoClass, "fromNative",
			"([I)L" PKG_CODEC "VideoEncoder$EncodeInfo;");

		bitrateAllocationClass = FindClass(env, PKG_CODEC"VideoEncoder$BitrateAllocation");
		bitrateAllocationCtor = GetMethod(env, bitrateAllocationClass, "<init>", "([[I)V");

		rateControlClass = FindClass(env, PKG_CODEC"VideoEncoder$RateControlParameters");
		rateControlCtor = GetMethod(env, rateControlClass, "<init>",
			"(L" PKG_CODEC "VideoEncoder$BitrateAllocation;D)V");

		jclass scalingClass = FindClass(env, PKG_CODEC"VideoEncoder$ScalingSettings");
		scalingOn = GetFieldID(env, scalingClass, "on", "Z");
		scalingLow = GetFieldID(env, scalingClass, "low", INTEGER_SIG);
		scalingHigh = GetFieldID(env, scalingClass, "high", INTEGER_SIG);

		jclass limitsClass = FindClass(env, PKG_CODEC"VideoEncoder$ResolutionBitrateLimits");
		limitsFrameSizePixels = GetFieldID(env, limitsClass, "frameSizePixels", "I");
		limitsMinStartBitrateBps = GetFieldID(env, limitsClass, "minStartBitrateBps", "I");
		limitsMinBitrateBps = GetFieldID(env, limitsClass, "minBitrateBps", "I");
		limitsMaxBitrateBps = GetFieldID(env, limitsClass, "maxBitrateBps", "I");

		jclass infoClass = FindClass(env, PKG_CODEC"VideoEncoder$EncoderInfo");
		infoRequestedResolutionAlignment = GetFieldID(env, infoClass, "requestedResolutionAlignment", "I");
		infoApplyAlignmentToAllSimulcastLayers = GetFieldID(env, infoClass, "applyAlignmentToAllSimulcastLayers", "Z");

		callbackClass = FindClass(env, PKG_CODEC"NativeVideoEncoderCallback");
		callbackCtor = GetMethod(env, callbackClass, "<init>", "(J)V");
		callbackInvalidate = GetMethod(env, callbackClass, "invalidate", "()V");

		// The class loader FindClass goes through does not resolve array
		// descriptors, and the JNI lookup of a primitive array class needs
		// no class loader.
		jclass intArray = env->FindClass("[I");
		intArrayClass = static_cast<jclass>(env->NewGlobalRef(intArray));
		env->DeleteLocalRef(intArray);
	}
}
