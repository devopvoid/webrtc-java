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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.onvoid.webrtc.RTCRtpCapabilities;
import dev.onvoid.webrtc.RTCRtpCodecCapability;
import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.MediaType;

/**
 * The codecs a peer negotiates, for the kinds of media a session named codecs
 * of. Confined to the control executor.
 */
final class CodecPreferences {

	/**
	 * The codecs that go along with those a session names: retransmission,
	 * redundancy, FEC, comfort noise and DTMF, which serve the media codecs.
	 */
	private static final Set<String> COMPANION_CODECS = Set.of("rtx", "red", "ulpfec", "flexfec-03", "cn",
			"telephone-event");

	private final PeerImpl peer;

	/** The codecs each kind of media is negotiated with, in order. */
	private final Map<MediaKind, List<RTCRtpCodecCapability>> codecs = new EnumMap<>(MediaKind.class);


	CodecPreferences(PeerImpl peer) {
		this.peer = peer;
	}

	/**
	 * Negotiates only the codecs of the given names, in that order, for each
	 * kind of media given, together with the companion codecs WebRTC pairs
	 * with them, and sets them on the transceivers there are.
	 *
	 * @param names the codec names of each kind, such as H264 or opus.
	 *
	 * @throws ConfigurationException if WebRTC has no codec of a name.
	 */
	void prefer(Map<MediaKind, List<String>> names) {
		for (Map.Entry<MediaKind, List<String>> entry : names.entrySet()) {
			codecs.put(entry.getKey(), named(entry.getKey(), entry.getValue()));
		}

		apply();
	}

	/**
	 * Sets the preferred codecs on every transceiver that has not stopped.
	 */
	void apply() {
		if (codecs.isEmpty()) {
			return;
		}

		apply(peer.connection().getTransceivers(), codecs);
	}

	/** Takes ownership of every wrapper, including those not reached on failure. */
	static void apply(RTCRtpTransceiver[] transceivers, Map<MediaKind, List<RTCRtpCodecCapability>> codecs) {
		try {
			for (RTCRtpTransceiver transceiver : transceivers) {
				if (transceiver.stopped()) {
					continue;
				}

				MediaKind kind;
				try (RTCRtpReceiver receiver = transceiver.getReceiver();
						MediaStreamTrack track = receiver.getTrack()) {
					kind = MediaStreamTrack.AUDIO_TRACK_KIND.equals(track.getKind())
							? MediaKind.AUDIO
							: MediaKind.VIDEO;
				}

				List<RTCRtpCodecCapability> preferred = codecs.get(kind);

				if (preferred != null) {
					transceiver.setCodecPreferences(preferred);
				}
			}
		}
		finally {
			for (RTCRtpTransceiver transceiver : transceivers) {
				transceiver.dispose();
			}
		}
	}

	/**
	 * Finds the codecs of the given names that WebRTC sends or receives,
	 * followed by the companion codecs.
	 *
	 * @throws ConfigurationException if WebRTC has no codec of a name.
	 */
	private List<RTCRtpCodecCapability> named(MediaKind kind, List<String> names) {
		MediaType type = kind == MediaKind.AUDIO ? MediaType.AUDIO : MediaType.VIDEO;
		// Those WebRTC sends and receives, each once. WebRTC tells codecs
		// apart as this key does, not by their scalability modes, in which
		// the two lists differ; one codec twice would be offered twice.
		Map<List<Object>, RTCRtpCodecCapability> available = new LinkedHashMap<>();

		for (RTCRtpCapabilities capabilities : List.of(peer.rtc.factory.getRtpSenderCapabilities(type),
				peer.rtc.factory.getRtpReceiverCapabilities(type))) {
			for (RTCRtpCodecCapability codec : capabilities.getCodecs()) {
				available.putIfAbsent(Arrays.asList(codec.getMimeType().toLowerCase(Locale.ROOT),
						codec.getClockRate(), codec.getSDPFmtp()), codec);
			}
		}

		List<RTCRtpCodecCapability> named = new ArrayList<>();

		for (String name : names) {
			boolean found = false;

			for (RTCRtpCodecCapability codec : available.values()) {
				if ((codec.getName().equalsIgnoreCase(name) || codec.getMimeType().equalsIgnoreCase(name))
						&& !named.contains(codec)) {
					named.add(codec);
					found = true;
				}
			}

			if (!found) {
				Set<String> known = new LinkedHashSet<>();
				available.values().forEach(codec -> known.add(codec.getName()));

				throw new ConfigurationException("WebRTC has no " + kind.name().toLowerCase(Locale.ROOT)
						+ " codec " + name + " for " + peer.name + ", only " + known);
			}
		}

		for (RTCRtpCodecCapability codec : available.values()) {
			if (COMPANION_CODECS.contains(codec.getName().toLowerCase(Locale.ROOT)) && !named.contains(codec)) {
				named.add(codec);
			}
		}

		return named;
	}
}
