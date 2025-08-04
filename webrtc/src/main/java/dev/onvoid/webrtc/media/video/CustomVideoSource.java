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

package dev.onvoid.webrtc.media.video;

/**
 * Custom implementation of a video source for WebRTC that allows pushing video frames
 * from external sources directly to the WebRTC video pipeline.
 *
 * @author Alex Andres
 */
public class CustomVideoSource extends VideoTrackSource {

	/**
	 * Constructs a new CustomVideoSource instance.
	 */
	public CustomVideoSource() {
		super();

		initialize();
	}

	/**
	 * Pushes audio data to be processed by this audio source.
	 *
	 * @param frame The video frame to be pushed to the source.
	 */
	public native void pushFrame(VideoFrame frame);

	/**
	 * Disposes of any native resources held by this video source.
	 * This method should be called when the video source is no longer needed
	 * to prevent memory leaks.
	 */
	public native void dispose();

	/**
	 * Initializes the native resources required by this video source.
	 */
	private native void initialize();

}
