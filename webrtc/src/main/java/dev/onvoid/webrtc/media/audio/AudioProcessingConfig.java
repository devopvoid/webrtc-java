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

    public final PreAmplifier preAmplifier = new PreAmplifier();

    public final CaptureLevelAdjustment captureLevelAdjustment = new CaptureLevelAdjustment();

    public final EchoCanceller echoCanceller = new EchoCanceller();

    public final GainControl gainControl = new GainControl();

    public final HighPassFilter highPassFilter = new HighPassFilter();

    public final NoiseSuppression noiseSuppression = new NoiseSuppression();

    public final ResidualEchoDetector residualEchoDetector = new ResidualEchoDetector();

    public final TransientSuppression transientSuppression = new TransientSuppression();

    public final VoiceDetection voiceDetection = new VoiceDetection();

    public static class PreAmplifier {
        public boolean enabled;
        public float fixedGainFactor;

        public PreAmplifier() {
            enabled = false;
            fixedGainFactor = 1.0f;
        }
    }

    public static class CaptureLevelAdjustment {

        public boolean enabled;
        public float preGainFactor;
        public float postGainFactor;

        public AnalogMicGainEmulation analogMicGainEmulation;

        public CaptureLevelAdjustment() {
            enabled = false;
            preGainFactor = 1.0f;
            postGainFactor = 1.0f;
            analogMicGainEmulation = new AnalogMicGainEmulation();
        }

        public static class AnalogMicGainEmulation {
            public boolean enabled;
            public int initialLevel;

            public AnalogMicGainEmulation() {
                enabled = false;
                initialLevel = 255;
            }
        }
    }

    public static class EchoCanceller {

        public boolean enabled;

        public boolean enforceHighPassFiltering;

    }

    public static class GainControl {

        public final FixedDigital fixedDigital = new FixedDigital();
        public final AdaptiveDigital adaptiveDigital = new AdaptiveDigital();
        public boolean enabled;

        public static class FixedDigital {

            public float gainDb = 0.0f;

        }

        public static class AdaptiveDigital {

            public boolean enabled;

            /**
             * Run the adaptive digital controller but the signal is not modified.
             */
            public boolean dryRun = false;

            public int vadResetPeriodMs = 1500;
            public int adjacentSpeechFramesThreshold = 12;
            public float maxGainChangeDbPerSecond = 3.0f;
            public float maxOutputNoiseLevelDbfs = -50.0f;

        }

    }

    public static class HighPassFilter {

        public boolean enabled;

    }

    public static class LevelEstimation {

        public boolean enabled;

    }

    public static class NoiseSuppression {

        public boolean enabled;
        public Level level;

        public enum Level {
            LOW,
            MODERATE,
            HIGH,
            VERY_HIGH
        }

    }

    public static class ResidualEchoDetector {

        public boolean enabled;

    }

    public static class TransientSuppression {

        public boolean enabled;

    }

    public static class VoiceDetection {

        public boolean enabled;

    }
}
