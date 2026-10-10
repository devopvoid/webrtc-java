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

import dev.onvoid.webrtc.media.MediaSource;

/**
 * A source for one or more VideoTracks.
 */
public class VideoTrackSource extends MediaSource {

	/*
	 * The native VideoProcessorHost of a source that can run a processor, set
	 * by the native source and cleared when it is disposed.
	 */
	@SuppressWarnings("unused")
	private long processorHostHandle;

	private ProcessedFrameSink processorSink;


	protected VideoTrackSource() {

	}

	/**
	 * Sets the processor that the frames of this source pass through before
	 * they reach its tracks, replacing any processor set before. {@code null}
	 * removes the processor, so frames go to the tracks unchanged.
	 * <p>
	 * Camera and desktop sources support a processor. A {@link
	 * CustomVideoSource} does not: process the frames before pushing them.
	 *
	 * @param processor The processor, or {@code null}.
	 *
	 * @throws UnsupportedOperationException If this source does not support a
	 *                                       processor, or has been disposed.
	 */
	public synchronized void setVideoProcessor(VideoProcessor processor) {
		if (processorHostHandle == 0) {
			throw new UnsupportedOperationException(
					getClass().getSimpleName() + " does not support a VideoProcessor");
		}

		ProcessedFrameSink sink = null;

		if (processor != null) {
			sink = new ProcessedFrameSink(this);

			processor.setSink(sink);
		}

		setVideoProcessorNative(processor);

		// Frames the previous processor delivers from now on are dropped.
		if (processorSink != null) {
			processorSink.detach();
		}

		processorSink = sink;
	}

	/**
	 * Detaches the sink of the current processor, so that it drops frames
	 * delivered while and after this source is disposed. Called by the
	 * sources that support a processor before they dispose of their native
	 * source, which removes the processor.
	 */
	synchronized void detachVideoProcessor() {
		if (processorSink != null) {
			processorSink.detach();
			processorSink = null;
		}
	}

	private native void setVideoProcessorNative(VideoProcessor processor);

	/**
	 * Passes a frame the processor delivered on to the tracks.
	 */
	native void deliverProcessedFrame(VideoFrame frame);

}
