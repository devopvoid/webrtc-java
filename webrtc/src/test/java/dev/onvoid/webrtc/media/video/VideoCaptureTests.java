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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.util.List;

import dev.onvoid.webrtc.TestBase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.SAME_THREAD)
class VideoCaptureTests extends TestBase {

	@Test
	void startWithoutDevice() {
		VideoCapture capture = new VideoCapture();

		try {
			IllegalStateException e = assertThrows(IllegalStateException.class, capture::start);

			assertEquals("No video capture device set", e.getMessage());
		}
		finally {
			capture.dispose();
		}
	}

	@Test
	void startReportsNativeError() {
		VideoCapture capture = new VideoCapture();

		try {
			capture.setVideoCaptureDevice(new VideoDevice("webrtc-java missing camera", "missing"));

			// Depending on the machine there is no camera at all or not this one;
			// either way the native message arrives instead of "Unhandled Exception".
			Error e = assertThrows(Error.class, capture::start);

			assertTrue(List.of("No video capture devices available",
					"Device webrtc-java missing camera not found").contains(e.getMessage()),
					e.getMessage());
		}
		finally {
			capture.dispose();
		}
	}

	@Test
	void disposeReleasesSink() {
		VideoCapture capture = new VideoCapture();
		WeakReference<VideoTrackSink> sinkRef = setSink(capture);

		capture.dispose();

		// Only the native sink's global reference could keep the sink alive.
		for (int i = 0; i < 50 && sinkRef.get() != null; i++) {
			System.gc();
		}

		assertTrue(sinkRef.get() == null, "Sink still referenced after dispose");
	}

	@Test
	void disposeTwice() {
		VideoCapture capture = new VideoCapture();
		capture.dispose();

		assertThrows(NullPointerException.class, capture::dispose);
	}

	private static WeakReference<VideoTrackSink> setSink(VideoCapture capture) {
		// Not a non-capturing lambda, which the JVM would keep alive itself.
		VideoTrackSink sink = new VideoTrackSink() {
			@Override
			public void onVideoFrame(VideoFrame frame) {
			}
		};

		capture.setVideoSink(sink);

		return new WeakReference<>(sink);
	}
}
