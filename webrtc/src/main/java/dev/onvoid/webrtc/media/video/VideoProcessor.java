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

package dev.onvoid.webrtc.media.video;

/**
 * Processes the frames of a camera or desktop source before they reach its
 * tracks, for example to blur a background, draw an overlay or mirror the
 * picture. Set one with {@link VideoTrackSource#setVideoProcessor}.
 * <p>
 * The source hands each frame to {@link #onFrameCaptured}, after it has
 * adapted the frame to what its tracks ask for, so the frame has the size and
 * comes at the rate that will be sent. The processor delivers the frames to
 * send to the sink it was given with {@link #setSink}: the same frame, a new
 * one, or none to drop it. It may deliver from another thread, later.
 * <p>
 * The frame passed to {@code onFrameCaptured} is released once the method
 * returns. To deliver it or read it afterwards, {@link VideoFrame#retain()
 * retain} it first and {@link VideoFrame#release() release} it when done.
 * The sink does not take over the reference of a delivered frame, so the
 * processor releases a frame it created or retained after delivering it. The
 * pixels of a captured frame may be shared with the source: to change them,
 * write into a new buffer, such as one from {@link NativeI420Buffer#allocate},
 * and deliver a new frame.
 * <p>
 * {@code onFrameCaptured} runs on the source's capture thread and holds up
 * capturing while it runs. An exception it throws is reported to that
 * thread's uncaught exception handler, and the frame is dropped.
 *
 * @author Alex Andres
 */
public interface VideoProcessor {

	/**
	 * Gives the processor the sink to deliver processed frames to. Called by
	 * {@link VideoTrackSource#setVideoProcessor} before the first frame
	 * arrives. The sink does nothing once the processor is replaced or the
	 * source is disposed.
	 *
	 * @param sink The sink that passes frames on to the source's tracks.
	 */
	void setSink(VideoTrackSink sink);

	/**
	 * Called for each frame the source captures and does not drop.
	 *
	 * @param frame The captured frame, valid until this method returns.
	 */
	void onFrameCaptured(VideoFrame frame);

}
