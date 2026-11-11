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

package dev.onvoid.webrtc.media.recorder;

import java.util.ArrayList;
import java.util.List;

/**
 * Plays {@link FakeTrack}s into a recorder on a clock of the test's own, at
 * a fixed frame rate each: video at 25 frames a second, audio in frames of
 * 20 ms, both from the moment the feed was created on.
 * <p>
 * Each moment a frame is due, the feed sets the clock, waits for the
 * recorder to have seen it, pushes the frames due in the order the tracks
 * were added, and waits for the recorder to have handled them. So whatever
 * the recorder decides by the clock, such as how long it waited for a
 * track, it decides before the frames of that moment, and whatever it
 * decides on a frame, it decides at the frame's time. Nothing depends on
 * how fast the machine is.
 *
 * @author Alex Andres
 */
final class FakeFeed {

	/** What the feed waits for: {@link MediaRecorder#sync()}. */
	interface Writer {

		void sync() throws InterruptedException;

	}

	/** Where the clock starts, far from zero like a real one. */
	static final long START_US = 100_000_000L;

	static final long VIDEO_INTERVAL_US = 40_000;

	static final long AUDIO_INTERVAL_US = 20_000;

	/** How often the clock moves on without frames, at most. */
	private static final long TICK_US = 100_000;

	private final Writer writer;

	private final List<Source> sources = new ArrayList<>();

	private long nowUs = START_US;


	/**
	 * Creates a feed and sets the clock to its start, which has to come
	 * before the recorder is started.
	 */
	FakeFeed(Writer writer, FakeTrack... tracks) {
		this.writer = writer;

		for (FakeTrack track : tracks) {
			sources.add(new Source(track));
		}

		FakeMedia.setClock(nowUs);
	}

	/**
	 * @return The time since the start, in microseconds.
	 */
	long elapsedUs() {
		return nowUs - START_US;
	}

	/**
	 * Plays the tracks on for the given time: the frames due before its end
	 * are pushed, and the clock is left at the end.
	 */
	void play(long durationUs) throws InterruptedException {
		long endUs = nowUs + durationUs;

		while (true) {
			long next = Math.min(endUs, nowUs + TICK_US);

			for (Source source : sources) {
				if (source.playing) {
					next = Math.min(next, source.nextUs);
				}
			}

			moveTo(next);

			if (next == endUs) {
				break;
			}

			for (Source source : sources) {
				if (source.playing && source.nextUs == next) {
					source.track.push(next);
					source.nextUs += source.intervalUs;
				}
			}

			writer.sync();
		}
	}

	/**
	 * Plays the tracks on until the time since the start is the given one.
	 */
	void playUntil(long elapsedUs) throws InterruptedException {
		play(START_US + elapsedUs - nowUs);
	}

	/**
	 * Lets the track begin later than the others, at the given time since the
	 * start, e.g. half a frame later, so that its frames come at moments of
	 * their own.
	 */
	void startAt(FakeTrack track, long elapsedUs) {
		sourceOf(track).nextUs = START_US + elapsedUs;
	}

	/**
	 * Stops pushing frames of the track, as a sender does that has nothing to
	 * send.
	 */
	void pause(FakeTrack track) {
		sourceOf(track).playing = false;
	}

	/**
	 * Pushes frames of the track again, from now on.
	 */
	void resume(FakeTrack track) {
		Source source = sourceOf(track);

		source.playing = true;
		source.nextUs = nowUs;
	}

	private void moveTo(long timeUs) throws InterruptedException {
		nowUs = timeUs;

		FakeMedia.setClock(nowUs);
		writer.sync();

		// What the recorder asked for by now is answered from here on.
		for (Source source : sources) {
			source.track.takeRequests();
		}
	}

	private Source sourceOf(FakeTrack track) {
		for (Source source : sources) {
			if (source.track == track) {
				return source;
			}
		}

		throw new IllegalArgumentException("Not a track of this feed");
	}


	private static final class Source {

		final FakeTrack track;

		final long intervalUs;

		boolean playing = true;

		long nextUs = START_US;


		Source(FakeTrack track) {
			this.track = track;
			this.intervalUs = track.isVideo() ? VIDEO_INTERVAL_US : AUDIO_INTERVAL_US;
		}
	}

}
