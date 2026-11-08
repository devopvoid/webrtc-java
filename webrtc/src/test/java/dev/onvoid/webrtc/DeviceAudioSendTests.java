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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSink;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;
import dev.onvoid.webrtc.media.audio.ToneAudioDeviceModule;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Audio captured by the factory's audio device module reaches the senders
 * through the track of a device source, like the audio of every other local
 * source. The module here records a tone in place of a microphone.
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
	 * A call that sends the track of a device source from the caller to the
	 * callee, both on a factory whose audio device module records a tone.
	 */
	private static class DeviceCall implements AutoCloseable {

		final ToneAudioDeviceModule audioModule;

		final PeerConnectionFactory factory;

		final AudioTrackSource source;

		final AudioTrack track;

		final TestPeerConnection caller;

		final TestPeerConnection callee;

		final RTCRtpSender sender;


		DeviceCall(AudioOptions options) {
			audioModule = new ToneAudioDeviceModule(440);
			factory = new PeerConnectionFactory(audioModule);
			source = factory.createAudioSource(options);
			track = factory.createAudioTrack("microphone", source);

			caller = new TestPeerConnection(factory);
			callee = new TestPeerConnection(factory);

			caller.setRemotePeerConnection(callee);
			callee.setRemotePeerConnection(caller);

			sender = caller.getPeerConnection().addTrack(track, Collections.singletonList("stream"));
		}

		void connect() throws Exception {
			callee.setRemoteDescription(caller.createOffer());
			caller.setRemoteDescription(callee.createAnswer());

			caller.waitUntilConnected();
			callee.waitUntilConnected();
		}

		/**
		 * Returns the callee's receiver of the track, which is the caller's to
		 * dispose.
		 */
		RTCRtpReceiver getReceiver() {
			RTCRtpTransceiver[] transceivers = callee.getPeerConnection().getTransceivers();
			RTCRtpReceiver receiver = transceivers[0].getReceiver();

			for (RTCRtpTransceiver transceiver : transceivers) {
				transceiver.dispose();
			}

			return receiver;
		}

		Map<String, Object> awaitMediaSourceStats(Predicate<Map<String, Object>> condition)
				throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

			while (System.nanoTime() < deadline) {
				CountDownLatch done = new CountDownLatch(1);
				AtomicReference<Map<String, Object>> found = new AtomicReference<>();

				caller.getPeerConnection().getStats(sender, report -> {
					for (RTCStats stats : report.getStats().values()) {
						if (stats.getType() == RTCStatsType.MEDIA_SOURCE && condition.test(stats.getAttributes())) {
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
		public void close() {
			sender.dispose();

			caller.close();
			callee.close();

			track.dispose();
			source.dispose();
			factory.dispose();
			audioModule.dispose();
		}

	}

}
