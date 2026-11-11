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
 * How the frames of a video track reach a {@link VideoTrackSink}, chosen
 * when the sink is added with {@link VideoTrack#addSink(VideoTrackSink,
 * VideoFrameAccess)}.
 */
public enum VideoFrameAccess {

	/**
	 * Each sink gets an I420 copy of each frame of its own, which it may
	 * write into without the other sinks or the encoder seeing it. This costs
	 * a full copy of the pixels per frame and sink: at 1080p and 30 frames a
	 * second, about 89 MiB a second for each sink.
	 */
	COPY,

	/**
	 * The sink gets the pixels of the frame that the source handed out, which
	 * the other shared sinks of the track and the encoder use as well, so
	 * that no copy is made for it. Its {@link NativeI420Buffer} has read-only
	 * planes: writing to them throws a {@link java.nio.ReadOnlyBufferException}.
	 * A sink that wants to change a frame asks for a copy with {@link
	 * VideoFrame#copy()}.
	 * <p>
	 * A source whose frames are not in I420, such as one with NV12 frames
	 * from a hardware decoder, has them converted for each sink still.
	 * <p>
	 * The frames may come from a pool of the decoder or capturer, which runs
	 * dry while the sinks hold on to them. A sink that keeps a frame beyond a
	 * frame interval or so should keep a copy and release the frame.
	 */
	SHARED

}
