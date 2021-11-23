/*
 * Copyright 2021 Alex Andres
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

package dev.onvoid.webrtc.media.audio;

import dev.onvoid.webrtc.internal.DisposableNativeObject;

public class AudioConverter extends DisposableNativeObject {

	public AudioConverter(int srcSampleRate, int srcChannels, int dstSampleRate,
			int dstChannels) {
		initialize(srcSampleRate, srcChannels, dstSampleRate, dstChannels);
	}

	public native int convert(byte[] src, int nSrcSamples, byte[] dst, int nDstSamples);

	@Override
	public native void dispose();

	private native void initialize(int srcSampleRate, int srcChannels,
			int dstSampleRate, int dstChannels);

}
