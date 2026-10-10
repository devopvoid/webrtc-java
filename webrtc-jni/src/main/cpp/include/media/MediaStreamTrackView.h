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

#ifndef JNI_WEBRTC_MEDIA_STREAM_TRACK_VIEW_H_
#define JNI_WEBRTC_MEDIA_STREAM_TRACK_VIEW_H_

#include "Exception.h"
#include "JavaClass.h"
#include "JavaFactories.h"
#include "JavaRef.h"

#include "api/media_stream_interface.h"
#include "api/scoped_refptr.h"

#include <jni.h>
#include <typeinfo>
#include <vector>

namespace jni
{
	// A Java MediaStreamTrack made for a track that something else owns (a
	// sender, a receiver, a stream, or the track a listener is called for)
	// holds no reference to it. It is marked as a view, so that disposing it
	// detaches the Java object without releasing a reference it never had.
	// Tracks the factory creates hand their reference to the Java object and
	// are not views.
	class MediaStreamTrackView
	{
		public:
			static JavaLocalRef<jobject> create(JNIEnv * env, const webrtc::MediaStreamTrackInterface * track);

			template <class T>
			static JavaLocalRef<jobjectArray> createArray(JNIEnv * env, const std::vector<webrtc::scoped_refptr<T>> & tracks)
			{
				jsize size = static_cast<jsize>(tracks.size());

				JavaLocalRef<jobjectArray> array = JavaFactories::createArray<T>(env, size);

				if (array.get() == nullptr) {
					throw Exception("Create track array of type [%s] failed", typeid(T).name());
				}

				for (jsize i = 0; i < size; i++) {
					JavaLocalRef<jobject> view = create(env, tracks[i].get());

					env->SetObjectArrayElement(array, i, view.get());
				}

				return array;
			}

			static bool isView(JNIEnv * env, jobject javaTrack);

		private:
			static JavaLocalRef<jobject> markView(JNIEnv * env, JavaLocalRef<jobject> && javaTrack);

			class JavaMediaStreamTrackClass : public JavaClass
			{
				public:
					explicit JavaMediaStreamTrackClass(JNIEnv * env);

					jfieldID view;
			};
	};
}

#endif
