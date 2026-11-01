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

import static org.junit.jupiter.api.Assertions.*;

import dev.onvoid.webrtc.TestBase;
import dev.onvoid.webrtc.internal.SinkRegistry;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VideoTrackTests extends TestBase {

	private VideoTrack videoTrack;


	@BeforeEach
	void init() {
		VideoDeviceSource videoSource = new VideoDeviceSource();

		videoTrack = factory.createVideoTrack("videoTrack", videoSource);
	}

	@AfterEach
	void dispose() {
		videoTrack.dispose();
	}

	@Test
	void disableEnableTrack() {
		videoTrack.setEnabled(false);

		assertFalse(videoTrack.isEnabled());

		videoTrack.setEnabled(true);

		assertTrue(videoTrack.isEnabled());
	}

	@Test
	void addNullSink() {
		assertThrows(NullPointerException.class, () -> videoTrack.addSink(null));
	}

	@Test
	void removeNullSink() {
		assertThrows(NullPointerException.class, () -> videoTrack.removeSink(null));
	}

	@Test
	void addRemoveSink() {
		VideoTrackSink sink = frame -> { };

		videoTrack.addSink(sink);
		videoTrack.removeSink(sink);
	}

	@Test
	void sinkRemovesItself() {
		CustomVideoSource source = new CustomVideoSource();
		VideoTrack track = factory.createVideoTrack("selfRemoving", source);
		AtomicInteger frames = new AtomicInteger();

		track.addSink(new VideoTrackSink() {

			@Override
			public void onVideoFrame(VideoFrame frame) {
				frames.incrementAndGet();
				frame.release();

				// WebRTC holds the lock that removing takes while it is here.
				track.removeSink(this);
			}
		});

		assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
			pushFrame(source);
			pushFrame(source);
		});

		assertEquals(1, frames.get());

		track.dispose();
		source.dispose();
	}

	@Test
	void sinkRemovesOtherSink() {
		CustomVideoSource source = new CustomVideoSource();
		VideoTrack track = factory.createVideoTrack("removingOther", source);
		AtomicInteger otherFrames = new AtomicInteger();

		VideoTrackSink other = frame -> {
			otherFrames.incrementAndGet();
			frame.release();
		};

		// Called first, as it was added first.
		track.addSink(frame -> {
			frame.release();
			track.removeSink(other);
		});
		track.addSink(other);

		assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
			pushFrame(source);
			pushFrame(source);
		});

		assertEquals(0, otherFrames.get());

		track.dispose();
		source.dispose();
	}

	@Test
	void sinkAddsSink() throws Exception {
		CustomVideoSource source = new CustomVideoSource();
		VideoTrack track = factory.createVideoTrack("adding", source);
		AtomicInteger addedFrames = new AtomicInteger();

		VideoTrackSink added = frame -> {
			addedFrames.incrementAndGet();
			frame.release();
		};

		track.addSink(frame -> {
			frame.release();
			track.addSink(added);
		});

		assertTimeoutPreemptively(Duration.ofSeconds(5), () -> pushFrame(source));

		// The native sink is added once the callback returned.
		SinkRegistry.awaitDeferred();

		pushFrame(source);

		assertEquals(1, addedFrames.get());

		track.dispose();
		source.dispose();
	}

	@Test
	void disposeInCallbackFails() {
		CustomVideoSource source = new CustomVideoSource();
		VideoTrack track = factory.createVideoTrack("disposing", source);
		AtomicReference<RuntimeException> failure = new AtomicReference<>();

		track.addSink(frame -> {
			frame.release();

			try {
				track.dispose();
			}
			catch (RuntimeException e) {
				failure.set(e);
			}
		});

		assertTimeoutPreemptively(Duration.ofSeconds(5), () -> pushFrame(source));

		assertInstanceOf(IllegalStateException.class, failure.get());

		track.dispose();
		source.dispose();
	}

	@Test
	void addSinkWithoutAccess() {
		assertThrows(NullPointerException.class, () -> videoTrack.addSink(frame -> { }, null));
	}

	@Test
	void sharedSinksShareThePixels() {
		CustomVideoSource source = new CustomVideoSource();
		VideoTrack track = factory.createVideoTrack("shared", source);
		List<VideoFrame> shared = new CopyOnWriteArrayList<>();
		List<VideoFrame> copied = new CopyOnWriteArrayList<>();

		track.addSink(shared::add, VideoFrameAccess.SHARED);
		track.addSink(shared::add, VideoFrameAccess.SHARED);
		track.addSink(copied::add);

		NativeI420Buffer pushed = NativeI420Buffer.allocate(320, 240);
		pushed.getDataY().put(0, (byte) 1);

		VideoFrame frame = new VideoFrame(pushed, 0);
		source.pushFrame(frame);

		assertEquals(2, shared.size());
		assertEquals(1, copied.size());

		// The pixels of the source change under the shared sinks, which got
		// them without a copy, and not under the copy.
		pushed.getDataY().put(0, (byte) 7);

		for (VideoFrame sharedFrame : shared) {
			ByteBuffer y = dataY(sharedFrame);

			assertTrue(y.isReadOnly());
			assertEquals(7, y.get(0));
			assertThrows(ReadOnlyBufferException.class, () -> y.put(0, (byte) 9));
		}

		ByteBuffer copiedY = dataY(copied.get(0));

		assertFalse(copiedY.isReadOnly());
		assertEquals(1, copiedY.get(0));

		// A sink that wants to write asks for a copy.
		VideoFrame writable = shared.get(0).copy();
		dataY(writable).put(0, (byte) 9);

		assertEquals(9, dataY(writable).get(0));
		assertEquals(7, dataY(shared.get(1)).get(0));

		writable.release();
		shared.forEach(VideoFrame::release);
		copied.forEach(VideoFrame::release);
		frame.release();

		track.dispose();
		source.dispose();
	}

	private static ByteBuffer dataY(VideoFrame frame) {
		return ((I420Buffer) frame.buffer).getDataY();
	}

	private static void pushFrame(CustomVideoSource source) {
		VideoFrame frame = new VideoFrame(NativeI420Buffer.allocate(320, 240), 0);

		source.pushFrame(frame);
		frame.release();
	}

}
