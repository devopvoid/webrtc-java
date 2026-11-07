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

#include "JNI_PlaybackProbe.h"
#include "media/PlaybackProbe.h"

// The Java class this belongs to exists in the tests only, so that nothing but
// them can reach it.

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_media_player_PlaybackProbe_seeks
(JNIEnv * env, jclass caller)
{
	return static_cast<jlong>(ffmpeg::PlaybackProbe::Seeks());
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_player_PlaybackProbe_waitingPushes
(JNIEnv * env, jclass caller)
{
	return static_cast<jint>(ffmpeg::PlaybackProbe::WaitingPushes());
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_player_PlaybackProbe_waitingPlayers
(JNIEnv * env, jclass caller)
{
	return static_cast<jint>(ffmpeg::PlaybackProbe::WaitingPlayers());
}
