#include "api/RTCDataChannelSendObserver.h"
#include "api/WebRTCUtils.h"
#include "JavaString.h"
#include "JNI_WebRTC.h"

namespace jni
{
	RTCDataChannelSendObserver::RTCDataChannelSendObserver(JNIEnv * env, jobject observer) :
		observer(env, observer),
		javaClass(JavaClasses::get<JavaSendObserverClass>(env))
	{
	}

	RTCDataChannelSendObserver::~RTCDataChannelSendObserver()
	{
		// WebRTC can destroy the completion without calling it after losing its transport.
		Notify("[INVALID_STATE] Send operation was discarded before completion");
	}

	void RTCDataChannelSendObserver::Cancel()
	{
		observer = JavaGlobalRef<jobject>(nullptr);
	}

	void RTCDataChannelSendObserver::OnComplete(webrtc::RTCError error) noexcept
	{
		try {
			if (error.ok()) {
				Notify(nullptr);
			}
			else {
				Notify(RTCErrorToString(error).c_str());
			}
		}
		catch (...) {
			Notify("[INTERNAL_ERROR] Could not report native send result");
		}
	}

	void RTCDataChannelSendObserver::Notify(const char * error) noexcept
	{
		JavaGlobalRef<jobject> callback(std::move(observer));
		if (!callback.get()) {
			return;
		}
		JNIEnv * env = AttachCurrentThread();
		if (env == nullptr) {
			return;
		}
		if (error == nullptr) {
			env->CallVoidMethod(callback.get(), javaClass->onSuccess);
		}
		else {
			JavaLocalRef<jstring> message(env, env->NewStringUTF(error));
			if (!env->ExceptionCheck()) {
				env->CallVoidMethod(callback.get(), javaClass->onFailure, message.get());
			}
		}
		// A Java exception must not escape into WebRTC's network task or a destructor.
		if (env->ExceptionCheck()) {
			env->ExceptionDescribe();
			env->ExceptionClear();
		}
	}

	RTCDataChannelSendObserver::JavaSendObserverClass::JavaSendObserverClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG"RTCDataChannelSendObserver");
		onSuccess = GetMethod(env, cls, "onSuccess", "()V");
		onFailure = GetMethod(env, cls, "onFailure", "(" STRING_SIG ")V");
	}
}
