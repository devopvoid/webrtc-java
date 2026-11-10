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
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * What a {@link CallCheck} found. It names the problems with their reason
 * rather than a score, and the measurements behind them. A part that was not
 * checked is null.
 */
public final class CallCheckResult {

	/** A problem a check found. */
	public enum Problem {

		/** The video track delivered no frame within the timeout. */
		NO_VIDEO("The video track delivered no frame."),

		/** Every frame was nearly black. */
		DARK_VIDEO("Every frame was nearly black: a covered lens, a closed privacy shutter or no light."),

		/** The audio track delivered no audio within the timeout. */
		NO_AUDIO("The audio track delivered no audio."),

		/** The audio never rose above -50 dBFS. */
		SILENT_AUDIO("The audio stayed below -50 dBFS: a muted microphone, or nobody spoke during the check."),

		/** More than 0.1% of the samples were at full scale. */
		CLIPPED_AUDIO("More than 0.1% of the samples were at full scale: the input is too loud and distorts."),

		/** No connection to the test peer came up within the timeout. */
		NO_CONNECTION("No connection to the test peer came up.");

		private final String reason;


		Problem(String reason) {
			this.reason = reason;
		}

		/**
		 * @return what the problem means, in a sentence.
		 */
		public String reason() {
			return reason;
		}
	}

	private final Video video;

	private final Audio audio;

	private final Network network;

	private final Set<Problem> problems;


	CallCheckResult(Video video, Audio audio, Network network) {
		this.video = video;
		this.audio = audio;
		this.network = network;

		Set<Problem> found = EnumSet.noneOf(Problem.class);

		if (video != null) {
			video.addProblems(found);
		}
		if (audio != null) {
			audio.addProblems(found);
		}
		if (network != null && !network.isConnected()) {
			found.add(Problem.NO_CONNECTION);
		}

		this.problems = Collections.unmodifiableSet(found);
	}

	/**
	 * @return true when no check found a problem. That is not a guarantee
	 * that the call will work, only that nothing checked stands in its way.
	 */
	public boolean isReady() {
		return problems.isEmpty();
	}

	/**
	 * @return the problems found; empty when there is none.
	 */
	public Set<Problem> problems() {
		return problems;
	}

	/**
	 * @return the measurements of the video track, or null when it was not
	 * checked.
	 */
	public Video video() {
		return video;
	}

	/**
	 * @return the measurements of the audio track, or null when it was not
	 * checked.
	 */
	public Audio audio() {
		return audio;
	}

	/**
	 * @return the connection to the test peer, or null when it was not
	 * checked.
	 */
	public Network network() {
		return network;
	}

	@Override
	public String toString() {
		return (isReady() ? "READY" : "NOT READY " + problems)
				+ (video != null ? " " + video : "")
				+ (audio != null ? " " + audio : "")
				+ (network != null ? " " + network : "");
	}



	/** The frames a video track delivered. */
	public static final class Video {

		/** The brightness below which a frame counts as nearly black. */
		static final double DARK = 0.08;

		private final int frames;

		private final double framesPerSecond;

		private final int width;

		private final int height;

		private final double brightness;


		Video(int frames, double framesPerSecond, int width, int height, double brightness) {
			this.frames = frames;
			this.framesPerSecond = framesPerSecond;
			this.width = width;
			this.height = height;
			this.brightness = brightness;
		}

		/**
		 * @return the frames measured.
		 */
		public int frames() {
			return frames;
		}

		/**
		 * @return the rate at which the frames arrived; 0 with fewer than two.
		 */
		public double framesPerSecond() {
			return framesPerSecond;
		}

		/**
		 * @return the width of the last frame; 0 without one.
		 */
		public int width() {
			return width;
		}

		/**
		 * @return the height of the last frame; 0 without one.
		 */
		public int height() {
			return height;
		}

		/**
		 * @return the average luma of the brightest frame measured, from 0
		 * (black) to 1 (white); 0 without a frame. A camera's first frames are
		 * often dark while its exposure settles, so the brightest one counts.
		 */
		public double brightness() {
			return brightness;
		}

