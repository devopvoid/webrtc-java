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

package dev.onvoid.webrtc;

/**
 * The largest resolution an encoding may be sent in, as {@link
 * RTCRtpEncodingParameters#scaleResolutionDownTo} takes it: an absolute size
 * the video is scaled down to fit in, rather than a factor relative to the
 * size of the frames.
 *
 * @author Alex Andres
 */
public class RTCResolutionRestriction {

	/** The largest width in pixels. */
	public int maxWidth;

	/** The largest height in pixels. */
	public int maxHeight;


	/**
	 * Creates an empty restriction, whose fields are to be set.
	 */
	public RTCResolutionRestriction() {
	}

	/**
	 * Creates a restriction to the given size.
	 *
	 * @param maxWidth  The largest width in pixels.
	 * @param maxHeight The largest height in pixels.
	 */
	public RTCResolutionRestriction(int maxWidth, int maxHeight) {
		this.maxWidth = maxWidth;
		this.maxHeight = maxHeight;
	}

	@Override
	public String toString() {
		return String.format("%s [maxWidth=%d, maxHeight=%d]",
				RTCResolutionRestriction.class.getSimpleName(), maxWidth,
				maxHeight);
	}

}
