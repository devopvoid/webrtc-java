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

import java.util.List;

import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSink;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.VideoDeviceSource;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;

import dev.onvoid.webrtc.examples.web.connection.PeerConnectionManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages media tracks for WebRTC connections, including audio and video.
 *
 * @author Alex Andres
 */
public class MediaManager {

    private static final Logger LOG = LoggerFactory.getLogger(MediaManager.class);

    /** The audio track managed by this class. */
    private AudioTrack audioTrack;

    /** The video track managed by this class. */
    private VideoTrack videoTrack;

    /** The custom audio source for generating audio frames. */
    private CustomAudioSource customAudioSource;

    /** The audio generator responsible for creating and pushing audio frames to the custom audio source. */
    private AudioGenerator audioGenerator;

    /** The custom video source for generating video frames. */
    private CustomVideoSource customVideoSource;

    /** The video generator responsible for creating and pushing video frames to the custom video source. */
    private VideoGenerator videoGenerator;


    /**
     * Creates a new MediaManager.
     */
    public MediaManager() {
        LOG.info("MediaManager created");
    }

    /**
     * Creates and initializes audio and video tracks.
     * 
     * @param peerConnectionManager The peer connection manager to use for creating tracks.
     */
    public void createTracks(PeerConnectionManager peerConnectionManager) {
        createAudioTrack(peerConnectionManager);
        createVideoTrack(peerConnectionManager);

        // Add tracks to the peer connection.
        List<String> streamIds = List.of("stream0");

        peerConnectionManager.addTrack(getAudioTrack(), streamIds);
        peerConnectionManager.addTrack(getVideoTrack(), streamIds);
    }

    /**
     * Creates and initializes tracks with a custom audio source and standard video track.
     * 
     * @param peerConnectionManager The peer connection manager to use for creating tracks.
     * @param useCustomAudio Whether to use a custom audio source that can push frames.
     */
    public void createTracks(PeerConnectionManager peerConnectionManager, boolean useCustomAudio) {
        if (useCustomAudio) {
            createCustomAudioTrack(peerConnectionManager);
        }
        else {
            createAudioTrack(peerConnectionManager);
        }

        createVideoTrack(peerConnectionManager);

        // Add tracks to the peer connection.
        List<String> streamIds = List.of("stream0");

        peerConnectionManager.addTrack(getAudioTrack(), streamIds);
        peerConnectionManager.addTrack(getVideoTrack(), streamIds);
    }

    /**
     * Creates and initializes tracks with options for both custom audio and video sources.
     *
     * @param peerConnectionManager The peer connection manager to use for creating tracks.
     * @param useCustomAudio        Whether to use a custom audio source that can push frames.
     * @param useCustomVideo        Whether to use a custom video source that can push frames.
     */
    public void createTracks(PeerConnectionManager peerConnectionManager, boolean useCustomAudio, boolean useCustomVideo) {
        if (useCustomAudio) {
            createCustomAudioTrack(peerConnectionManager);
        }
        else {
            createAudioTrack(peerConnectionManager);
        }

        if (useCustomVideo) {
            createCustomVideoTrack(peerConnectionManager);
        }
        else {
            createVideoTrack(peerConnectionManager);
        }

        // Add tracks to the peer connection.
        List<String> streamIds = List.of("stream0");

        peerConnectionManager.addTrack(getAudioTrack(), streamIds);
        peerConnectionManager.addTrack(getVideoTrack(), streamIds);
    }

    /**
     * Creates an audio track with default options.
     */
    private void createAudioTrack(PeerConnectionManager peerConnectionManager) {
        AudioOptions audioOptions = new AudioOptions();
        audioOptions.echoCancellation = true;
        audioOptions.autoGainControl = true;
        audioOptions.noiseSuppression = true;

        audioTrack = peerConnectionManager.createAudioTrack(audioOptions, "audio0");

        LOG.info("Audio track created");
    }

    /**
     * Creates an audio track with a custom audio source that can push audio frames.
     * Also starts the audio generator thread that pushes audio frames every 10ms.
     *
     * @param peerConnectionManager The peer connection manager to use for creating the track.
     */
    public void createCustomAudioTrack(PeerConnectionManager peerConnectionManager) {
        customAudioSource = new CustomAudioSource();

        audioTrack = peerConnectionManager.createAudioTrack(customAudioSource, "audio0");

        // Start the audio generator.
        audioGenerator = new AudioGenerator(customAudioSource);
        audioGenerator.start();

        LOG.info("Custom audio track created with audio generator");
    }

    /**
     * Creates a video track using the default video device.
     */
    private void createVideoTrack(PeerConnectionManager peerConnectionManager) {
        VideoDeviceSource videoSource = new VideoDeviceSource();
        videoSource.start();

        videoTrack = peerConnectionManager.createVideoTrack(videoSource, "video0");

        LOG.info("Video track created");
    }

    /**
     * Creates a video track with a custom video source that can push video frames.
     * Also starts the video generator thread that pushes video frames at regular intervals.
     *
     * @param peerConnectionManager The peer connection manager to use for creating the track.
     */
    public void createCustomVideoTrack(PeerConnectionManager peerConnectionManager) {
        customVideoSource = new CustomVideoSource();

        videoTrack = peerConnectionManager.createVideoTrack(customVideoSource, "video0");

        // Start the video generator.
        videoGenerator = new VideoGenerator(customVideoSource);
        videoGenerator.start();

        LOG.info("Custom video track created with video generator");
    }

    /**
     * Gets the audio track.
     *
     * @return The audio track.
     */
    public AudioTrack getAudioTrack() {
        return audioTrack;
    }

    /**
     * Gets the video track.
     *
     * @return The video track.
     */
    public VideoTrack getVideoTrack() {
        return videoTrack;
    }

    /**
     * Adds a sink to the audio track.
     *
     * @param sink The sink to add.
     */
    public void addAudioSink(AudioTrackSink sink) {
        if (audioTrack != null) {
            audioTrack.addSink(sink);

            LOG.info("Added sink to audio track");
        }
    }

    /**
     * Adds a sink to the video track.
     *
     * @param sink The sink to add.
     */
    public void addVideoSink(VideoTrackSink sink) {
        if (videoTrack != null) {
            videoTrack.addSink(sink);

            LOG.info("Added sink to video track");
        }
    }

    /**
     * Disposes of all media resources.
     */
    public void dispose() {
        // Stop the audio generator if it's running.
        if (audioGenerator != null) {
            audioGenerator.stop();
            audioGenerator = null;
        }

        // Stop the video generator if it's running.
        if (videoGenerator != null) {
            videoGenerator.stop();
            videoGenerator = null;
        }

        if (audioTrack != null) {
            audioTrack.dispose();
            audioTrack = null;
        }

        if (videoTrack != null) {
            videoTrack.dispose();
            videoTrack = null;
        }

        customAudioSource = null;
        customVideoSource = null;

        LOG.info("Media resources disposed");
    }
}