		void addProblems(Set<Problem> found) {
			if (frames == 0) {
				found.add(Problem.NO_VIDEO);
			}
			else if (brightness < DARK) {
				found.add(Problem.DARK_VIDEO);
			}
		}

		@Override
		public String toString() {
			return String.format("video[%d frames, %.1f fps, %dx%d, brightness %.2f]",
					frames, framesPerSecond, width, height, brightness);
		}
	}



	/** The audio an audio track delivered. */
	public static final class Audio {

		/** The peak level below which audio counts as silent, -50 dBFS. */
		static final double SILENT = 0.00316;

		/** The share of samples at full scale above which audio counts as clipped. */
		static final double CLIPPED = 0.001;

		private final Duration received;

		private final double peakLevel;

		private final double rmsLevel;

		private final double clipping;


		Audio(Duration received, double peakLevel, double rmsLevel, double clipping) {
			this.received = received;
			this.peakLevel = peakLevel;
			this.rmsLevel = rmsLevel;
			this.clipping = clipping;
		}

		/**
		 * @return how much audio was measured.
		 */
		public Duration received() {
			return received;
		}

		/**
		 * @return the highest sample, linear, from 0 to 1 (full scale).
		 */
		public double peakLevel() {
			return peakLevel;
		}

		/**
		 * @return the root mean square of the samples, linear, from 0 to 1.
		 */
		public double rmsLevel() {
			return rmsLevel;
		}

		/**
		 * @return the share of samples at full scale, at 99% of it or above,
		 * from 0 to 1.
		 */
		public double clipping() {
			return clipping;
		}

		void addProblems(Set<Problem> found) {
			if (received.isZero()) {
				found.add(Problem.NO_AUDIO);
			}
			else if (peakLevel < SILENT) {
				found.add(Problem.SILENT_AUDIO);
			}
			else if (clipping > CLIPPED) {
				found.add(Problem.CLIPPED_AUDIO);
			}
		}

		@Override
		public String toString() {
			return String.format("audio[%d ms, peak %.3f, rms %.3f, clipping %.4f]",
					received.toMillis(), peakLevel, rmsLevel, clipping);
		}
	}



	/** The connection to the test peer. */
	public static final class Network {

		private final Duration connectTime;

		private final Duration roundTripTime;

		private final String candidateType;

		private final boolean relayed;

		private final Throwable failure;


		Network(Duration connectTime, Duration roundTripTime, String candidateType, boolean relayed,
				Throwable failure) {
			this.connectTime = connectTime;
			this.roundTripTime = roundTripTime;
			this.candidateType = candidateType;
			this.relayed = relayed;
			this.failure = failure;
		}

		static Network failed(Throwable failure) {
			return new Network(null, null, null, false, failure);
		}

		/**
		 * @return true once the connection came up.
		 */
		public boolean isConnected() {
			return connectTime != null;
		}

		/**
		 * @return how long the connection took to come up, from the creation
		 * of the peer on; null when it did not.
		 */
		public Duration connectTime() {
			return connectTime;
		}

		/**
		 * @return the round-trip time of the connection, as ICE measured it;
		 * null when it is not known.
		 */
		public Duration roundTripTime() {
			return roundTripTime;
		}

		/**
		 * @return the type of the local candidate in use: host, srflx, prflx
		 * or relay; null when it is not known.
		 */
		public String candidateType() {
			return candidateType;
		}

		/**
		 * @return true when the connection goes through a TURN server, on
		 * either side.
		 */
		public boolean isRelayed() {
			return relayed;
		}

		/**
		 * @return why the connection did not come up: a {@link
		 * java.util.concurrent.TimeoutException} when the timeout passed, or
		 * the failure of the peer; null when it came up.
		 */
		public Throwable failure() {
			return failure;
		}

		@Override
		public String toString() {
			if (!isConnected()) {
				return "network[not connected: " + failure + "]";
			}

			return "network[" + candidateType + (relayed ? ", relayed" : "") + ", connected in "
					+ connectTime.toMillis() + " ms"
					+ (roundTripTime != null ? ", rtt " + roundTripTime.toMillis() + " ms" : "") + "]";
		}
	}
}
