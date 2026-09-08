#ifndef JNI_WEBRTC_API_RTC_DATA_CHANNEL_SEND_OBSERVER_H_
#define JNI_WEBRTC_API_RTC_DATA_CHANNEL_SEND_OBSERVER_H_

#include "JavaClass.h"
#include "JavaRef.h"
#include "api/rtc_error.h"

#include <memory>

namespace jni
{
	class RTCDataChannelSendObserver
	{
		public:
			RTCDataChannelSendObserver(JNIEnv * env, jobject observer);
			~RTCDataChannelSendObserver();

			RTCDataChannelSendObserver(const RTCDataChannelSendObserver &) = delete;
			RTCDataChannelSendObserver & operator=(const RTCDataChannelSendObserver &) = delete;

			void OnComplete(webrtc::RTCError error) noexcept;
			void Cancel();

		private:
			class JavaSendObserverClass : public JavaClass
			{
				public:
					explicit JavaSendObserverClass(JNIEnv * env);
					jmethodID onSuccess;
					jmethodID onFailure;
			};

			void Notify(const char * error) noexcept;

			JavaGlobalRef<jobject> observer;
			const std::shared_ptr<JavaSendObserverClass> javaClass;
	};
}

#endif
