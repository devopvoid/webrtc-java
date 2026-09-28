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

package dev.onvoid.webrtc.media.video.codec;

import dev.onvoid.webrtc.media.video.VideoFrame;

import java.util.Arrays;
import java.util.Objects;

/**
 * A video encoder implemented in Java, which a {@link VideoEncoderFactory}
 * hands to WebRTC. This is the way to bring in a codec WebRTC does not have,
 * or a hardware encoder reached through another library.
 * <p>
 * WebRTC calls {@link #initEncode initEncode}, then {@link #encode encode}
 * for each frame and {@link #setRates setRates} whenever the target bitrate
 * changes, and finally {@link #release release}. It may initialize a released
 * encoder again. These calls come from one WebRTC thread at a time, never
 * concurrently. The encoder hands each encoded frame to the {@link Callback}
 * it was initialized with, either from within {@code encode} or later from a
 * thread of its own.
 * <p>
 * Whatever a method throws is logged and treated as {@link
 * VideoCodecStatus#ERROR}.
 *
 * @author Alex Andres
 *
 * @see VideoEncoderFactory
 */
public interface VideoEncoder {

	/**
	 * The settings an encoder is initialized with.
	 */
	class Settings {

		/** The number of CPU cores the encoder may use. */
		public final int numberOfCores;

		/** The frame width in pixels. */
		public final int width;

		/** The frame height in pixels. */
		public final int height;

		/** The initial target bitrate in kbps. */
		public final int startBitrateKbps;

		/** The minimum bitrate in kbps. */
		public final int minBitrateKbps;

		/** The maximum bitrate in kbps. */
		public final int maxBitrateKbps;

		/** The maximum frame rate in frames per second. */
		public final int maxFramerate;

		/** The number of simulcast streams, 0 or 1 without simulcast. */
		public final int numberOfSimulcastStreams;

		/** Whether the encoder may change the resolution on its own. */
		public final boolean automaticResizeOn;

		/** Whether the content is a screen share rather than camera video. */
		public final boolean screenContent;

		/** Whether the encoder is to react to loss notifications. */
		public final boolean lossNotification;


		/**
		 * Creates encoder settings.
		 *
		 * @param numberOfCores            The number of CPU cores to use.
		 * @param width                    The frame width in pixels.
		 * @param height                   The frame height in pixels.
		 * @param startBitrateKbps         The initial bitrate in kbps.
		 * @param minBitrateKbps           The minimum bitrate in kbps.
		 * @param maxBitrateKbps           The maximum bitrate in kbps.
		 * @param maxFramerate             The maximum frame rate.
		 * @param numberOfSimulcastStreams The number of simulcast streams.
		 * @param automaticResizeOn        Whether the encoder may resize.
		 * @param screenContent            Whether the content is a screen.
		 * @param lossNotification         Whether to react to loss
		 *                                 notifications.
		 */
		public Settings(int numberOfCores, int width, int height,
				int startBitrateKbps, int minBitrateKbps, int maxBitrateKbps,
				int maxFramerate, int numberOfSimulcastStreams,
				boolean automaticResizeOn, boolean screenContent,
				boolean lossNotification) {
			this.numberOfCores = numberOfCores;
			this.width = width;
			this.height = height;
			this.startBitrateKbps = startBitrateKbps;
			this.minBitrateKbps = minBitrateKbps;
			this.maxBitrateKbps = maxBitrateKbps;
			this.maxFramerate = maxFramerate;
			this.numberOfSimulcastStreams = numberOfSimulcastStreams;
			this.automaticResizeOn = automaticResizeOn;
			this.screenContent = screenContent;
			this.lossNotification = lossNotification;
		}

		@Override
		public String toString() {
			return String.format("%s [cores=%d, width=%d, height=%d, startBitrateKbps=%d, minBitrateKbps=%d, "
							+ "maxBitrateKbps=%d, maxFramerate=%d, simulcastStreams=%d, automaticResize=%s, "
							+ "screenContent=%s, lossNotification=%s]",
					Settings.class.getSimpleName(), numberOfCores, width, height,
					startBitrateKbps, minBitrateKbps, maxBitrateKbps, maxFramerate,
					numberOfSimulcastStreams, automaticResizeOn, screenContent,
					lossNotification);
		}

	}



	/**
	 * Additional information about a frame to encode.
	 */
	class EncodeInfo {

		/**
		 * The frame type WebRTC asks for, one per simulcast stream. {@link
		 * EncodedImage.FrameType#KEY} asks for a key frame.
		 */
		public final EncodedImage.FrameType[] frameTypes;


		/**
		 * Creates the information about a frame to encode.
		 *
		 * @param frameTypes The frame type asked for, per simulcast stream.
		 */
		public EncodeInfo(EncodedImage.FrameType[] frameTypes) {
			this.frameTypes = Objects.requireNonNull(frameTypes, "Frame types are null");
		}

