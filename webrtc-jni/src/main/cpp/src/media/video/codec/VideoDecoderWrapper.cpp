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

#include "media/video/codec/VideoDecoderWrapper.h"
#include "media/video/codec/EncodedImage.h"
#include "media/video/codec/JavaLocalFrame.h"
#include "media/video/codec/VideoCodecUtils.h"
#include "api/VideoFrame.h"
#include "Exception.h"
#include "JavaClasses.h"
#include "JavaString.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

#include "api/video/render_resolution.h"
#include "api/video/video_frame.h"
#include "modules/video_coding/include/video_error_codes.h"
#include "modules/video_coding/utility/vp8_header_parser.h"
#include "modules/video_coding/utility/vp9_uncompressed_header_parser.h"
#include "rtc_base/logging.h"
#include "rtc_base/time_utils.h"

namespace jni
{
	namespace
	{
		// Enough for the objects of one call into the decoder.
		constexpr jint kLocalFrameCapacity = 32;

		// How many frames the decoder may hold back before the oldest records
		// are given up, so that one that never produces output does not make
		// them grow without bound. Several seconds of video.
		constexpr size_t kMaxPendingFrames = 300;

		// RTP video timestamps count at 90 kHz.
		constexpr int64_t kRtpTicksPerMillisecond = 90;
	}

	VideoDecoderWrapper::VideoDecoderWrapper(JNIEnv * env, jobject decoder) :
		decoder(env, decoder),
		javaClass(JavaClasses::get<JavaVideoDecoderClass>(env)),
		hardwareAccelerated(false),
		callback(nullptr),
		initialized(false),
		qpParsingEnabled(true)
	{
		JavaLocalFrame localFrame(env, kLocalFrameCapacity);

		jobject name = env->CallObjectMethod(decoder, javaClass->getImplementationName);

		if (!ClearCodecException(env, "getImplementationName") && name != nullptr) {
			try {
				implementationName = JavaString::toNative(env,
					JavaLocalRef<jstring>(env, static_cast<jstring>(env->NewLocalRef(name))));
			}
			catch (...) {
				ClearCodecException(env, "getImplementationName");
			}
		}

		jboolean hardware = env->CallBooleanMethod(decoder, javaClass->isHardwareDecoder);

		if (!ClearCodecException(env, "isHardwareDecoder")) {
			hardwareAccelerated = hardware == JNI_TRUE;
		}
	}

	VideoDecoderWrapper::~VideoDecoderWrapper()
	{
		JNIEnv * env = AttachCurrentThread();

		if (env != nullptr) {
			InvalidateCallback(env);
		}
	}

	bool VideoDecoderWrapper::Configure(const Settings & decoderSettings)
	{
		JNIEnv * env = AttachCurrentThread();

		if (env == nullptr) {
			return false;
		}

		settings = decoderSettings;

		return ConfigureInternal(env);
	}

	bool VideoDecoderWrapper::ConfigureInternal(JNIEnv * env)
	{
		JavaLocalFrame localFrame(env, kLocalFrameCapacity);

		webrtc::RenderResolution resolution = settings.max_render_resolution();

		jobject jSettings = env->NewObject(javaClass->settingsClass, javaClass->settingsCtor,
			static_cast<jint>(settings.number_of_cores()),
			static_cast<jint>(resolution.Valid() ? resolution.Width() : 0),
			static_cast<jint>(resolution.Valid() ? resolution.Height() : 0));

		if (ClearCodecException(env, "initDecode") || jSettings == nullptr) {
			return false;
		}

		// A callback of its own for each initialization, so that frames of an
		// earlier one cannot reach this one.
		InvalidateCallback(env);

		jobject jCallback = env->NewObject(javaClass->callbackClass, javaClass->callbackCtor,
			reinterpret_cast<jlong>(this));

		if (ClearCodecException(env, "initDecode") || jCallback == nullptr) {
			return false;
		}

		javaCallback = std::make_unique<JavaGlobalRef<jobject>>(env, jCallback);

		jobject status = env->CallObjectMethod(decoder, javaClass->initDecode, jSettings, jCallback);
		int32_t result = ToNativeCodecStatus(env, status, "initDecode");

		RTC_LOG(LS_INFO) << "Java decoder initDecode: " << result;

		initialized = result == WEBRTC_VIDEO_CODEC_OK;

		// A decoder that stopped providing the QP after a reset gets it
		// parsed again.
		qpParsingEnabled = true;

		return initialized;
	}

