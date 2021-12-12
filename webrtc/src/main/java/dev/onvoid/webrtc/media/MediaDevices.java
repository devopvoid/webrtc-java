/*
 * Copyright 2019 Alex Andres
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

package dev.onvoid.webrtc.media;

import dev.onvoid.webrtc.internal.NativeLoader;
import dev.onvoid.webrtc.media.audio.AudioDevice;
import dev.onvoid.webrtc.media.video.VideoCaptureCapability;
import dev.onvoid.webrtc.media.video.VideoDevice;

import java.util.List;

public class MediaDevices {

	static {
		try {
			NativeLoader.loadLibrary("webrtc-java");
		}
		catch (Exception e) {
			throw new RuntimeException("Load library 'webrtc-java' failed", e);
		}
	}


	public static native void addDeviceChangeListener(DeviceChangeListener listener);

	public static native void removeDeviceChangeListener(DeviceChangeListener listener);

	public static native AudioDevice getDefaultAudioRenderDevice();

	public static native AudioDevice getDefaultAudioCaptureDevice();

	public static native List<AudioDevice> getAudioRenderDevices();

	public static native List<AudioDevice> getAudioCaptureDevices();

	public static native List<VideoDevice> getVideoCaptureDevices();

	public static native List<VideoCaptureCapability> getVideoCaptureCapabilities(VideoDevice device);

}
