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

import dev.onvoid.webrtc.client.CustomAudio;
import dev.onvoid.webrtc.client.CustomVideo;
import dev.onvoid.webrtc.client.WebRtc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates the tracks the Java client sends and feeds them: a 440 Hz tone and an
 * animated color pattern, generated in Java and pushed into the tracks.
 * <p>
 * The tracks belong to the runtime that created them. They may be sent on any
 * number of peers, which lets the client start a new peer for every participant
 * that joins without creating new tracks.
 *
 * @author Alex Andres
 */
public class MediaManager implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MediaManager.class);

    /** The audio track, fed by the audio generator. */
    private final CustomAudio audio;

    /** The video track, fed by the video generator. */
    private final CustomVideo video;

    private final AudioGenerator audioGenerator;

    private final VideoGenerator videoGenerator;


    /**
     * Creates the tracks and starts feeding them.
     *
     * @param rtc The runtime that creates the tracks. Its audio mode must allow pushed audio.
     */
    public MediaManager(WebRtc rtc) {
        audio = rtc.media().customAudio("audio0");
        video = rtc.media().customVideo("video0");

        audioGenerator = new AudioGenerator(audio);
        videoGenerator = new VideoGenerator(video);

        audioGenerator.start();
        videoGenerator.start();

        LOG.info("Audio and video tracks created with generators");
    }

    /**
     * @return The audio track.
     */
    public CustomAudio audio() {
        return audio;
    }

    /**
     * @return The video track.
     */
    public CustomVideo video() {
        return video;
    }

    /**
     * Stops the generators and closes the tracks, which stops sending them on every peer.
     */
    @Override
    public void close() {
        audioGenerator.stop();
        videoGenerator.stop();

        audio.close();
        video.close();

        LOG.info("Media resources closed");
    }
}
