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

package dev.onvoid.webrtc.media.video.desktop;

import dev.onvoid.webrtc.internal.DisposableNativeObject;

import java.util.List;

public abstract class DesktopCapturer extends DisposableNativeObject {

	public enum Result {

		/**
		 * The frame was captured successfully.
		 */
		SUCCESS,

		/**
		 * There was a temporary error. The caller should continue calling
		 * CaptureFrame(), in the expectation that it will eventually recover.
		 */
		ERROR_TEMPORARY,

		/**
		 * Capture has failed and will keep failing if the caller tries calling
		 * CaptureFrame() again.
		 */
		ERROR_PERMANENT,

		/**
		 * ERROR_PERMANENT
		 */
		MAX_VALUE;
	}


	/**
	 * The DesktopCapturer doesn't take ownership of the callback. When the
	 * DesktopCapturer is disposed the callback will be disposed using this
	 * handle.
	 */
	private long callbackHandle;


	@Override
	public native void dispose();

	public native List<DesktopSource> getDesktopSources();

	public native void selectSource(DesktopSource source);

	public native void setFocusSelectedSource(boolean focus);

	public native void start(DesktopCaptureCallback callback);

	public native void captureFrame();

}
