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

package dev.onvoid.webrtc.media.video.codec;

/**
 * The result of a {@link VideoEncoder} or {@link VideoDecoder} operation.
 * <p>
 * Of the errors, {@link #FALLBACK_SOFTWARE} and {@link #UNINITIALIZED} give
 * the codec up for the stream. Any other error makes WebRTC release and
 * initialize the codec again, and give it up only if that fails.
 *
 * @author Alex Andres
 */
public enum VideoCodecStatus {

	/** The operation succeeded. */
	OK(0),

	/** The operation succeeded, but produced no output. */
	NO_OUTPUT(1),

	/** The operation succeeded, and the encoder asks for a key frame. */
	OK_REQUEST_KEYFRAME(4),

	/** The encoder overshot its target bitrate. */
	TARGET_BITRATE_OVERSHOOT(5),

	/** A generic error. */
	ERROR(-1),

	/** Memory could not be allocated. */
	MEMORY(-3),

	/** A parameter was not valid. */
	ERR_PARAMETER(-4),

	/** The operation timed out. */
	TIMEOUT(-6),

	/** The codec was not initialized. */
	UNINITIALIZED(-7),

	/** The codec cannot go on, and another implementation is to be used. */
	FALLBACK_SOFTWARE(-13),

	/** The encoder does not support the requested simulcast settings. */
	ERR_SIMULCAST_PARAMETERS_NOT_SUPPORTED(-15),

	/** The encoder failed. */
	ENCODER_FAILURE(-16);


	private final int number;


	VideoCodecStatus(int number) {
		this.number = number;
	}

	/**
	 * @return The WebRTC status code, a {@code WEBRTC_VIDEO_CODEC_*} value.
	 */
	public int getNumber() {
		return number;
	}

}
