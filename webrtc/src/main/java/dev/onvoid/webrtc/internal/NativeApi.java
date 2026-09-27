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

package dev.onvoid.webrtc.internal;

import java.util.Objects;

import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.RTCRtpSender;

/**
 * The entry point a native extension library uses to reach this library's
 * native side directly, without going through Java for every frame.
 * <p>
 * An extension gets two addresses from here and passes both to its own native
 * code: {@link #tableAddress()}, which is a {@code struct webrtc_java_api} of
 * function pointers declared in {@code webrtc_java_api.h}, and
 * {@link #handleOf(NativeObject)}, which is the native object behind a
 * {@code CustomVideoSource} or {@code CustomAudioSource}. The extension then
 * pushes decoded media straight into that source.
 * <p>
 * Neither native library links against the other, which is what makes this
 * work at all: {@link NativeLoader} extracts the library to a temporary file
 * with a generated name, so there is nothing an extension could link to.
 * <p>
 * This is not API for applications. It has no use outside a native extension,
 * and an address used after the source it belongs to was disposed crashes the
 * process.
 *
 * @author Alex Andres
 */
public final class NativeApi {

	static {
		try {
			NativeLoader.loadLibrary("webrtc-java");
		}
		catch (Exception e) {
			throw new RuntimeException("Load library 'webrtc-java' failed", e);
		}
	}


	private NativeApi() {
		// Static access only.
	}

	/**
	 * Returns the address of this library's {@code struct webrtc_java_api}.
	 * The table has static storage duration, so the address stays valid for
	 * the lifetime of the process and needs no release.
	 *
	 * @return The address of the function table.
	 */
	public static native long tableAddress();

	/**
	 * Returns the interface version of the function table. An extension built
	 * against a different version must not use the table.
	 *
	 * @return The value of {@code WEBRTC_JAVA_API_VERSION} in this library.
	 */
	public static native int version();

	/**
	 * Returns the address of the native object bound to the given Java object,
	 * so that a native extension can act on it directly.
	 *
	 * @param object The object whose native counterpart is wanted.
	 *
	 * @return The native handle, or {@code 0} if the object was disposed.
	 *
	 * @throws NullPointerException If the object is {@code null}.
	 */
	public static long handleOf(NativeObject object) {
		Objects.requireNonNull(object, "NativeObject is null");

		return object.getNativeHandle();
	}

	/**
	 * Returns a handle through which a native extension observes the encoded
	 * frames of the given sender, as they leave the encoder and before any
	 * {@code RTCEncodedFrameTransformer} runs on them. The extension attaches
	 * to it with {@code encoded_observer_add()} of the function table.
	 * <p>
	 * The handle carries a reference, which the extension must drop with
	 * {@code encoded_frames_release()} once it has attached its observers,
	 * or once it decides not to.
	 *
	 * @param sender The sender whose frames are to be observed.
	 *
	 * @return The handle, or {@code 0} if the sender was disposed.
	 *
	 * @throws NullPointerException If the sender is {@code null}.
	 */
	public static long encodedFramesOf(RTCRtpSender sender) {
		return senderFrames(handleOf(sender));
	}

	/**
	 * Returns a handle through which a native extension observes the encoded
	 * frames of the given receiver, as they go to the decoder and after any
	 * {@code RTCEncodedFrameTransformer} ran on them. See {@link
	 * #encodedFramesOf(RTCRtpSender)} for how the handle is used and
	 * released.
	 *
	 * @param receiver The receiver whose frames are to be observed.
	 *
	 * @return The handle, or {@code 0} if the receiver was disposed.
	 *
	 * @throws NullPointerException If the receiver is {@code null}.
	 */
	public static long encodedFramesOf(RTCRtpReceiver receiver) {
		return receiverFrames(handleOf(receiver));
	}

	private static native long senderFrames(long senderHandle);

	private static native long receiverFrames(long receiverHandle);

}
