/*
 * Copyright 2019 Alex Andres
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

#include "JNI_MediaDevices.h"
#include "JavaBoundary.h"
#include "JavaArrayList.h"
#include "JavaContext.h"
#include "JavaError.h"
#include "JavaUtils.h"
#include "Exception.h"
#include "media/AudioBackendUnavailableException.h"
#include <new>
#include "media/DeviceChangeListener.h"
#include "media/audio/AudioDevice.h"
#include "media/audio/AudioDeviceManager.h"
#include "media/video/VideoDevice.h"
#include "media/video/VideoDeviceManager.h"
#include "media/video/VideoCaptureCapability.h"
#include "WebRTCContext.h"

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_MediaDevices_addDeviceChangeListener
(JNIEnv * env, jclass caller, jobject jListener)
{
	jni::CallFromJava(env, [&]() {
		jni::WebRTCContext * context = static_cast<jni::WebRTCContext *>(javaContext);

		try {
			auto listener = new jni::DeviceChangeListener(env, jni::JavaGlobalRef<jobject>(env, jListener));
			auto listenerPtr = std::shared_ptr<jni::DeviceChangeListener>(listener);

			context->getAudioDeviceManager()->attachHotplugListener(listenerPtr);
			context->getVideoDeviceManager()->attachHotplugListener(listenerPtr);

			javaContext->addNativeRef(env, jni::JavaLocalRef<jobject>(env, jListener), listenerPtr);
		}
		catch (const jni::AudioBackendUnavailableException & e) {
			ThrowJavaException(env, "dev/onvoid/webrtc/media/AudioBackendUnavailableException", e.what());
		}
		catch (const std::bad_alloc &) {
			ThrowJavaException(env, "java/lang/OutOfMemoryError", "Allocating native device resources failed");
		}
		catch (const jni::Exception & e) {
			ThrowJavaException(env, "dev/onvoid/webrtc/media/DeviceException", e.what());
		}
		catch (...) {
			ThrowCxxJavaException(env);
		}
	});
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_MediaDevices_removeDeviceChangeListener
(JNIEnv * env, jclass caller, jobject jListener)
{
	jni::CallFromJava(env, [&]() {
		jni::WebRTCContext * context = static_cast<jni::WebRTCContext *>(javaContext);
		auto listener = javaContext->removeNativeRef<jni::DeviceChangeListener>(env, jni::JavaLocalRef<jobject>(env, jListener));

		if (listener) {
			listener->detach();
		}

		try {
			context->getAudioDeviceManager()->detachHotplugListener(listener);
			context->getVideoDeviceManager()->detachHotplugListener(listener);
		}
		catch (const jni::AudioBackendUnavailableException & e) {
			ThrowJavaException(env, "dev/onvoid/webrtc/media/AudioBackendUnavailableException", e.what());
		}
		catch (const std::bad_alloc &) {
			ThrowJavaException(env, "java/lang/OutOfMemoryError", "Allocating native device resources failed");
		}
		catch (const jni::Exception & e) {
			ThrowJavaException(env, "dev/onvoid/webrtc/media/DeviceException", e.what());
		}
		catch (...) {
			ThrowCxxJavaException(env);
		}
	});
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_media_MediaDevices_getDefaultAudioRenderDevice
(JNIEnv * env, jclass caller)
{
	return jni::CallFromJava(env, [&]() -> jobject {
		jni::WebRTCContext * context = static_cast<jni::WebRTCContext *>(javaContext);

		try {
		    auto device = context->getAudioDeviceManager()->getDefaultAudioPlaybackDevice();

			if (device == nullptr) {
				return nullptr;
			}

			return jni::AudioDevice::toJavaAudioDevice(env, device).release();
		}
		catch (const jni::AudioBackendUnavailableException & e) {
			ThrowJavaException(env, "dev/onvoid/webrtc/media/AudioBackendUnavailableException", e.what());
		}
		catch (const std::bad_alloc &) {
			ThrowJavaException(env, "java/lang/OutOfMemoryError", "Allocating native device resources failed");
		}
		catch (const jni::Exception & e) {
			ThrowJavaException(env, "dev/onvoid/webrtc/media/DeviceException", e.what());
		}
		catch (...) {
			ThrowCxxJavaException(env);
		}

		return nullptr;
	});
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_media_MediaDevices_getDefaultAudioCaptureDevice
(JNIEnv * env, jclass caller)
{
	return jni::CallFromJava(env, [&]() -> jobject {
		jni::WebRTCContext * context = static_cast<jni::WebRTCContext *>(javaContext);

		try {
		    auto device = context->getAudioDeviceManager()->getDefaultAudioCaptureDevice();

			if (device == nullptr) {
				return nullptr;
			}

			return jni::AudioDevice::toJavaAudioDevice(env, device).release();
		}
		catch (const jni::AudioBackendUnavailableException & e) {
			ThrowJavaException(env, "dev/onvoid/webrtc/media/AudioBackendUnavailableException", e.what());
		}
		catch (const std::bad_alloc &) {
			ThrowJavaException(env, "java/lang/OutOfMemoryError", "Allocating native device resources failed");
		}
		catch (const jni::Exception & e) {
			ThrowJavaException(env, "dev/onvoid/webrtc/media/DeviceException", e.what());
		}
		catch (...) {
			ThrowCxxJavaException(env);
		}

		return nullptr;
	});
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_media_MediaDevices_getAudioRenderDevices
(JNIEnv * env, jclass caller)
{
	return jni::CallFromJava(env, [&]() -> jobject {
		try {
			jni::WebRTCContext * context = static_cast<jni::WebRTCContext *>(javaContext);

			auto devices = context->getAudioDeviceManager()->getAudioPlaybackDevices();

			jni::JavaArrayList deviceList(env, devices.size());

			for (const auto & device : devices) {
				deviceList.add(jni::AudioDevice::toJavaAudioDevice(env, device));
			}

			return deviceList.listObject().release();
		}
		catch (const jni::AudioBackendUnavailableException & e) {
			ThrowJavaException(env, "dev/onvoid/webrtc/media/AudioBackendUnavailableException", e.what());
		}
		catch (const std::bad_alloc &) {
			ThrowJavaException(env, "java/lang/OutOfMemoryError", "Allocating native device resources failed");
		}
		catch (const jni::Exception & e) {
			ThrowJavaException(env, "dev/onvoid/webrtc/media/DeviceException", e.what());
		}
		catch (...) {
			ThrowCxxJavaException(env);
		}

		return nullptr;
	});
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_media_MediaDevices_getAudioCaptureDevices
(JNIEnv * env, jclass caller)
{
	return jni::CallFromJava(env, [&]() -> jobject {
		try {
			jni::WebRTCContext * context = static_cast<jni::WebRTCContext *>(javaContext);

			auto devices = context->getAudioDeviceManager()->getAudioCaptureDevices();

			jni::JavaArrayList deviceList(env, devices.size());

			for (const auto & device : devices) {
				deviceList.add(jni::AudioDevice::toJavaAudioDevice(env, device));
			}

			return deviceList.listObject().release();
		}
		catch (const jni::AudioBackendUnavailableException & e) {
			ThrowJavaException(env, "dev/onvoid/webrtc/media/AudioBackendUnavailableException", e.what());
		}
		catch (const std::bad_alloc &) {
			ThrowJavaException(env, "java/lang/OutOfMemoryError", "Allocating native device resources failed");
		}
		catch (const jni::Exception & e) {
			ThrowJavaException(env, "dev/onvoid/webrtc/media/DeviceException", e.what());
		}
		catch (...) {
			ThrowCxxJavaException(env);
		}

		return nullptr;
	});
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_media_MediaDevices_getVideoCaptureDevices
(JNIEnv * env, jclass caller)
{
	return jni::CallFromJava(env, [&]() -> jobject {
		try {
			jni::WebRTCContext * context = static_cast<jni::WebRTCContext *>(javaContext);

			auto devices = context->getVideoDeviceManager()->getVideoCaptureDevices();

			jni::JavaArrayList deviceList(env, devices.size());

			for (const auto & device : devices) {
				deviceList.add(jni::VideoDevice::toJavaVideoDevice(env, *device.get()));
			}

			return deviceList.listObject().release();
		}
		catch (const jni::AudioBackendUnavailableException & e) {
			ThrowJavaException(env, "dev/onvoid/webrtc/media/AudioBackendUnavailableException", e.what());
		}
		catch (const std::bad_alloc &) {
			ThrowJavaException(env, "java/lang/OutOfMemoryError", "Allocating native device resources failed");
		}
		catch (const jni::Exception & e) {
			ThrowJavaException(env, "dev/onvoid/webrtc/media/DeviceException", e.what());
		}
		catch (...) {
			ThrowCxxJavaException(env);
		}

		return nullptr;
	});
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_media_MediaDevices_getVideoCaptureCapabilities
(JNIEnv * env, jclass caller, jobject device)
{
	return jni::CallFromJava(env, [&]() -> jobject {
		if (!device) {
			env->Throw(jni::JavaNullPointerException(env, "VideoDevice is null"));
			return nullptr;
		}

		try {
			jni::WebRTCContext * context = static_cast<jni::WebRTCContext *>(javaContext);

			auto dev = jni::VideoDevice::toNativeVideoDevice(env, jni::JavaLocalRef<jobject>(env, device));
			auto capabilities = context->getVideoDeviceManager()->getVideoCaptureCapabilities(dev);

			jni::JavaArrayList capabilityList(env, capabilities.size());

			for (const auto & capability : capabilities) {
				capabilityList.add(jni::VideoCaptureCapability::toJava(env, capability));
			}

			return capabilityList.listObject().release();
		}
		catch (const jni::AudioBackendUnavailableException & e) {
			ThrowJavaException(env, "dev/onvoid/webrtc/media/AudioBackendUnavailableException", e.what());
		}
		catch (const std::bad_alloc &) {
			ThrowJavaException(env, "java/lang/OutOfMemoryError", "Allocating native device resources failed");
		}
		catch (const jni::Exception & e) {
			ThrowJavaException(env, "dev/onvoid/webrtc/media/DeviceException", e.what());
		}
		catch (...) {
			ThrowCxxJavaException(env);
		}

		return nullptr;
	});
}