#include "api/RTCDataChannelSendObserver.h"
#include "api/JavaCallbackThread.h"
#include "api/WebRTCUtils.h"
#include "JavaString.h"
#include "JNI_WebRTC.h"

#include "rtc_base/logging.h"

#include <optional>
#include <string>

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
		try {
			std::optional<std::string> message;
			if (error != nullptr) {
				message = error;
			}
			// WebRTC completes sends on its network thread, where the Java observer must not run.
			JavaCallbackThread::Post([callback = std::move(callback), javaClass = javaClass,
					message = std::move(message)](JNIEnv * env) {
				if (!message) {
					env->CallVoidMethod(callback.get(), javaClass->onSuccess);
				}
				else {
					JavaLocalRef<jstring> jMessage(env, env->NewStringUTF(message->c_str()));
					if (!env->ExceptionCheck()) {
						env->CallVoidMethod(callback.get(), javaClass->onFailure, jMessage.get());
					}
				}
				// A Java exception must not escape into the next callback.
				ReportPendingException(env);
			});
		}
		catch (...) {
			RTC_LOG(LS_ERROR) << "Could not report a data channel send result";
		}
	}

	RTCDataChannelSendObserver::JavaSendObserverClass::JavaSendObserverClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG"RTCDataChannelSendObserver");
		onSuccess = GetMethod(env, cls, "onSuccess", "()V");
		onFailure = GetMethod(env, cls, "onFailure", "(" STRING_SIG ")V");
	}
}
