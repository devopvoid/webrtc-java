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

package dev.onvoid.webrtc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSink;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import dev.onvoid.webrtc.media.audio.ToneAudioDeviceModule;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Audio captured by the factory's audio device module reaches the senders
 * through the track of a device source, like the audio of every other local
 * source, so a factory sends it beside pushed audio. The module here records a
 * tone in place of a microphone.
 */
class DeviceAudioSendTests extends TestBase {

	private static final long TIMEOUT_SECONDS = 10;

	/** Well above the noise of a codec, well below the recorded tone. */
	private static final int AUDIBLE = 1000;


	@Test
	void localSinkReceivesRecording() throws Exception {
		try (DeviceCall call = new DeviceCall(new AudioOptions())) {
			CountDownLatch audible = new CountDownLatch(5);
			AudioTrackSink sink = (data, bits, rate, channels, frames) -> {
				if (isAudible(data, bits, channels, frames)) {
					audible.countDown();
				}
			};

			// WebRTC's own source of device-captured audio never fed a local
			// sink: the recording reached its senders past the track.
			call.track.addSink(sink);

			try {
				call.connect();

				assertTrue(audible.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "No recorded audio at the local sink");
			}
			finally {
				call.track.removeSink(sink);
			}
		}
	}

	@Test
	void calleeReceivesRecording() throws Exception {
		try (DeviceCall call = new DeviceCall(new AudioOptions())) {
			CountDownLatch audible = new CountDownLatch(5);

			call.connect();

			RTCRtpReceiver receiver = call.getReceiver();
			AudioTrack remote = (AudioTrack) receiver.getTrack();
			AudioTrackSink sink = (data, bits, rate, channels, frames) -> {
				if (isAudible(data, bits, channels, frames)) {
					audible.countDown();
				}
			};

			remote.addSink(sink);

			try {
				assertTrue(audible.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "No recorded audio at the callee");
			}
			finally {
				remote.removeSink(sink);
				receiver.dispose();
			}
		}
	}

	@Test
	void mediaSourceStatsReportLevel() throws Exception {
		try (DeviceCall call = new DeviceCall(new AudioOptions())) {
			call.connect();

			// The level is computed by the send stream from what it is handed,
			// so it shows that the recording reaches the sender.
			Map<String, Object> stats = call.awaitMediaSourceStats(attributes ->
					number(attributes, "audioLevel") > 0 && number(attributes, "totalAudioEnergy") > 0);

			assertNotNull(stats, "The media source reports no audio level");
		}
	}

	@Test
	void echoStatisticsReported() throws Exception {
		AudioOptions options = new AudioOptions();
		options.echoCancellation = true;

		try (DeviceCall call = new DeviceCall(options)) {
			call.connect();

			// The echo canceller reports once it has seen both sides: the
			// recording, run through it by the audio device module's proxy,
			// and the playout of the callee, run through it by WebRTC.
			Map<String, Object> stats = call.awaitMediaSourceStats(attributes ->
					attributes.containsKey("echoReturnLoss"));

			assertNotNull(stats, "The media source reports no echo statistics");
		}
	}

	@Test
	void deviceAndPushedTracksOnOnePeer() throws Exception {
		try (DeviceCall call = new DeviceCall(new AudioOptions(), true, true)) {
			call.connect();
			call.startPushing();

			// The callee's transceivers follow the order of the offer: the
			// microphone first, then the pushed silence.
			RTCRtpReceiver[] receivers = call.getReceivers();
			AudioTrack microphone = (AudioTrack) receivers[0].getTrack();
			AudioTrack pushed = (AudioTrack) receivers[1].getTrack();

			CountDownLatch microphoneAudible = new CountDownLatch(5);
			CountDownLatch pushedReceived = new CountDownLatch(50);
			AtomicBoolean pushedAudible = new AtomicBoolean();

			AudioTrackSink microphoneSink = (data, bits, rate, channels, frames) -> {
				if (isAudible(data, bits, channels, frames)) {
					microphoneAudible.countDown();
				}
			};
			// Recorded audio fed into the pushed track's sender as well would
			// make the silence audible.
			AudioTrackSink pushedSink = (data, bits, rate, channels, frames) -> {
				if (isAudible(data, bits, channels, frames)) {
					pushedAudible.set(true);
				}
				pushedReceived.countDown();
			};

			microphone.addSink(microphoneSink);
			pushed.addSink(pushedSink);

			try {
				assertTrue(microphoneAudible.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
						"No recorded audio at the callee");
				assertTrue(pushedReceived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
						"No pushed audio at the callee");
				assertFalse(pushedAudible.get(), "Recorded audio reached the pushed track");
			}
			finally {
				microphone.removeSink(microphoneSink);
				pushed.removeSink(pushedSink);

				for (RTCRtpReceiver receiver : receivers) {
					receiver.dispose();
				}
			}
		}
	}