		/**
		 * @return Whether WebRTC asks for a key frame in any stream.
		 */
		public boolean isKeyFrameRequested() {
			for (EncodedImage.FrameType type : frameTypes) {
				if (type == EncodedImage.FrameType.KEY) {
					return true;
				}
			}

			return false;
		}

		@SuppressWarnings("unused")
		private static EncodeInfo fromNative(int[] frameTypes) {
			EncodedImage.FrameType[] types = new EncodedImage.FrameType[frameTypes.length];

			for (int i = 0; i < frameTypes.length; i++) {
				types[i] = EncodedImage.FrameType.fromNativeIndex(frameTypes[i]);
			}

			return new EncodeInfo(types);
		}

		@Override
		public String toString() {
			return String.format("%s [frameTypes=%s]",
					EncodeInfo.class.getSimpleName(), Arrays.toString(frameTypes));
		}

	}



	/**
	 * The bitrate WebRTC allocates to each spatial and temporal layer.
	 */
	class BitrateAllocation {

		/**
		 * The bitrate in bps, indexed by spatial layer, then temporal layer.
		 */
		public final int[][] bitratesBps;


		/**
		 * Creates a bitrate allocation.
		 *
		 * @param bitratesBps The bitrate in bps per spatial, then temporal
		 *                    layer.
		 */
		public BitrateAllocation(int[][] bitratesBps) {
			this.bitratesBps = Objects.requireNonNull(bitratesBps, "Bitrates are null");
		}

		/**
		 * @return The sum of all layers in bps.
		 */
		public int getSum() {
			int sum = 0;

			for (int[] spatial : bitratesBps) {
				for (int bitrate : spatial) {
					sum += bitrate;
				}
			}

			return sum;
		}

		@Override
		public String toString() {
			return String.format("%s [sum=%d, bitratesBps=%s]",
					BitrateAllocation.class.getSimpleName(), getSum(),
					Arrays.deepToString(bitratesBps));
		}

	}



	/**
	 * The rates an encoder is to aim for.
	 */
	class RateControlParameters {

		/** The target bitrate per layer. */
		public final BitrateAllocation bitrate;

		/** The target frame rate in frames per second. */
		public final double framerateFps;


		/**
		 * Creates rate control parameters.
		 *
		 * @param bitrate      The target bitrate per layer.
		 * @param framerateFps The target frame rate.
		 */
		public RateControlParameters(BitrateAllocation bitrate, double framerateFps) {
			this.bitrate = Objects.requireNonNull(bitrate, "Bitrate is null");
			this.framerateFps = framerateFps;
		}

		@Override
		public String toString() {
			return String.format("%s [bitrate=%s, framerateFps=%s]",
					RateControlParameters.class.getSimpleName(), bitrate,
					framerateFps);
		}

	}



	/**
	 * The quantizer thresholds WebRTC scales the resolution by: above the high
	 * threshold it lowers the resolution, below the low one it raises it.
	 */
	class ScalingSettings {

		/** No quality scaling. */
		public static final ScalingSettings OFF = new ScalingSettings(false, null, null);

		/**
		 * Quality scaling with the thresholds WebRTC uses for VP8, VP9 and
		 * H.264, and none for any other codec.
		 */
		public static final ScalingSettings DEFAULT = new ScalingSettings(true, null, null);

		/** Whether quality scaling is on. */
		public final boolean on;

		/** The low quantizer threshold, or {@code null} for the default. */
		public final Integer low;

		/** The high quantizer threshold, or {@code null} for the default. */
		public final Integer high;


		/**
		 * Creates scaling settings that turn quality scaling on with the given
		 * thresholds.
		 *
		 * @param low  The low quantizer threshold.
		 * @param high The high quantizer threshold.
		 */
		public ScalingSettings(int low, int high) {
			this(true, low, high);
		}

		private ScalingSettings(boolean on, Integer low, Integer high) {
			this.on = on;
			this.low = low;
			this.high = high;
		}

		@Override
		public String toString() {
			return on
					? String.format("[low=%s, high=%s]", low, high)
					: "OFF";
		}

	}



	/**
	 * The bitrate limits of an encoder at one resolution.
	 */
	class ResolutionBitrateLimits {

		/** The resolution, as width times height. */
		public final int frameSizePixels;

		/** The lowest bitrate to start encoding this resolution at, in bps. */
		public final int minStartBitrateBps;

		/** The lowest bitrate for this resolution, in bps. */
		public final int minBitrateBps;

		/** The highest bitrate for this resolution, in bps. */
		public final int maxBitrateBps;


