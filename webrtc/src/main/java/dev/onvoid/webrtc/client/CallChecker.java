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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import dev.onvoid.webrtc.RTCEncodedFrame;
import dev.onvoid.webrtc.client.CallCheckResult.Audio;
import dev.onvoid.webrtc.client.CallCheckResult.Network;
import dev.onvoid.webrtc.client.CallCheckResult.Video;
import dev.onvoid.webrtc.internal.Timeouts;
import dev.onvoid.webrtc.media.audio.AudioTrackSink;
import dev.onvoid.webrtc.media.video.I420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoFrameBuffer;

/**
 * Runs a {@link CallCheck}. Each part waits on the runtime's timer for its
 * first sample, then measures for the duration of the check. Frames are
 * measured on the thread of a preview subscription, audio on the thread that
 * delivers it, where nothing waits for more than the few field updates under
 * a part's lock. The peers the check creates are closed before its result
 * goes out.
 */
final class CallChecker {

	/** A 16-bit sample at 99% of full scale or above counts as clipped. */
	private static final int CLIPPED_SAMPLE = 32440;

	private static final boolean BIG_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN;

	private final WebRtc rtc;

	private final CallCheck check;

	/** The peers the check created. */
	private final List<PeerImpl> peers = new ArrayList<>();


	private CallChecker(WebRtc rtc, CallCheck check) {
		this.rtc = rtc;
		this.check = check;
	}

	/**
	 * Starts the check.
	 *
	 * @return a future that completes with the result once every part is
	 * done and the check's peers are closed, or fails when the runtime closes
	 * first.
	 *
	 * @throws ConfigurationException if WebRTC refuses the network options.
	 * @throws PeerClosedException    if the runtime is closed.
	 */
	static CompletableFuture<CallCheckResult> start(WebRtc rtc, CallCheck check) {
		LocalVideoImpl video = check.video != null ? checkTrack(rtc, check.video, LocalVideoImpl.class) : null;
		LocalAudioImpl audio = check.audio != null ? checkTrack(rtc, check.audio, LocalAudioImpl.class) : null;

		return new CallChecker(rtc, check).start(video, audio);
	}

	private CompletableFuture<CallCheckResult> start(LocalVideoImpl videoTrack, LocalAudioImpl audioTrack) {
		CompletableFuture<Network> network = CompletableFuture.completedFuture(null);
		CompletableFuture<Video> video = CompletableFuture.completedFuture(null);
		CompletableFuture<Audio> audio = CompletableFuture.completedFuture(null);

		try {
			// First, since only its options can be refused.
			if (check.network != null) {
				network = checkNetwork();
			}
			if (audioTrack != null) {
				audio = new AudioPart(audioTrack).begin();
			}
			if (videoTrack != null) {
				video = new VideoPart(videoTrack).begin();
			}
		}
		catch (RuntimeException | Error e) {
			closePeers();
			throw e;
		}

		CompletableFuture<Network> networkDone = network;
		CompletableFuture<Video> videoDone = video;
		CompletableFuture<Audio> audioDone = audio;
		CompletableFuture<CallCheckResult> result = new CompletableFuture<>();

		CompletableFuture.allOf(network, video, audio)
				.whenComplete((ignored, error) -> closePeers().whenComplete((closed, closeError) -> {
					if (error != null) {
						result.completeExceptionally(PeerImpl.unwrap(error));
					}
					else if (rtc.isClosing()) {
						// What the parts found then may be only the runtime closing.
						result.completeExceptionally(
								new PeerClosedException("The runtime closed before the call check completed"));
					}
					else {
						result.complete(new CallCheckResult(videoDone.join(), audioDone.join(),
								networkDone.join()));
					}
				}));

		return result;
	}

	private CompletableFuture<Network> checkNetwork() {
		long created = rtc.clock.getAsLong();
		PeerImpl peer = createPeer(check.network);
		CompletableFuture<Network> result = new CompletableFuture<>();

		// Something to negotiate, so that the peer offers.
		peer.createChannel("call-check");

		ScheduledFuture<?> deadline = schedule(() -> result.complete(Network.failed(
				new TimeoutException("No connection to the test peer within " + check.timeout))), result);

		peer.whenConnected().whenComplete((connected, error) -> {
			if (error != null) {
				result.complete(Network.failed(PeerImpl.unwrap(error)));
				return;
			}

			Duration connectTime = Duration.ofNanos(rtc.clock.getAsLong() - created);

			peer.stats().whenComplete((stats, statsError) -> {
				if (stats == null) {
					result.complete(new Network(connectTime, null, null, false, null));
					return;
				}

				TransportStats transport = stats.transport();

				result.complete(new Network(connectTime, stats.roundTripTime(), stats.candidateType(),
						transport != null && transport.relayed(), null));
			});
		});

		result.whenComplete((value, error) -> {
			if (deadline != null) {
				deadline.cancel(false);
			}
			peer.close();
		});

		return result;
	}

