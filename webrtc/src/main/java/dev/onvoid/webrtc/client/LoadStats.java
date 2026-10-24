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

package dev.onvoid.webrtc.client;

import java.time.Duration;

/**
 * How much work the threads of a runtime have, at one moment. Read it with
 * {@link WebRtc#loadStats()}, as often as needed; taking it is cheap and
 * waits for nothing.
 * <p>
 * A queue that keeps growing, or an oldest waiting item that keeps getting
 * older, tells that handlers do not keep up. Raising a {@link
 * WebRtc.Builder#eventThreads(int) thread limit} helps when the handlers
 * wait for something; when they compute, it only spreads the delay.
 */
public final class LoadStats {

	private final Work control;

	private final Work events;

	private final Work video;

	private final Work audio;

	private final int controlThreads;

	private final int eventThreads;

	private final int frameThreads;


	LoadStats(Work control, Work events, Work video, Work audio,
			int controlThreads, int eventThreads, int frameThreads) {
		this.control = control;
		this.events = events;
		this.video = video;
		this.audio = audio;
		this.controlThreads = controlThreads;
		this.eventThreads = eventThreads;
		this.frameThreads = frameThreads;
	}

	/**
	 * @return the tasks of the library that call into WebRTC. They wait
	 * there only while the control threads are busy, so a backlog here points
	 * at slow native calls, not at handlers.
	 */
	public Work control() {
		return control;
	}

	/**
	 * @return the events of all peers waiting for their handlers, future
	 * callbacks and {@code Signaling.send} calls. Events are never dropped;
	 * {@link Work#dropped()} counts those an {@link
	 * WebRtc.Builder#eventExecutor(java.util.concurrent.Executor) executor of
	 * the application} rejected.
	 */
	public Work events() {
		return events;
	}

	/**
	 * @return the video frames waiting for {@code onFrame} handlers, local
	 * previews included. {@link Work#dropped()} counts the frames a handler
	 * had no time for.
	 */
	public Work video() {
		return video;
	}

	/**
	 * @return the audio chunks waiting for {@code onAudio} handlers. {@link
	 * Work#dropped()} counts the chunks that no longer fit into the audio
	 * buffer of their subscription.
	 */
	public Work audio() {
		return audio;
	}

	/**
	 * @return the threads of the control pool right now.
	 */
	public int controlThreads() {
		return controlThreads;
	}

	/**
	 * @return the threads of the runtime's event pool right now; 0 when the
	 * application's executor runs the events.
	 */
	public int eventThreads() {
		return eventThreads;
	}

	/**
	 * @return the threads that run {@code onFrame} and {@code onAudio}
	 * handlers right now.
	 */
	public int frameThreads() {
		return frameThreads;
	}

	@Override
	public String toString() {
		return "LoadStats[control " + control + " on " + controlThreads + " threads, events " + events
				+ " on " + eventThreads + " threads, video " + video + ", audio " + audio
				+ " on " + frameThreads + " threads]";
	}



	/** The work of one kind. */
	public static final class Work {

		private final int queued;

		private final Duration oldestWaiting;

		private final int active;

		private final long dropped;


		Work(int queued, Duration oldestWaiting, int active, long dropped) {
			this.queued = queued;
			this.oldestWaiting = oldestWaiting;
			this.active = active;
			this.dropped = dropped;
		}

		/**
		 * @return the items waiting for a thread, of all peers and
		 * subscriptions together.
		 */
		public int queued() {
			return queued;
		}

		/**
		 * @return how long the item that waits longest has waited; zero when
		 * none waits.
		 */
		public Duration oldestWaiting() {
			return oldestWaiting;
		}

		/**
		 * @return the items being handled right now, which is the number of
		 * handlers running.
		 */
		public int active() {
			return active;
		}

		/**
		 * @return the items dropped since the runtime was built, without
		 * those left over when a subscription, peer or runtime closed.
		 */
		public long dropped() {
			return dropped;
		}

		@Override
		public String toString() {
			return queued + " queued (oldest " + oldestWaiting.toMillis() + " ms), "
					+ active + " active, " + dropped + " dropped";
		}
	}
}
