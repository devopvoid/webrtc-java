#include "JNI_AudioTrackSource.h"
#include <WebRTCUtils.h>
#include <AudioTrackSink.h>
#include <api/media_stream_interface.h>
#include "JavaFactories.h"
#include "JavaRef.h"
#include "JavaUtils.h"
/*
* Class:     dev_onvoid_webrtc_media_audio_AudioTrackSource
 * Method:    addSinkInternal
 * Signature: (Ldev/onvoid/webrtc/media/audio/AudioTrackSink;)J
 */
JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_media_audio_AudioTrackSource_addSinkInternal
  (JNIEnv * env, jobject caller, jobject jsink)
{
	if (jsink == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "AudioTrackSink must not be null"));
		return 0;
	}

	webrtc::AudioSourceInterface * source = GetHandle<webrtc::AudioSourceInterface>(env, caller);
	CHECK_HANDLEV(source, 0);

	auto sink = new jni::AudioTrackSink(env, jni::JavaGlobalRef<jobject>(env, jsink));
	source->AddSink(sink);
	
	return reinterpret_cast<jlong>(sink);
}

/*
 * Class:     dev_onvoid_webrtc_media_audio_AudioTrackSource
 * Method:    removeSinkInternal
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioTrackSource_removeSinkInternal
  (JNIEnv * env,jobject caller, jlong sinkHandle)
{
	webrtc::AudioSourceInterface * source = GetHandle<webrtc::AudioSourceInterface>(env, caller);
	CHECK_HANDLE(source);
	
	auto sink = reinterpret_cast<jni::AudioTrackSink *>(sinkHandle);
	
	if (sink != nullptr) {
		source->RemoveSink(sink);
		
		delete sink;
	}
}

/*
 * Class:     dev_onvoid_webrtc_media_audio_AudioTrackSource
 * Method:    setVolume
 * Signature: (D)V
 */
JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioTrackSource_setVolume
  (JNIEnv * env, jobject caller, jdouble jvolume)
{
	webrtc::AudioSourceInterface * source = GetHandle<webrtc::AudioSourceInterface>(env, caller);
	CHECK_HANDLE(source);
	
	source->SetVolume(static_cast<uint32_t>(jvolume));
}