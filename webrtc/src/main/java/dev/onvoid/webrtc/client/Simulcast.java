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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.onvoid.webrtc.RTCRtpEncodingParameters;

/**
 * The layers a video track is sent in, as simulcast (RFC 8853): the same video
 * encoded several times, each layer with an RTP stream id (rid) and encoding
 * values of its own, so that a server that forwards the stream can give each
 * receiver the layer its connection takes.
 * <pre>{@code
 * Sender sender = peer.addTrack(camera, Simulcast.builder()
 *         .layer("q", Encoding.builder().scaleDownBy(4).maxBitrate(150_000).build())
 *         .layer("h", Encoding.builder().scaleDownBy(2).maxBitrate(500_000).build())
 *         .layer("f", Encoding.builder().maxBitrate(1_500_000).build())
 *         .build());
 *
 * sender.configure("f", Encoding.builder().active(false).build());
 * }</pre>
 * The rids are fixed once the track is added. A remote peer that takes no
 * simulcast gets the first layer only.
 */
public final class Simulcast {

	/** The most layers WebRTC sends (kMaxSimulcastStreams). */
	static final int MAX_LAYERS = 3;

	/** The longest rid WebRTC takes. */
	private static final int MAX_RID_LENGTH = 16;

	private final Map<String, Encoding> layers;


	private Simulcast(Map<String, Encoding> layers) {
		this.layers = Collections.unmodifiableMap(new LinkedHashMap<>(layers));
	}

	/**
	 * @return three layers in full, half and quarter resolution, with the rids
	 * {@code f}, {@code h} and {@code q}, the largest first.
	 */
	public static Simulcast standard() {
		return builder()
				.layer("f", Encoding.builder().scaleDownBy(1).build())
				.layer("h", Encoding.builder().scaleDownBy(2).build())
				.layer("q", Encoding.builder().scaleDownBy(4).build())
				.build();
	}

	/**
	 * @return a builder without layers.
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * @return the rids of the layers, in their order.
	 */
	public List<String> rids() {
		return List.copyOf(layers.keySet());
	}

	/**
	 * @return the encodings to create the transceiver with, one per layer.
	 */
	List<RTCRtpEncodingParameters> encodings() {
		List<RTCRtpEncodingParameters> encodings = new ArrayList<>();

		layers.forEach((rid, layer) -> {
			RTCRtpEncodingParameters parameters = new RTCRtpEncodingParameters();
			parameters.rid = rid;
			parameters.active = layer.active;
			parameters.maxBitrate = layer.maxBitrate;
			parameters.maxFramerate = layer.maxFrameRate;
			parameters.scaleResolutionDownBy = layer.scaleDownBy;

			encodings.add(parameters);
		});

		return encodings;
	}

	@Override
	public String toString() {
		return "Simulcast" + rids();
	}



	/** Builds a {@link Simulcast}. */
	public static final class Builder {

		private final Map<String, Encoding> layers = new LinkedHashMap<>();


		private Builder() {
		}

		/**
		 * Adds a layer after those added before.
		 *
		 * @param rid      the layer's RTP stream id: 1 to 16 letters and
		 *                 digits, unique among the layers.
		 * @param encoding the layer's values; without a {@link
		 *                 Encoding.Builder#scaleDownBy factor} it keeps the
		 *                 resolution of the track.
		 *
		 * @return this builder.
		 *
		 * @throws IllegalArgumentException if the rid is not one WebRTC
		 *                                  takes, or is taken already.
		 */
		public Builder layer(String rid, Encoding encoding) {
			requireNonNull(rid, "rid");
			requireNonNull(encoding, "encoding");

			if (!isLegalRid(rid)) {
				throw new IllegalArgumentException(
						"A rid has 1 to " + MAX_RID_LENGTH + " letters and digits, got \"" + rid + "\"");
			}
			if (layers.containsKey(rid)) {
				throw new IllegalArgumentException("The rid " + rid + " is taken already");
			}

			layers.put(rid, encoding);
			return this;
		}

		/**
		 * @return the layers.
		 *
		 * @throws IllegalArgumentException if there are fewer than two layers,
		 *                                  or more than three, the most WebRTC
		 *                                  sends.
		 */
		public Simulcast build() {
			if (layers.size() < 2 || layers.size() > MAX_LAYERS) {
				throw new IllegalArgumentException(
						"Simulcast takes two or three layers, got " + layers.size());
			}

			return new Simulcast(layers);
		}

		/**
		 * As WebRTC checks it (IsLegalRsidName): ASCII letters and digits.
		 */
		private static boolean isLegalRid(String rid) {
			if (rid.isEmpty() || rid.length() > MAX_RID_LENGTH) {
				return false;
			}
			for (int i = 0; i < rid.length(); i++) {
				char c = rid.charAt(i);

				if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z')) {
					return false;
				}
			}
			return true;
		}
	}

}
