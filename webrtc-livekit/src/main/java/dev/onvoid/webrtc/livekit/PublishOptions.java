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

package dev.onvoid.webrtc.livekit;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;

import dev.onvoid.webrtc.client.Encoding;
import dev.onvoid.webrtc.client.Simulcast;

/**
 * How a track is published.
 * <pre>{@code
 * room.publish(camera, PublishOptions.builder()
 *         .source(TrackSource.CAMERA)
 *         .videoSize(1280, 720)
 *         .simulcast(true)
 *         .build());
 * }</pre>
 */
public final class PublishOptions {

	final TrackSource source;

	final String name;

	final int width;

	final int height;

	final boolean simulcast;


	private PublishOptions(Builder builder) {
		this.source = builder.source;
		this.name = builder.name;
		this.width = builder.width;
		this.height = builder.height;
		this.simulcast = builder.simulcast;
	}

	/**
	 * @return a builder of a track of unknown source, without simulcast.
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * The layers a video of this size is sent in, as LiveKit's own clients
	 * send them: from the smallest up, with the rids {@code q}, {@code h} and
	 * {@code f}, and the bitrates of LiveKit's presets.
	 *
	 * @return the layers, none if the video is too small for simulcast.
	 */
	List<Layer> layers() {
		List<Layer> layers = new ArrayList<>();

		if (!simulcast || height < 360) {
			return layers;
		}

		int[] scales = height >= 720 ? new int[] { 4, 2, 1 } : new int[] { 2, 1 };
		for (int i = 0; i < scales.length; i++) {
			int layerWidth = ceilDiv(width, scales[i]);
			int layerHeight = ceilDiv(height, scales[i]);

			layers.add(new Layer(VideoQuality.values()[i], scales[i], layerWidth, layerHeight,
					bitrate(layerHeight)));
		}

		return layers;
	}

	/**
	 * @return the layers as the client API sends them, or null without
	 * simulcast.
	 */
	Simulcast simulcastLayers() {
		List<Layer> layers = layers();
		if (layers.isEmpty()) {
			return null;
		}

		Simulcast.Builder builder = Simulcast.builder();
		for (Layer layer : layers) {
			builder.layer(layer.quality.rid(), Encoding.builder()
					.scaleDownBy(layer.scale)
					.maxBitrate(layer.bitrate)
					.build());
		}
		return builder.build();
	}

	/** The bitrate of LiveKit's video preset for this height. */
	private static int bitrate(int height) {
		if (height >= 1080) {
			return 3_000_000;
		}
		if (height >= 720) {
			return 1_700_000;
		}
		if (height >= 540) {
			return 800_000;
		}
		if (height >= 360) {
			return 450_000;
		}
		return 160_000;
	}

	private static int ceilDiv(int value, int divisor) {
		return (value + divisor - 1) / divisor;
	}



	static final class Layer {

		final VideoQuality quality;

		final int scale;

		final int width;

		final int height;

		final int bitrate;


		Layer(VideoQuality quality, int scale, int width, int height, int bitrate) {
			this.quality = quality;
			this.scale = scale;
			this.width = width;
			this.height = height;
			this.bitrate = bitrate;
		}
	}



	/** Builds {@link PublishOptions}. */
	public static final class Builder {

		private TrackSource source = TrackSource.UNKNOWN;

		private String name;

		private int width;

		private int height;

		private boolean simulcast;


		private Builder() {
		}

		/**
		 * @param source where the track comes from.
		 *
		 * @return this builder.
		 */
		public Builder source(TrackSource source) {
			this.source = requireNonNull(source, "source");
			return this;
		}

		/**
		 * @param name the name other participants see, the track's id by
		 *             default.
		 *
		 * @return this builder.
		 */
		public Builder name(String name) {
			this.name = requireNonNull(name, "name");
			return this;
		}

		/**
		 * @param width  the width the video is captured in.
		 * @param height the height the video is captured in.
		 *
		 * @return this builder.
		 */
		public Builder videoSize(int width, int height) {
			if (width <= 0 || height <= 0) {
				throw new IllegalArgumentException("The size must be positive, got " + width + "x" + height);
			}
			this.width = width;
			this.height = height;
			return this;
		}

		/**
		 * Sends a video track in simulcast layers, so that the server can
		 * forward each subscriber the one its connection and view take: three
		 * from 720 lines up, two from 360 lines up, none below. Needs the
		 * {@link #videoSize video size}.
		 *
		 * @param simulcast true to send the layers.
		 *
		 * @return this builder.
		 */
		public Builder simulcast(boolean simulcast) {
			this.simulcast = simulcast;
			return this;
		}

		/**
		 * @return the options.
		 *
		 * @throws IllegalStateException if simulcast lacks the video size.
		 */
		public PublishOptions build() {
			if (simulcast && height == 0) {
				throw new IllegalStateException("Simulcast needs the video size");
			}
			return new PublishOptions(this);
		}
	}

}