	@Test
	void pushedAudioAloneNeverRecords() throws Exception {
		try (DeviceCall call = new DeviceCall(new AudioOptions(), false, true)) {
			call.connect();
			call.startPushing();

			// A send stream runs, so WebRTC asks for a recording, but no device
			// source needs it.
			assertNotNull(call.awaitStats(call.customSender, RTCStatsType.OUTBOUND_RTP,
					attributes -> number(attributes, "packetsSent") > 0), "Nothing was sent");
			assertFalse(call.audioModule.isRecording(), "The device records for pushed audio");
		}
	}

	@Test
	void recordingFollowsSetAudioRecording() throws Exception {
		try (DeviceCall call = new DeviceCall(new AudioOptions(), true, false)) {
			call.connect();

			assertTrue(await(call.audioModule::isRecording), "The device does not record");

			call.caller.getPeerConnection().setAudioRecording(false);
			assertTrue(await(() -> !call.audioModule.isRecording()), "Recording was not switched off");

			call.caller.getPeerConnection().setAudioRecording(true);
			assertTrue(await(call.audioModule::isRecording), "Recording was not switched on again");
		}
	}

	@Test
	void recordingStopsWithLastDeviceTrack() throws Exception {
		try (DeviceCall call = new DeviceCall(new AudioOptions(), true, true)) {
			call.connect();
			call.startPushing();

			assertTrue(await(call.audioModule::isRecording), "The device does not record");

			// The pushed track keeps a send stream, and WebRTC's request for a
			// recording, alive; the microphone track's sink is gone.
			call.caller.getPeerConnection().removeTrack(call.sender);

			assertTrue(await(() -> !call.audioModule.isRecording()), "The device still records");
		}
	}

	private static boolean await(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return true;
			}

