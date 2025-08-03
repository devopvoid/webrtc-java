/*
 * Copyright 2025 Alex Andres
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

package dev.onvoid.webrtc.examples.web.media;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.onvoid.webrtc.media.audio.CustomAudioSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A generator that produces synthetic audio samples as a continuous sine wave.
 * <p>
 * This class creates a 440 Hz (A4 note) sine wave and delivers audio frames
 * at regular 10 ms intervals. The generated audio is stereo with 16-bit samples
 * at a 48 kHz sample rate. The audio generator runs on a dedicated scheduled
 * thread that can be started and stopped on demand.
 * </p>
 * <p>
 * The sine wave phase is maintained between audio frames to ensure a continuous
 * waveform without clicks or pops that would occur from phase discontinuities.
 * </p>
 *
 * @author Alex Andres
 */
public class AudioGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(AudioGenerator.class);

    /** The custom audio source that receives generated audio frames. */
    private final CustomAudioSource customAudioSource;

    /** Flag indicating whether the audio generator is running. */
    private final AtomicBoolean generatorRunning = new AtomicBoolean(false);

    /** Executor service for scheduling audio sample generation. */
    private ScheduledExecutorService executorService;

    /** Future for the scheduled audio sample generation task. */
    private ScheduledFuture<?> generatorFuture;

    /** Default audio parameters */
    private static final int DEFAULT_BITS_PER_SAMPLE = 16;
    private static final int DEFAULT_SAMPLE_RATE = 48000;
    private static final int DEFAULT_CHANNELS = 2;
    private static final int DEFAULT_FRAME_COUNT = 480; // 10ms at 48kHz
    
    /** 
     * Sine wave phase tracking for audio generation.
     * Maintained between frames to ensure a continuous sine wave without clicks or pops.
     */
    private double sinePhase = 0.0;


    public AudioGenerator(CustomAudioSource audioSource) {
        customAudioSource = audioSource;
    }

    /**
     * Starts the audio sample generator thread that pushes audio frames
     * to the custom audio source every 10 ms.
     */
    public void start() {
        if (generatorRunning.get()) {
            LOG.info("Audio generator is already running");
            return;
        }

        executorService = Executors.newSingleThreadScheduledExecutor();

        generatorRunning.set(true);

        generatorFuture = executorService.scheduleAtFixedRate(() -> {
            if (!generatorRunning.get()) {
                return;
            }

            try {
                // Create a buffer with audio data (silence in this case).
                int bytesPerSample = DEFAULT_BITS_PER_SAMPLE / 8;
                byte[] audioData = new byte[DEFAULT_FRAME_COUNT * DEFAULT_CHANNELS * bytesPerSample];

                // Generate a pleasant sine wave at 440 Hz (A4 note).
                double amplitude = 0.2; // 30% of maximum to avoid being too loud
                double frequency = 440.0; // A4 note (440 Hz)
                double radiansPerSample = 2.0 * Math.PI * frequency / DEFAULT_SAMPLE_RATE;

                for (int i = 0; i < DEFAULT_FRAME_COUNT; i++) {
                    short sample = (short) (amplitude * Short.MAX_VALUE * Math.sin(sinePhase));
                    sinePhase += radiansPerSample;

                    // Keep the phase between 0 and 2π.
                    if (sinePhase > 2.0 * Math.PI) {
                        sinePhase -= 2.0 * Math.PI;
                    }

                    // Write the sample to both channels (stereo).
                    for (int channel = 0; channel < DEFAULT_CHANNELS; channel++) {
                        int index = (i * DEFAULT_CHANNELS + channel) * bytesPerSample;
                        // Write as little-endian (LSB first).
                        audioData[index] = (byte) (sample & 0xFF);
                        audioData[index + 1] = (byte) ((sample >> 8) & 0xFF);
                    }
                }

                // Push the audio data to the custom audio source.
                customAudioSource.pushAudio(
                        audioData,
                        DEFAULT_BITS_PER_SAMPLE,
                        DEFAULT_SAMPLE_RATE,
                        DEFAULT_CHANNELS,
                        DEFAULT_FRAME_COUNT
                );
            }
            catch (Exception e) {
                LOG.error("Error in audio generator thread", e);
            }
        }, 0, 10, TimeUnit.MILLISECONDS);

        LOG.info("Audio generator started");
    }

    /**
     * Stops the audio sample generator thread.
     */
    public void stop() {
        if (!generatorRunning.get()) {
            return;
        }

        generatorRunning.set(false);

        if (generatorFuture != null) {
            generatorFuture.cancel(false);
            generatorFuture = null;
        }

        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                    executorService.shutdownNow();
                }
            }
            catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
            executorService = null;
        }

        LOG.info("Audio generator stopped");
    }

}
