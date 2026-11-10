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

import static java.util.Objects.requireNonNull;

import java.time.Duration;

import dev.onvoid.webrtc.internal.Timeouts;

/**
 * What {@link WebRtc#checkCall(CallCheck)} checks before a call: whether a
 * video track delivers frames, whether an audio track delivers audio that is
 * neither silent nor clipped, and whether a connection to a test peer comes
 * up. Any of the three may be left out, but not all of them.
 * <pre>{@code
 * LocalCamera camera = rtc.media().camera();
 * LocalAudio microphone = rtc.media().microphone();
 *
 * CallCheck check = CallCheck.builder()
 *         .video(camera)
 *         .audio(microphone)
 *         .network(PeerOptions.builder()
 *                 .iceServer("turn:turn.example.com", "user", "secret")
 *                 .relayOnly(true)
 *                 .signaling(testPeerSignaling)
 *                 .build())
 *         .build();
 *
 * rtc.checkCall(check).thenAccept(result -> {
 *     result.problems().forEach(problem -> System.out.println(problem.reason()));
 * });
 * }</pre>
 * The tracks stay the application's: the check neither closes nor mutes them,
 * so the same tracks can go into the call afterwards.
 */
public final class CallCheck {

	final LocalVideo video;

	final LocalAudio audio;

	final PeerOptions network;

	final Duration duration;

	final Duration timeout;


	private CallCheck(Builder builder) {
		this.video = builder.video;
		this.audio = builder.audio;
		this.network = builder.network;
		this.duration = builder.duration;
		this.timeout = builder.timeout;
	}

	/**
	 * @return a builder of a check.
	 */
	public static Builder builder() {
		return new Builder();
	}



	/** Builds a {@link CallCheck}. */
	public static final class Builder {

		private LocalVideo video;

		private LocalAudio audio;

		private PeerOptions network;

		private Duration duration = Duration.ofSeconds(3);

		private Duration timeout = Duration.ofSeconds(10);


		private Builder() {
		}

		/**
		 * Checks that the track delivers frames that are not all black, as
		 * from a covered lens or a closed privacy shutter. The frames are
		 * those a local preview of the track gets.
		 *
		 * @param track a camera, a screen or any other local video track.
		 *
		 * @return this builder.
		 */
		public Builder video(LocalVideo track) {
			this.video = requireNonNull(track, "track");
			return this;
		}

		/**
		 * Checks that the track delivers audio that is neither silent nor
		 * clipped, so the user should speak during the check. The audio is
		 * measured as the track sends it, after audio processing.
		 * <p>
		 * WebRTC records a microphone only while it sends it, so for a
		 * {@link LocalMedia#microphone() microphone} the check sends the track
		 * to a peer of its own, in this runtime, which it closes again. That
		 * peer drops every encoded frame before it goes out, so nothing is
		 * played back and the user does not hear themselves.
		 *
		 * @param track a microphone or any other local audio track.
		 *
		 * @return this builder.
		 */
		public Builder audio(LocalAudio track) {
			this.audio = requireNonNull(track, "track");
			return this;
		}

		/**
		 * Checks that a connection to a test peer comes up: a peer the
		 * application runs for this, or any other that answers over the
		 * signaling of the options, such as an echo bot. Only a connection
		 * that is made tells that the network lets one through; the local
		 * configuration alone cannot. With {@link
		 * PeerOptions.Builder#relayOnly(boolean) relayOnly} set, a connection
		 * proves that the TURN servers of the options work.
		 * <p>
		 * The check creates a peer with the options, opens a data channel, so
		 * that the peer offers, reads the statistics of the connection and
		 * closes the peer again.
		 *
		 * @param options the ICE servers and the signaling to the test peer.
		 *
		 * @return this builder.
		 */
		public Builder network(PeerOptions options) {
			this.network = requireNonNull(options, "options");
			return this;
		}

		/**
		 * @param duration how long frames and audio are measured, from the
		 *                 first frame or the first audio on; 3 seconds by
		 *                 default.
		 *
		 * @return this builder.
		 */
		public Builder duration(Duration duration) {
			this.duration = Timeouts.requirePositive(duration, "duration");
			return this;
		}

		/**
		 * @param timeout how long the check waits for the first frame, the
		 *                first audio and the connection before it reports
		 *                that there is none; 10 seconds by default.
		 *
		 * @return this builder.
		 */
		public Builder timeout(Duration timeout) {
			this.timeout = Timeouts.requirePositive(timeout, "timeout");
			return this;
		}

		/**
		 * @return the check.
		 *
		 * @throws IllegalStateException if nothing was given to check.
		 */
		public CallCheck build() {
			if (video == null && audio == null && network == null) {
				throw new IllegalStateException("A call check needs video, audio or a network to check");
			}

			return new CallCheck(this);
		}
	}
}
