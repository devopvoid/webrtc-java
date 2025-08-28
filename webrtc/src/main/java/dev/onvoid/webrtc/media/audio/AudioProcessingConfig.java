/*
 * Copyright 2021 Alex Andres
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

package dev.onvoid.webrtc.media.audio;

/**
 * This config is intended to be used during setup of {@link AudioProcessing} to
 * enable/disable audio processing effects.
 *
 * @author Alex Andres
 */
public class AudioProcessingConfig {

    /**
     * Configuration for the audio processing pipeline, including settings for
     * downmixing and processing rates.
     */
    public final Pipeline pipeline = new Pipeline();

    /**
     * Echo cancellation configuration to remove acoustic echo from capture signals.
     * Controls whether echo cancellation is enabled and its filtering behavior.
     */
	public final EchoCanceller echoCanceller = new EchoCanceller();

    /**
     * Digital gain control configuration for automatic volume adjustment.
     * Includes both fixed and adaptive digital gain control settings.
     */
	public final GainControlDigital gainControlDigital = new GainControlDigital();

    /**
     * High-pass filter configuration for removing low-frequency components from the
     * processed audio signal.
     */
	public final HighPassFilter highPassFilter = new HighPassFilter();

    /**
     * Noise suppression configuration for reducing background noise in captured audio.
     * Supports multiple suppression levels.
     */
	public final NoiseSuppression noiseSuppression = new NoiseSuppression();


    /**
     * Configures the audio processing pipeline settings.
     */
    public static class Pipeline {

        /**
         * Specifies the method to use when downmixing multiple audio channels.
         * This determines how channels are combined when reducing the number of channels.
         */
        public enum DownmixMethod {
            /** Average across channels. */
            AverageChannels,
            /** Use the first channel. */
            UseFirstChannel
        }

        /**
         * Maximum allowed processing rate used internally.
         * May only be set to 32000 or 48000, and any differing values will be treated as 48000.
         */
        int maximumInternalProcessingRate = 48000;

        /**
         * When enabled, audio rendering can process multiple channels.
         * Default is false.
         */
        boolean multiChannelRender = false;

        /**
         * When enabled, allows multi-channel processing of capture audio when AEC3 is active
         * or a custom AEC is injected.
         * Default is false.
         */
        boolean multiChannelCapture = false;

        /**
         * Determines how multiple capture channels are combined when a single-channel
         * signal is required.
         * Defaults to averaging all available channels.
         */
        DownmixMethod captureDownmixMethod = DownmixMethod.AverageChannels;
    }


	/**
	 * Configuration for the echo cancellation module in audio processing.
	 * Controls whether echo cancellation is enabled and its filtering behavior.
	 */
	public static class EchoCanceller {

	    /**
	     * Determines whether echo cancellation is enabled.
	     * When true, acoustic echo will be removed from the capture signal.
	     */
		public boolean enabled;

	    /**
	     * Controls whether to enforce high-pass filtering with echo cancellation.
	     * When true, additional filtering is applied to improve echo cancellation.
	     * Default is true.
	     */
		public boolean enforceHighPassFiltering = true;

	}


	/**
	 * Configuration for digital gain control in audio processing.
	 * Provides settings for both fixed and adaptive digital gain control.
	 */
	public static class GainControlDigital {

		/**
		 * Configuration for fixed digital gain control.
		 * Applies a constant gain value to the audio signal.
		 */
		public static class FixedDigital {

			/**
			 * The fixed gain to apply to the audio signal in decibels.
			 * The default value is 0.0 dB (no gain).
			 */
			public float gainDb = 0.0f;

		}


		/**
		 * Configuration for adaptive digital gain control.
		 * Automatically adjusts gain based on input signal characteristics.
		 */
		public static class AdaptiveDigital {

			/**
			 * Determines whether adaptive digital gain control is enabled.
			 */
			public boolean enabled;

			/**
			 * Headroom in decibels to maintain above the signal level.
			 * The default value is 5.0 dB.
			 */
			public float headroomDb = 5.0f;

			/**
			 * Maximum gain in decibels that can be applied to the signal.
			 * The default value is 50.0 dB.
			 */
			public float maxGainDb = 50.0f;

			/**
			 * Initial gain in decibels to apply when processing starts.
			 * The default value is 15.0 dB.
			 */
			public float initialGainDb = 15.0f;

			/**
			 * The maximum rate at which gain can change, in decibels per second.
			 * Controls how quickly the gain adapts to changing conditions.
			 * The default value is 6.0 dB/s.
			 */
			public float maxGainChangeDbPerSecond = 6.0f;

			/**
			 * Maximum allowable noise level in the output, in decibels relative to full scale.
			 * The default value is -50.0 dBFS.
			 */
			public float maxOutputNoiseLevelDbfs = -50.0f;

		}

		/**
		 * Fixed digital gain control settings.
		 */
		public final FixedDigital fixedDigital = new FixedDigital();

		/**
		 * Adaptive digital gain control settings.
		 */
		public final AdaptiveDigital adaptiveDigital = new AdaptiveDigital();

		/**
		 * Determines whether digital gain control is enabled.
		 */
		public boolean enabled;

	}


	/**
	 * Configuration for the high-pass filter in audio processing.
	 */
	public static class HighPassFilter {

		/**
		 * Determines whether the high-pass filter is enabled.
		 * When true, low-frequency components will be filtered out of the audio signal.
		 */
		public boolean enabled;

		/**
		 * Controls whether the filter is applied across the full frequency band.
		 * When true, the filter affects the entire frequency spectrum.
		 * Default is true.
		 */
		public boolean applyInFullBand = true;

	}


	/**
	 * Configuration for audio level estimation.
	 * Provides settings to enable/disable the measurement of audio signal levels.
	 */
	public static class LevelEstimation {

		/**
		 * Determines whether audio level estimation is enabled.
		 * When true, the audio processing module will calculate and report signal levels.
		 */
		public boolean enabled;

	}


	/**
	 * Configuration for noise suppression in audio processing.
	 */
	public static class NoiseSuppression {

		/**
		 * Defines the available intensity levels for noise suppression.
		 * Higher levels provide more aggressive noise reduction but may affect voice quality.
		 */
		public enum Level {
			/** Minimal noise suppression, preserves most audio quality. */
			LOW,
			/** Balanced noise suppression with moderate effect on audio quality. */
			MODERATE,
			/** Strong noise suppression that may slightly affect voice quality. */
			HIGH,
			/** Maximum noise suppression that prioritizes noise removal over voice quality. */
			VERY_HIGH
		}

		/**
		 * Determines whether noise suppression is enabled.
		 * When true, background noise will be reduced in the processed audio.
		 */
		public boolean enabled;

		/**
		 * Specifies the intensity level of noise suppression to apply.
		 * Controls how aggressively noise is filtered from the audio signal.
		 */
		public Level level;

	}
}