	private PeerImpl createPeer(PeerOptions options) {
		PeerImpl peer = (PeerImpl) rtc.createPeer(options);

		synchronized (peers) {
			peers.add(peer);
		}

		return peer;
	}

	/**
	 * Closes the peers of the check.
	 *
	 * @return a future that completes once they are closed, failed or not.
	 */
	private CompletableFuture<Void> closePeers() {
		List<CompletableFuture<Void>> closing = new ArrayList<>();

		synchronized (peers) {
			for (PeerImpl peer : peers) {
				closing.add(peer.closeInternal().exceptionally(error -> null));
			}
		}

		return CompletableFuture.allOf(closing.toArray(new CompletableFuture[0]));
	}

	/**
	 * Runs the task after the delay, on the runtime's timer.
	 *
	 * @return the scheduled task, or null when the timer is shut down, in
	 * which case the future is failed.
	 */
	private ScheduledFuture<?> schedule(Runnable task, CompletableFuture<?> future) {
		return schedule(task, check.timeout, future);
	}

	private ScheduledFuture<?> schedule(Runnable task, Duration delay, CompletableFuture<?> future) {
		try {
			return rtc.timer.schedule(task, Timeouts.toMillis(delay), TimeUnit.MILLISECONDS);
		}
		catch (RejectedExecutionException e) {
			future.completeExceptionally(new PeerClosedException("The runtime is closed"));
			return null;
		}
	}

	private static <T extends LocalTrackImpl> T checkTrack(WebRtc rtc, LocalTrack track, Class<T> type) {
		if (!type.isInstance(track) || ((LocalTrackImpl) track).rtc != rtc) {
			throw new IllegalArgumentException("The track was not created by this runtime");
		}

		return type.cast(track);
	}

	/**
	 * @return the average luma of a grid of pixels, from 0 to 1.
	 */
	static double brightness(VideoFrameBuffer buffer) {
		I420Buffer i420 = buffer.toI420();

		try {
			ByteBuffer luma = i420.getDataY();
			int stride = i420.getStrideY();
			int width = i420.getWidth();
			int height = i420.getHeight();
			int stepX = Math.max(1, width / 32);
			int stepY = Math.max(1, height / 32);
			long sum = 0;
			int count = 0;

			for (int y = stepY / 2; y < height; y += stepY) {
				for (int x = stepX / 2; x < width; x += stepX) {
					sum += luma.get(y * stride + x) & 0xFF;
					count++;
				}
			}

			return count == 0 ? 0 : sum / (255.0 * count);
		}
		finally {
			// An I420 buffer is its own I420 view, without a reference of its own.
			if (i420 != buffer) {
				i420.release();
			}
		}
	}



	/**
	 * A part that waits for its first sample until the timeout, then measures
	 * for the duration. It finishes once, whichever comes first: the end of
	 * the measurement, the timeout, or the end of its track.
	 */
	private abstract class Part<R> {

		final CompletableFuture<R> result = new CompletableFuture<>();

		// Guarded by this.

		private ScheduledFuture<?> deadline;

		private boolean attached;

		private boolean measuring;

		private boolean finished;


		/**
		 * Waits for the first sample, then attaches to the track.
		 *
		 * @return the future of the result.
		 */
		CompletableFuture<R> begin() {
			ScheduledFuture<?> timeout = schedule(this::finish, result);

			if (timeout == null) {
				return result;
			}

			synchronized (this) {
				deadline = timeout;
			}

			try {
				attach();
			}
			catch (RuntimeException | Error e) {
				synchronized (this) {
					finished = true;
				}
				timeout.cancel(false);
				detach();
				throw e;
			}

			boolean late;

			synchronized (this) {
				attached = true;
				late = finished;
			}

			// Finished while it attached, by its track's end, so that the
			// finish found nothing to detach.
			if (late) {
				detach();
			}

			return result;
		}

		/**
		 * Called under the lock by a sample before it is counted.
		 *
		 * @return false once the part has finished, when the sample does not
		 * count.
		 */
		final boolean counts() {
			return !finished;
		}

		/**
		 * Starts the measurement once the first sample was counted, so that a
		 * measurement that ends at once has it. Later calls do nothing.
		 */
		final void sampled() {
			ScheduledFuture<?> timeout;

			synchronized (this) {
				if (finished || measuring) {
					return;
				}

				measuring = true;
				timeout = deadline;
			}

			if (timeout != null) {
				timeout.cancel(false);
			}

			ScheduledFuture<?> end = schedule(this::finish, check.duration, result);

			synchronized (this) {
				if (finished) {
					if (end != null) {
						end.cancel(false);
					}
				}
				else {
					deadline = end;
				}
			}
		}

		final void finish() {
			ScheduledFuture<?> pending;
			boolean detaches;

			synchronized (this) {
				if (finished) {
					return;
				}

				finished = true;
				pending = deadline;
				detaches = attached;
			}

			if (pending != null) {
				pending.cancel(false);
			}

			if (detaches) {
				try {
					detach();
				}
				catch (Throwable e) {
					rtc.reportError(e);
				}
			}

			R value;

			synchronized (this) {
				value = measured();
			}

			result.complete(value);
		}

