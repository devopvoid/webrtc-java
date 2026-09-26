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

#ifndef WEBRTC_JAVA_API_H_
#define WEBRTC_JAVA_API_H_

/*
 * The C interface a native extension library uses to feed media into
 * webrtc-java's custom media sources, without linking against this library or
 * against WebRTC, and without routing the media through Java.
 *
 * Deliberately plain C: no C++ types, no JNI types, no WebRTC types. An
 * extension compiled against this header keeps working against any build of
 * webrtc-java that reports the same interface version, whatever compiler or
 * standard library either side was built with.
 *
 * How an extension obtains the table:
 *
 *   1. Java side: dev.onvoid.webrtc.internal.NativeApi.tableAddress() returns
 *      the address of a "struct webrtc_java_api" owned by this library, and
 *      NativeApi.handleOf(source) returns the native handle of a
 *      CustomVideoSource or CustomAudioSource.
 *   2. The extension receives both as jlong values and casts them back.
 *
 * There is no link-time dependency between the two native libraries, so the
 * extension does not care where this library was loaded from or under which
 * file name, which matters because NativeLoader extracts it to a temporary
 * file with a generated name.
 *
 * Versioning: the "version" field is the first member of the table and never
 * moves. An extension must refuse to run when it reads a version it does not
 * know. Within one version, members are only ever appended, never reordered
 * or removed, and "size" tells an extension how much of the table the loaded
 * library actually provides.
 *
 * Threading: every function may be called from any thread, including threads
 * that are not attached to the JVM. The push functions deliver to WebRTC
 * synchronously on the calling thread, so the caller must not hold locks that
 * a WebRTC callback could need, and must pace its calls in real time: a frame
 * is encoded and sent when it arrives, not when its timestamp says.
 */

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/** The interface version described by this header. */
#define WEBRTC_JAVA_API_VERSION 1u

/** Success, returned by every function that reports a status. */
#define WEBRTC_JAVA_OK 0
/** A handle was null, or a required pointer inside a struct was null. */
#define WEBRTC_JAVA_ERR_HANDLE (-1)
/** A field of the pushed frame or chunk is out of range. */
#define WEBRTC_JAVA_ERR_FORMAT (-2)

/**
 * Called when WebRTC has released its last reference to the pixel data of a
 * pushed video frame, and the extension may reuse or free it. It runs on
 * whichever thread drops that reference, which is usually an encoder thread
 * and not the thread that pushed the frame, and it may run after the source
 * itself was disposed. It must not block.
 */
typedef void (*wj_release_fn)(void * opaque);

/**
 * One decoded video frame in I420 (planar YUV 4:2:0, 8 bit).
 *
 * The planes are not copied. They must stay valid and unmodified until
 * "release" is called. Passing a null "release" means the caller guarantees
 * the planes outlive every use of the frame, which is rarely true; prefer
 * handing over a reference and dropping it in the callback.
 */
struct wj_i420_frame {
	/** Frame width in pixels, greater than zero. */
	int width;
	/** Frame height in pixels, greater than zero. */
	int height;

	/** Luma plane, "height" rows of "stride_y" bytes. */
	const uint8_t * y;
	/** Blue-difference chroma plane, (height + 1) / 2 rows of "stride_u". */
	const uint8_t * u;
	/** Red-difference chroma plane, (height + 1) / 2 rows of "stride_v". */
	const uint8_t * v;

	/** Luma row stride in bytes, at least "width". */
	int stride_y;
	/** Chroma row stride in bytes, at least (width + 1) / 2. */
	int stride_u;
	/** Chroma row stride in bytes, at least (width + 1) / 2. */
	int stride_v;

	/** Clockwise rotation to apply on render: 0, 90, 180 or 270. */
	int rotation;

	/**
	 * Capture time in the clock of now_us(). Frames must be pushed in
	 * increasing timestamp order. A value of zero means "stamp it with the
	 * source's own clock", which is what the Java pushFrame() does.
	 */
	int64_t timestamp_us;

	/** Called once WebRTC is done with the planes; may be null. */
	wj_release_fn release;
	/** Passed to "release" unchanged. */
	void * opaque;
};

/**
 * One chunk of decoded audio: interleaved signed 16-bit PCM in host byte
 * order, exactly 10 ms long, which is the only chunk length WebRTC accepts.
 *
 * The samples are copied before the call returns, so the caller may reuse the
 * buffer immediately.
 */
struct wj_audio_chunk {
	/** "frames" * "channels" samples, interleaved. */
	const int16_t * samples;
	/** Sample rate in Hz, at most 48000. */
	int sample_rate;
	/** Channel count, 1 or 2. */
	int channels;
	/** Frames per channel, which must equal sample_rate / 100. */
	int frames;

	/**
	 * Capture time in the clock of now_us(), or zero to stamp the chunk with
	 * the source's own clock, which is what the Java pushAudio() does.
	 */
	int64_t timestamp_us;
};

/**
 * The function table this library exposes to native extensions. It is a
 * singleton with static storage duration, so its address stays valid for the
 * lifetime of the process and needs no release.
 */
struct webrtc_java_api {
	/** WEBRTC_JAVA_API_VERSION of the library providing this table. */
	uint32_t version;
	/** sizeof(struct webrtc_java_api) as the library compiled it. */
	uint32_t size;

	/**
	 * The monotonic clock WebRTC itself uses, in microseconds. Timestamps in
	 * pushed frames and chunks are interpreted in this clock, so an extension
	 * that wants audio and video to line up on the receiver maps its own
	 * presentation times onto it once and keeps that mapping.
	 */
	int64_t (*now_us)(void);

	/**
	 * Delivers one video frame to a CustomVideoSource.
	 *
	 * @param source The handle from NativeApi.handleOf(CustomVideoSource).
	 * @param frame  The frame to deliver; borrowed for the call only.
	 *
	 * @return WEBRTC_JAVA_OK, or a negative error code, in which case
	 *         "release" is still called before returning.
	 */
	int (*video_source_push)(void * source, const struct wj_i420_frame * frame);

	/**
	 * Delivers one 10 ms audio chunk to a CustomAudioSource.
	 *
	 * @param source The handle from NativeApi.handleOf(CustomAudioSource).
	 * @param chunk  The chunk to deliver; copied during the call.
	 *
	 * @return WEBRTC_JAVA_OK or a negative error code.
	 */
	int (*audio_source_push)(void * source, const struct wj_audio_chunk * chunk);
};

#ifdef __cplusplus
}
#endif

#endif