	int32_t VideoDecoderWrapper::Decode(const webrtc::EncodedImage & inputImage, int64_t renderTimeMs)
	{
		if (!initialized) {
			return WEBRTC_VIDEO_CODEC_UNINITIALIZED;
		}

		JNIEnv * env = AttachCurrentThread();

		if (env == nullptr) {
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		JavaLocalFrame localFrame(env, kLocalFrameCapacity);

		// The capture time identifies the frame, and received images carry
		// none, so it is derived from the RTP timestamp.
		webrtc::EncodedImage image(inputImage);
		image.capture_time_ms_ = image.RtpTimestamp() / kRtpTicksPerMillisecond;

		{
			webrtc::MutexLock lock(&frameExtraInfosLock);

			if (frameExtraInfos.size() >= kMaxPendingFrames) {
				frameExtraInfos.pop_front();
			}

			frameExtraInfos.push_back(FrameExtraInfo {
				image.capture_time_ms_ * webrtc::kNumNanosecsPerMillisec,
				image.RtpTimestamp(),
				image.ntp_time_ms_,
				qpParsingEnabled ? ParseQp(image) : std::nullopt
			});
		}

		jobject jImage = nullptr;

		try {
			jImage = EncodedImage::toJava(env, image).release();
		}
		catch (...) {
			ClearCodecException(env, "decode");
		}

		if (jImage == nullptr) {
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		jobject status = env->CallObjectMethod(decoder, javaClass->decode, jImage);
		int32_t result = ToNativeCodecStatus(env, status, "decode");

		return HandleReturnCode(env, result, "decode");
	}

	int32_t VideoDecoderWrapper::RegisterDecodeCompleteCallback(webrtc::DecodedImageCallback * decodeCallback)
	{
		callback = decodeCallback;

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t VideoDecoderWrapper::Release()
	{
		JNIEnv * env = AttachCurrentThread();

		if (env == nullptr) {
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		return ReleaseInternal(env);
	}

	int32_t VideoDecoderWrapper::ReleaseInternal(JNIEnv * env)
	{
		JavaLocalFrame localFrame(env, kLocalFrameCapacity);

		jobject status = env->CallObjectMethod(decoder, javaClass->release);
		int32_t result = ToNativeCodecStatus(env, status, "release");

		RTC_LOG(LS_INFO) << "Java decoder release: " << result;

		InvalidateCallback(env);

		{
			webrtc::MutexLock lock(&frameExtraInfosLock);
			frameExtraInfos.clear();
		}

		initialized = false;

		return result;
	}

	webrtc::VideoDecoder::DecoderInfo VideoDecoderWrapper::GetDecoderInfo() const
	{
		DecoderInfo info;
		info.implementation_name = implementationName;
		info.is_hardware_accelerated = hardwareAccelerated;

		return info;
	}

	const char * VideoDecoderWrapper::ImplementationName() const
	{
		return implementationName.c_str();
	}

	void VideoDecoderWrapper::OnDecodedFrame(JNIEnv * env, jobject jFrame, std::optional<int32_t> decodeTimeMs,
		std::optional<uint8_t> qp)
	{
		const auto frameClass = JavaClasses::get<JavaVideoFrameClass>(env);

		const int64_t timestampNs = env->GetLongField(jFrame, frameClass->timestampNs);
		const jint rotation = env->GetIntField(jFrame, frameClass->rotation);

		FrameExtraInfo extraInfo;

		{
			webrtc::MutexLock lock(&frameExtraInfosLock);

			// A decoder may drop frames, so the records of those it dropped
			// come before the one of this frame.
			do {
				if (frameExtraInfos.empty()) {
					RTC_LOG(LS_WARNING) << "Java decoder produced a frame for no image it was given, timestamp "
						<< timestampNs << " ns";
					return;
				}

				extraInfo = frameExtraInfos.front();
				frameExtraInfos.pop_front();
			}
			while (extraInfo.timestampNs != timestampNs);
		}

		JavaLocalRef<jobject> buffer(env, env->GetObjectField(jFrame, frameClass->buffer));
		webrtc::scoped_refptr<webrtc::VideoFrameBuffer> nativeBuffer = VideoFrame::toNativeBuffer(env, buffer);

		if (nativeBuffer == nullptr) {
			throw Exception("The decoded frame has no pixels that can be read: its buffer has to provide "
				"direct byte buffers");
		}

		webrtc::VideoFrame frame = webrtc::VideoFrame::Builder()
			.set_video_frame_buffer(nativeBuffer)
			.set_rtp_timestamp(extraInfo.rtpTimestamp)
			.set_timestamp_ms(timestampNs / webrtc::kNumNanosecsPerMillisec)
			.set_ntp_time_ms(extraInfo.ntpTimeMs)
			.set_rotation(static_cast<webrtc::VideoRotation>(rotation))
			.build();

		// A decoder that provides the QP itself saves parsing the bitstream.
		qpParsingEnabled = !qp.has_value();

		webrtc::DecodedImageCallback * decodeCallback = callback.load();

		if (decodeCallback != nullptr) {
			decodeCallback->Decoded(frame, decodeTimeMs, qp.has_value() ? qp : extraInfo.qp);
		}
	}

	int32_t VideoDecoderWrapper::HandleReturnCode(JNIEnv * env, int32_t status, const char * method)
	{
		if (status >= 0) {
			// OK or NO_OUTPUT.
			return status;
		}

		RTC_LOG(LS_WARNING) << "Java decoder " << method << ": " << status;

		if (status == WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE || status == WEBRTC_VIDEO_CODEC_UNINITIALIZED) {
			return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
		}

		// Any other error may be one the decoder recovers from.
		if (ReleaseInternal(env) == WEBRTC_VIDEO_CODEC_OK && ConfigureInternal(env)) {
			RTC_LOG(LS_WARNING) << "Java decoder reset";

			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		RTC_LOG(LS_WARNING) << "Java decoder could not be reset";

		return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
	}

	void VideoDecoderWrapper::InvalidateCallback(JNIEnv * env)
	{
		if (javaCallback == nullptr) {
			return;
		}

		// Waits for a frame that is being handed over right now.
		env->CallVoidMethod(javaCallback->get(), javaClass->callbackInvalidate);
		ClearCodecException(env, "invalidate");

		javaCallback.reset();
	}

	std::optional<uint8_t> VideoDecoderWrapper::ParseQp(const webrtc::EncodedImage & image)
	{
		if (image.qp_ != -1) {
			return static_cast<uint8_t>(image.qp_);
		}

		int qp;

		switch (settings.codec_type()) {
			case webrtc::kVideoCodecVP8:
				if (webrtc::vp8::GetQp(image.data(), image.size(), &qp)) {
					return static_cast<uint8_t>(qp);
				}
				break;

			case webrtc::kVideoCodecVP9:
				if (webrtc::vp9::GetQp(image.data(), image.size(), &qp)) {
					return static_cast<uint8_t>(qp);
				}
				break;

			case webrtc::kVideoCodecH264: {
				h264BitstreamParser.ParseBitstream(image);

				std::optional<int> sliceQp = h264BitstreamParser.GetLastSliceQp();

				if (sliceQp.has_value()) {
					return static_cast<uint8_t>(*sliceQp);
				}
				break;
			}

			default:
				break;
		}

		return std::nullopt;
	}

	VideoDecoderWrapper::JavaVideoDecoderClass::JavaVideoDecoderClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG_CODEC"VideoDecoder");

		initDecode = GetMethod(env, cls, "initDecode",
			"(L" PKG_CODEC "VideoDecoder$Settings;L" PKG_CODEC "VideoDecoder$Callback;)L" PKG_CODEC "VideoCodecStatus;");
		release = GetMethod(env, cls, "release", "()L" PKG_CODEC "VideoCodecStatus;");
		decode = GetMethod(env, cls, "decode",
			"(L" PKG_CODEC "EncodedImage;)L" PKG_CODEC "VideoCodecStatus;");
		getImplementationName = GetMethod(env, cls, "getImplementationName", "()" STRING_SIG);
		isHardwareDecoder = GetMethod(env, cls, "isHardwareDecoder", "()Z");

		settingsClass = FindClass(env, PKG_CODEC"VideoDecoder$Settings");
		settingsCtor = GetMethod(env, settingsClass, "<init>", "(III)V");

		callbackClass = FindClass(env, PKG_CODEC"NativeVideoDecoderCallback");
		callbackCtor = GetMethod(env, callbackClass, "<init>", "(J)V");
		callbackInvalidate = GetMethod(env, callbackClass, "invalidate", "()V");
	}
}
