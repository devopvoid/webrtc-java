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

public class VideoDesktopSource extends VideoTrackSource {

	public VideoDesktopSource() {
		super();

		initialize();
	}

	public native void setSourceId(long sourceId, boolean isWindow);

	public native void setFrameRate(int frameRate);

	public native void setMaxFrameSize(int width, int height);

	public native void start();

	public native void stop();

	public native void dispose();

	private native void initialize();

}
