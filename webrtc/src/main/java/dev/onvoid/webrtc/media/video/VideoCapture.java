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

import dev.onvoid.webrtc.internal.NativeObject;

/**
 * A class representing a video capture device that can be controlled to capture video.
 * Extends NativeObject to interact with native code implementations.
 * <pre>
 * VideoCapture capture = new VideoCapture();
 * capture.setVideoCaptureDevice(device);
 * capture.setVideoCaptureCapability(capability);
 * capture.setVideoSink(sink);
 * capture.start();
 * // ... consume frames via sink ...
 * capture.stop();
 * capture.dispose();
 * </pre>
 * Notes:
 * <li>Always call dispose() to release native resources.</li>
 * <li>Methods are not guaranteed to be thread-safe unless externally synchronized.</li>
 *
 * @author Alex Andres
 */
public class VideoCapture extends NativeObject {

	/**
	 * Constructs a new VideoCapture and initializes underlying native resources.
	 * Initialization failures are typically surfaced as runtime exceptions
	 * originating from native code.
	 */
	public VideoCapture() {
		initialize();
	}

	/**
	 * Selects the physical (or virtual) video input device to capture from.
	 * <p>
	 * Constraints / Lifecycle:
	 * <li>Must be invoked before {@link #start()}.</li>
	 * <li>Re-setting the device after capture has started may require an
	 *     internal restart (implementation-dependent).</li>
	 *
	 * @param device Non-null device descriptor to bind. Passing {@code null}
	 *               is invalid and may raise a {@link NullPointerException}.
	 *
	 * @throws IllegalStateException if called after disposal.
	 */
	public native void setVideoCaptureDevice(VideoDevice device);

	/**
	 * Defines desired capture parameters (resolution, frame rate, pixel format, etc.).
	 * <p>
	 * Constraints:
	 * <li>Should be called after selecting the device and before {@link #start()}.</li>
	 * <li>Some capabilities may be adjusted (e.g., negotiated to the nearest supported values).</li>
	 *
	 * @param capability Desired capture capability (must be non-null).
	 *
	 * @throws IllegalArgumentException if unsupported or invalid.
	 * @throws IllegalStateException    if called after disposal.
	 */
	public native void setVideoCaptureCapability(VideoCaptureCapability capability);

	/**
	 * Registers (or replaces) the sink that will receive decoded/raw video frames.
	 * <p>
	 * Behavior:
	 * <li>May be called before or after {@link #start()}.</li>
	 * <li>Passing {@code null} (if supported) detaches the current sink and
	 *     frames will be dropped until a new sink is set.</li>
	 *
	 * @param sink The consumer of captured frames.
	 *
	 * @throws IllegalStateException if called after disposal.
	 */
	public native void setVideoSink(VideoTrackSink sink);

	/**
	 * Begins asynchronous frame capture and delivery to the configured sink.
	 * <p>
	 * Idempotency:
	 * Calling start() while already started should be a no-op (implementation-dependent).
	 *
	 * @throws IllegalStateException if prerequisites (device/capability) are missing
	 *                               or the instance is disposed.
	 */
	public native void start();

	/**
	 * Stops frame capture.
	 * <p>
	 * Behavior:
	 * <li>Drains or discards in-flight frames (implementation-dependent).</li>
	 * <li>Safe to call multiple times (idempotent).</li>
	 *
	 * @throws IllegalStateException if the instance is disposed.
	 */
	public native void stop();

	/**
	 * Releases native resources associated with this capture instance.
	 * <p>
	 * Lifecycle:
	 * <li>Implicitly stops capture if currently running.</li>
	 * <li>After disposal, further method calls (other than additional dispose attempts)
	 *     are invalid and may throw {@link IllegalStateException}.</li>
	 * <p>
	 * Best Practice:
	 * Always invoke in a finally block or use a higher-level resource management
	 * construct to avoid native leaks.
	 */
	public native void dispose();

	/**
	 * Internal native initialization hook invoked exactly once by the constructor.
	 * Not intended for direct external use.
	 * <p>
	 * Failure Handling:
	 * Should raise a runtime exception if initialization fails.
	 */
	private native void initialize();

}