			Thread.sleep(20);
		}

		return condition.getAsBoolean();
	}

	private static boolean isAudible(byte[] data, int bits, int channels, int frames) {
		if (bits != 16) {
			return false;
		}

		ByteBuffer samples = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());
		int count = Math.min(frames * channels, data.length / 2);

		for (int i = 0; i < count; i++) {
			if (Math.abs(samples.getShort(i * 2)) > AUDIBLE) {
				return true;
			}
		}

		return false;
	}

	private static double number(Map<String, Object> attributes, String name) {
		Object value = attributes.get(name);

		return value instanceof Number ? ((Number) value).doubleValue() : 0;
	}



	/**
	 * A call that sends the track of a device source, a track of pushed
	 * silence, or both, from the caller to the callee, all on one factory whose
	 * audio device module records a tone.
	 */
	private static class DeviceCall implements AutoCloseable {

		final ToneAudioDeviceModule audioModule;

		final PeerConnectionFactory factory;

		final AudioTrackSource source;

		final AudioTrack track;

		final TestPeerConnection caller;

		final TestPeerConnection callee;

		final RTCRtpSender sender;

		final CustomAudioSource customSource;

		final AudioTrack customTrack;

		final RTCRtpSender customSender;

		private volatile boolean pushing;

		private Thread pusher;


		DeviceCall(AudioOptions options) {
			this(options, true, false);
		}

		DeviceCall(AudioOptions options, boolean device, boolean pushed) {
			audioModule = new ToneAudioDeviceModule(440);
			factory = new PeerConnectionFactory(audioModule);

			caller = new TestPeerConnection(factory);
			callee = new TestPeerConnection(factory);

			caller.setRemotePeerConnection(callee);
			callee.setRemotePeerConnection(caller);

			if (device) {
				source = factory.createAudioSource(options);
				track = factory.createAudioTrack("microphone", source);
				sender = caller.getPeerConnection().addTrack(track, Collections.singletonList("stream"));
			}
			else {
				source = null;
				track = null;
				sender = null;
			}

			if (pushed) {
				customSource = new CustomAudioSource();
				customTrack = factory.createAudioTrack("pushed", customSource);
				customSender = caller.getPeerConnection().addTrack(customTrack, Collections.singletonList("stream"));
			}
			else {
				customSource = null;
				customTrack = null;
				customSender = null;
			}
		}

		/**
		 * Starts pushing 10 ms of silence every 10 ms into the custom source.
		 */
		void startPushing() {
			pushing = true;
			pusher = new Thread(() -> {
				byte[] silence = new byte[480 * 2];
				long next = System.nanoTime();

				while (pushing) {
					customSource.pushAudio(silence, 16, 48000, 1, 480);

					next += 10_000_000L;

					long wait = next - System.nanoTime();

					if (wait > 0) {
						try {
							TimeUnit.NANOSECONDS.sleep(wait);
						}
						catch (InterruptedException e) {
							return;
						}
					}
				}
			}, "DeviceCall-pusher");
			pusher.setDaemon(true);
			pusher.start();
		}

		void connect() throws Exception {
			callee.setRemoteDescription(caller.createOffer());
			caller.setRemoteDescription(callee.createAnswer());

			caller.waitUntilConnected();
			callee.waitUntilConnected();
		}

		/**
		 * Returns the callee's receiver of the first track, which is the
		 * caller's to dispose.
		 */
		RTCRtpReceiver getReceiver() {
			RTCRtpReceiver[] receivers = getReceivers();

			for (int i = 1; i < receivers.length; i++) {
				receivers[i].dispose();
			}

			return receivers[0];
		}

		/**
		 * Returns the callee's receivers, in the order of the offer, which are
		 * the caller's to dispose.
		 */
		RTCRtpReceiver[] getReceivers() {
			RTCRtpTransceiver[] transceivers = callee.getPeerConnection().getTransceivers();
			RTCRtpReceiver[] receivers = new RTCRtpReceiver[transceivers.length];

			for (int i = 0; i < transceivers.length; i++) {
				receivers[i] = transceivers[i].getReceiver();
				transceivers[i].dispose();
			}

			return receivers;
		}

		Map<String, Object> awaitMediaSourceStats(Predicate<Map<String, Object>> condition)
				throws InterruptedException {
			return awaitStats(sender, RTCStatsType.MEDIA_SOURCE, condition);
		}

		Map<String, Object> awaitStats(RTCRtpSender statsSender, RTCStatsType type,
				Predicate<Map<String, Object>> condition) throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

			while (System.nanoTime() < deadline) {
				CountDownLatch done = new CountDownLatch(1);
				AtomicReference<Map<String, Object>> found = new AtomicReference<>();

				caller.getPeerConnection().getStats(statsSender, report -> {
					for (RTCStats stats : report.getStats().values()) {
						if (stats.getType() == type && condition.test(stats.getAttributes())) {
							found.set(stats.getAttributes());
						}
					}
					done.countDown();
				});

				done.await(5, TimeUnit.SECONDS);

				if (found.get() != null) {
					return found.get();
				}

				Thread.sleep(50);
			}

			return null;
		}

		@Override
		public void close() throws InterruptedException {
			pushing = false;

			if (pusher != null) {
				pusher.join();
			}

			if (sender != null) {
				sender.dispose();
			}
			if (customSender != null) {
				customSender.dispose();
			}

			caller.close();
			callee.close();

			if (track != null) {
				track.dispose();
				source.dispose();
			}
			if (customTrack != null) {
				customTrack.dispose();
				customSource.dispose();
			}

			factory.dispose();
			audioModule.dispose();
		}

	}

}
