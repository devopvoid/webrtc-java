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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.onvoid.webrtc.media.MediaType;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import dev.onvoid.webrtc.media.audio.codec.AudioCodecInfo;
import dev.onvoid.webrtc.media.audio.codec.BuiltinAudioDecoderFactory;
import dev.onvoid.webrtc.media.audio.codec.BuiltinAudioEncoderFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class AudioCodecFactoryTests {

	@Test
	void builtinCodecs() {
		List<String> encoders = names(new BuiltinAudioEncoderFactory().getSupportedCodecs());
		List<String> decoders = names(new BuiltinAudioDecoderFactory().getSupportedCodecs());

		for (String codec : new String[] { "opus", "g722", "pcmu", "pcma" }) {
			assertTrue(encoders.contains(codec), encoders.toString());
			assertTrue(decoders.contains(codec), decoders.toString());
		}

		AudioCodecInfo opus = new BuiltinAudioEncoderFactory("opus").getSupportedCodecs().get(0);

		assertEquals(48000, opus.getClockRate());
		assertEquals(2, opus.getChannels());
	}

	@Test
	void selectionIsOrderedAndIgnoresCase() {
		BuiltinAudioEncoderFactory factory = new BuiltinAudioEncoderFactory("PCMU", "OPUS", "pcmu");

		assertEquals(Arrays.asList("pcmu", "opus"), names(factory.getSupportedCodecs()));

		assertThrows(UnsupportedOperationException.class,
				() -> factory.getSupportedCodecs().clear());
	}

	@Test
	void unknownCodecThrows() {
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> new BuiltinAudioEncoderFactory("opus", "AAC"));

		assertTrue(error.getMessage().contains("'AAC'"), error.getMessage());
		assertTrue(error.getMessage().contains("opus"), error.getMessage());

		assertThrows(IllegalArgumentException.class, () -> new BuiltinAudioDecoderFactory("iLBC"));
		assertThrows(NullPointerException.class, () -> new BuiltinAudioDecoderFactory((String) null));
	}

	@Test
	void factoryOffersOnlySelectedCodecs() {
		withFactory(new BuiltinAudioEncoderFactory("opus"), new BuiltinAudioDecoderFactory("PCMA"), factory -> {
			List<String> send = capabilityNames(factory.getRtpSenderCapabilities(MediaType.AUDIO));
			List<String> receive = capabilityNames(factory.getRtpReceiverCapabilities(MediaType.AUDIO));

			assertTrue(send.contains("opus"), send.toString());
			assertFalse(send.contains("pcmu") || send.contains("pcma") || send.contains("g722"), send.toString());

			assertTrue(receive.contains("pcma"), receive.toString());
			assertFalse(receive.contains("opus") || receive.contains("pcmu") || receive.contains("g722"), receive.toString());

			// WebRTC still adds DTMF at the selected codecs' clock rates.
			assertTrue(send.contains("telephone-event"), send.toString());
			assertTrue(receive.contains("telephone-event"), receive.toString());
		});
	}

	@Test
	void offerFollowsSelectedOrder() {
		withFactory(new BuiltinAudioEncoderFactory("PCMU", "opus"), new BuiltinAudioDecoderFactory("PCMU", "opus"), factory -> {
			RTCPeerConnection connection = factory.createPeerConnection(new RTCConfiguration(), candidate -> { });

			try {
				connection.addTransceiver(factory.createAudioTrack("audio", new CustomAudioSource()),
						new RTCRtpTransceiverInit());

				TestCreateDescObserver observer = new TestCreateDescObserver();
				connection.createOffer(new RTCOfferOptions(), observer);

				String sdp = observer.get().sdp;
				List<String> codecs = offeredCodecs(sdp);

				assertEquals("pcmu", codecs.get(0), sdp);
				assertEquals("opus", codecs.get(1), sdp);
				assertFalse(codecs.contains("g722") || codecs.contains("pcma"), sdp);
			}
			catch (Exception e) {
				throw new AssertionError(e);
			}
			finally {
				connection.close();
			}
		});
	}

	@Test
	void callUsesSelectedCodec() {
		withFactory(new BuiltinAudioEncoderFactory("PCMA"), new BuiltinAudioDecoderFactory("PCMA"), factory -> {
			try (TestMediaCall call = new TestMediaCall(factory, false, true)) {
				call.negotiate();
				call.awaitConnected();

				List<String> codecs = call.getAudioSender().getParameters().codecs.stream()
						.map(codec -> codec.codecName.toLowerCase(Locale.ROOT))
						.collect(Collectors.toList());

				assertEquals("pcma", codecs.get(0), codecs.toString());
				assertFalse(codecs.contains("opus"), codecs.toString());
			}
			catch (Exception e) {
				throw new AssertionError(e);
			}
		});
	}

	private static void withFactory(BuiltinAudioEncoderFactory encoders,
			BuiltinAudioDecoderFactory decoders, Consumer<PeerConnectionFactory> test) {
		AudioDeviceModule audioModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
		PeerConnectionFactory factory = PeerConnectionFactory.builder()
				.setAudioDeviceModule(audioModule)
				.setAudioEncoderFactory(encoders)
				.setAudioDecoderFactory(decoders)
				.build();

		try {
			test.accept(factory);
		}
		finally {
			factory.dispose();
			audioModule.dispose();
		}
	}

	private static List<String> names(List<AudioCodecInfo> codecs) {
		return codecs.stream()
				.map(codec -> codec.getName().toLowerCase(Locale.ROOT))
				.distinct()
				.collect(Collectors.toList());
	}

	private static List<String> capabilityNames(RTCRtpCapabilities capabilities) {
		return capabilities.getCodecs().stream()
				.map(codec -> codec.getName().toLowerCase(Locale.ROOT))
				.collect(Collectors.toList());
	}

	/**
	 * Returns the codec names of the audio media section, in payload order.
	 */
	private static List<String> offeredCodecs(String sdp) {
		List<String> payloadTypes = new ArrayList<>();
		List<String> codecs = new ArrayList<>();

		for (String line : sdp.split("\r?\n")) {
			if (line.startsWith("m=audio ")) {
				String[] parts = line.split(" ");
				payloadTypes.addAll(Arrays.asList(parts).subList(3, parts.length));
			}
		}
		for (String payloadType : payloadTypes) {
			for (String line : sdp.split("\r?\n")) {
				if (line.startsWith("a=rtpmap:" + payloadType + " ")) {
					String name = line.substring(line.indexOf(' ') + 1, line.indexOf('/'));
					codecs.add(name.toLowerCase(Locale.ROOT));
				}
			}
		}

		return codecs;
	}
}
