#include "api/RTCDataChannelSendObserver.h"
#include "api/WebRTCUtils.h"
#include "JavaString.h"
#include "JNI_WebRTC.h"

namespace
{
	// Hands an exception thrown by the observer to the calling thread's uncaught
	// exception handler, as the JVM does for an exception escaping a Java thread.
	// ExceptionDescribe would print the "Exception in thread" prefix straight to
	// native stderr and the stack trace to System.err, splitting the report
	// across two streams and leaving the prefix on an unterminated line.
	void ReportUncaughtException(JNIEnv * env, jthrowable exception)
	{
		jni::JavaLocalRef<jclass> threadClass(env, env->FindClass("java/lang/Thread"));
		jni::JavaLocalRef<jclass> handlerClass(env, env->FindClass("java/lang/Thread$UncaughtExceptionHandler"));
		if (env->ExceptionCheck()) {
			env->ExceptionClear();
			return;
		}

		jmethodID currentThread = env->GetStaticMethodID(threadClass.get(), "currentThread", "()Ljava/lang/Thread;");
		jmethodID getHandler = env->GetMethodID(threadClass.get(), "getUncaughtExceptionHandler",
			"()Ljava/lang/Thread$UncaughtExceptionHandler;");
		jmethodID uncaughtException = env->GetMethodID(handlerClass.get(), "uncaughtException",
			"(Ljava/lang/Thread;Ljava/lang/Throwable;)V");
		if (env->ExceptionCheck()) {
			env->ExceptionClear();
			return;
		}

		jni::JavaLocalRef<jobject> thread(env, env->CallStaticObjectMethod(threadClass.get(), currentThread));
		jni::JavaLocalRef<jobject> handler(env, env->CallObjectMethod(thread.get(), getHandler));
		if (!env->ExceptionCheck() && handler.get()) {
			env->CallVoidMethod(handler.get(), uncaughtException, thread.get(), exception);
		}
		// Like the JVM, ignore an exception thrown by the handler itself.
		if (env->ExceptionCheck()) {
			env->ExceptionClear();
		}
	}
}

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
			JavaLocalRef<jthrowable> exception(env, env->ExceptionOccurred());
			env->ExceptionClear();
			ReportUncaughtException(env, exception.get());
		}
	}

	RTCDataChannelSendObserver::JavaSendObserverClass::JavaSendObserverClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG"RTCDataChannelSendObserver");
		onSuccess = GetMethod(env, cls, "onSuccess", "()V");
		onFailure = GetMethod(env, cls, "onFailure", "(" STRING_SIG ")V");
	}
}
