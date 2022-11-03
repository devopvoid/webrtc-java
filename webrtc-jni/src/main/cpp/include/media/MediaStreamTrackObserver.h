/*
 * Copyright 2022 Alex Andres
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

#ifndef JNI_WEBRTC_MEDIA_STREAM_TRACK_LISTENER_H_
#define JNI_WEBRTC_MEDIA_STREAM_TRACK_LISTENER_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include "api/media_stream_interface.h"

#include <jni.h>
#include <memory>

namespace jni
{
	class MediaStreamTrackObserver : public webrtc::ObserverInterface
	{
		public:
			explicit MediaStreamTrackObserver(JNIEnv * env, const JavaGlobalRef<jobject> & javaTrack, const webrtc::MediaStreamTrackInterface * track);
			~MediaStreamTrackObserver() = default;

			// ObserverInterface implementation.
			void OnChanged() override;

		private:
			class JavaMediaStreamTrackClass : public JavaClass
			{
				public:
					explicit JavaMediaStreamTrackClass(JNIEnv * env);

					jmethodID onEnded;
					jmethodID onMuteState;
			};

		private:
			const webrtc::MediaStreamTrackInterface * track;

			const JavaGlobalRef<jobject> javaTrack;

			const std::shared_ptr<JavaMediaStreamTrackClass> javaClass;

			webrtc::MediaStreamTrackInterface::TrackState trackState;
			bool trackEnabled;
	};
}

#endif