		/**
		 * Creates bitrate limits for one resolution.
		 *
		 * @param frameSizePixels    The resolution, as width times height.
		 * @param minStartBitrateBps The lowest start bitrate in bps.
		 * @param minBitrateBps      The lowest bitrate in bps.
		 * @param maxBitrateBps      The highest bitrate in bps.
		 */
		public ResolutionBitrateLimits(int frameSizePixels, int minStartBitrateBps,
				int minBitrateBps, int maxBitrateBps) {
			this.frameSizePixels = frameSizePixels;
			this.minStartBitrateBps = minStartBitrateBps;
			this.minBitrateBps = minBitrateBps;
			this.maxBitrateBps = maxBitrateBps;
		}

	}



	/**
	 * Properties of an encoder WebRTC takes into account when feeding it.
	 */
	class EncoderInfo {

		/**
		 * The number both frame dimensions have to be divisible by. WebRTC
		 * crops or scales frames to fit.
		 */
		public final int requestedResolutionAlignment;

		/**
		 * Whether the alignment applies to every simulcast layer, rather than
		 * only to the highest.
		 */
		public final boolean applyAlignmentToAllSimulcastLayers;


		/**
		 * Creates encoder properties.
		 *
		 * @param requestedResolutionAlignment       The alignment of frame
		 *                                           dimensions, 1 for none.
		 * @param applyAlignmentToAllSimulcastLayers Whether the alignment
		 *                                           applies to every layer.
		 */
		public EncoderInfo(int requestedResolutionAlignment,
				boolean applyAlignmentToAllSimulcastLayers) {
			if (requestedResolutionAlignment < 1) {
				throw new IllegalArgumentException("Alignment must be at least 1");
			}

			this.requestedResolutionAlignment = requestedResolutionAlignment;
			this.applyAlignmentToAllSimulcastLayers = applyAlignmentToAllSimulcastLayers;
		}

	}



	/**
	 * Receives what an encoder produces.
	 */
	interface Callback {

		/**
		 * Hands an encoded frame to WebRTC, which copies the payload before
		 * returning. May be called from any thread, but frames have to be
		 * handed over in the order they were encoded in. Frames handed over
		 * after the encoder was released are ignored.
		 *
		 * @param image The encoded frame, whose capture time is the timestamp
		 *              of the frame it encodes.
		 */
		void onEncodedFrame(EncodedImage image);

	}


	/**
	 * Initializes the encoder, or initializes it again after a {@link
	 * #release()}, possibly with a different frame size.
	 *
	 * @param settings The encoder settings.
	 * @param callback Receives the encoded frames until the encoder is
	 *                 released.
	 *
	 * @return {@link VideoCodecStatus#OK} on success.
	 */
	VideoCodecStatus initEncode(Settings settings, Callback callback);

	/**
	 * Releases the encoder. It may be initialized again afterwards.
	 *
	 * @return {@link VideoCodecStatus#OK} on success.
	 */
	VideoCodecStatus release();

	/**
	 * Encodes a frame. The frame is valid only until this method returns; to
	 * encode it later, {@link VideoFrame#retain() retain} it and {@link
	 * VideoFrame#release() release} it once done. Its pixel data must not be
	 * changed, since other consumers may share it.
	 *
	 * @param frame The frame to encode.
	 * @param info  Additional information, such as a key frame request.
	 *
	 * @return {@link VideoCodecStatus#OK} if the frame was taken, also if it
	 *         is delivered later or dropped.
	 */
	VideoCodecStatus encode(VideoFrame frame, EncodeInfo info);

	/**
	 * Sets the bitrate and frame rate to aim for.
	 *
	 * @param parameters The target rates.
	 *
	 * @return {@link VideoCodecStatus#OK} on success.
	 */
	VideoCodecStatus setRates(RateControlParameters parameters);

	/**
	 * Returns the quality scaling settings. Read after construction and after
	 * each initialization.
	 *
	 * @return {@link ScalingSettings#DEFAULT} unless overridden.
	 */
	default ScalingSettings getScalingSettings() {
		return ScalingSettings.DEFAULT;
	}

	/**
	 * Returns the bitrate limits of the encoder per resolution. Read after
	 * construction and after each initialization.
	 *
	 * @return No limits unless overridden.
	 */
	default ResolutionBitrateLimits[] getResolutionBitrateLimits() {
		return new ResolutionBitrateLimits[0];
	}

	/**
	 * Returns properties of the encoder. Read after construction and after
	 * each initialization.
	 *
	 * @return No alignment unless overridden.
	 */
	default EncoderInfo getEncoderInfo() {
		return new EncoderInfo(1, false);
	}

	/**
	 * Returns the name of the implementation, which shows up in statistics.
	 *
	 * @return The simple class name unless overridden.
	 */
	default String getImplementationName() {
		return getClass().getSimpleName();
	}

	/**
	 * Returns whether the encoder runs on dedicated hardware.
	 *
	 * @return {@code false} unless overridden.
	 */
	default boolean isHardwareEncoder() {
		return false;
	}

}