		/** Starts to take samples from the track. */
		abstract void attach();

		/** Stops taking samples, once, without the lock. */
		abstract void detach();

		/** Called once, under the lock, after the last sample that counts. */
		abstract R measured();
	}



	private final class VideoPart extends Part<Video> implements Consumer<VideoFrame> {

		private final LocalVideoImpl track;

		private volatile Subscription frameSubscription;

		private volatile Subscription endedSubscription;

		// Guarded by this.

		private int frames;

		private long firstNanos;

		private long lastNanos;

		private int width;

		private int height;

		private double brightest;


		VideoPart(LocalVideoImpl track) {
			this.track = track;
		}

		@Override
		void attach() {
			frameSubscription = track.onFrame(this);
			endedSubscription = track.onEnded(this::finish);
		}

		@Override
		void detach() {
			closeQuietly(frameSubscription);
			closeQuietly(endedSubscription);
		}

		@Override
		public void accept(VideoFrame frame) {
			long now = rtc.clock.getAsLong();
			double brightness = brightness(frame.buffer);

			synchronized (this) {
				if (!counts()) {
					return;
				}
				if (frames == 0) {
					firstNanos = now;
				}

				frames++;
				lastNanos = now;
				width = frame.buffer.getWidth();
				height = frame.buffer.getHeight();
				brightest = Math.max(brightest, brightness);
			}

			sampled();
		}

		@Override
		Video measured() {
			double rate = frames > 1 && lastNanos > firstNanos
					? (frames - 1) * 1e9 / (lastNanos - firstNanos)
					: 0;

			return new Video(frames, rate, width, height, brightest);
		}
	}



	private final class AudioPart extends Part<Audio> implements AudioTrackSink {

		private final LocalAudioImpl track;

		private volatile Subscription pcmSubscription;

		private volatile Subscription endedSubscription;

		/** The peers that make WebRTC record a microphone, if it is one. */
		private final List<PeerImpl> recording = new ArrayList<>();

		// Guarded by this.

		private long receivedNanos;

		private long samples;

		private long clipped;

		private int peak;

		private double sumOfSquares;


		AudioPart(LocalAudioImpl track) {
			this.track = track;
		}

		@Override
		void attach() {
			if (track.isMicrophone() && !track.isEnded()) {
				// WebRTC records only while it sends a microphone. The sender
				// drops every frame, so nothing is played back.
				SignalingPair link = Signaling.loopback();
				PeerImpl sender = createPeer(PeerOptions.builder()
						.name("call-check-audio")
						.signaling(link.first())
						.sendTransform(RTCEncodedFrame::drop)
						.build());

				recording.add(sender);
				recording.add(createPeer(PeerOptions.builder()
						.name("call-check-audio-receiver")
						.signaling(link.second())
						.polite(true)
						.build()));

				sender.addTrack(track);
			}

			pcmSubscription = track.onPcm(this);
			endedSubscription = track.onEnded(this::finish);
		}

		@Override
		void detach() {
			closeQuietly(pcmSubscription);
			closeQuietly(endedSubscription);

			// Stops the recording now, rather than once the other parts are
			// done; the check waits for them to close before its result.
			for (PeerImpl peer : recording) {
				peer.close();
			}
		}

		@Override
		public void onData(byte[] data, int bitsPerSample, int sampleRate, int channels, int frames) {
			if (bitsPerSample != 16 || sampleRate <= 0 || channels <= 0) {
				return;
			}

			int count = Math.min(frames * channels, data.length / 2);
			int chunkPeak = 0;
			long chunkClipped = 0;
			double chunkSquares = 0;

			for (int i = 0; i < count; i++) {
				int low = data[2 * i] & 0xFF;
				int high = data[2 * i + 1];
				int sample = BIG_ENDIAN ? (short) ((low << 8) | (high & 0xFF)) : (short) ((high << 8) | low);
				int magnitude = Math.abs(sample);

				chunkPeak = Math.max(chunkPeak, magnitude);
				chunkSquares += (double) sample * sample;

				if (magnitude >= CLIPPED_SAMPLE) {
					chunkClipped++;
				}
			}

			synchronized (this) {
				if (!counts()) {
					return;
				}

				receivedNanos += frames * 1_000_000_000L / sampleRate;
				samples += count;
				clipped += chunkClipped;
				peak = Math.max(peak, chunkPeak);
				sumOfSquares += chunkSquares;
			}

			sampled();
		}

		@Override
		Audio measured() {
			double rms = samples > 0 ? Math.sqrt(sumOfSquares / samples) / 32768 : 0;
			double clipping = samples > 0 ? (double) clipped / samples : 0;

			return new Audio(Duration.ofNanos(receivedNanos), Math.min(1, peak / 32768.0), rms, clipping);
		}
	}

	private static void closeQuietly(Subscription subscription) {
		if (subscription != null) {
			subscription.close();
		}
	}
}
