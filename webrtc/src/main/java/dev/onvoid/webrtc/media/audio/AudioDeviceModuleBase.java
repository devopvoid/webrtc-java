/*
 * Copyright 2019 Alex Andres
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

import static java.util.Objects.nonNull;
import static java.util.Objects.requireNonNull;

import java.util.AbstractMap.SimpleEntry;
import java.util.List;
import java.util.Map;

import dev.onvoid.webrtc.internal.DisposableNativeObject;
import dev.onvoid.webrtc.internal.NativeLoader;

/**
 * Base implementation for audio device modules that provides access to the system's audio devices.
 * <p>
 * This class provides functionality to list, select, and control audio input and output
 * devices through the native WebRTC implementation. It manages both recording (microphone) and
 * playback (speaker) devices, allowing for volume control, muting, and device selection.
 * <p>
 * It loads the required native library "webrtc-java" for accessing the underlying WebRTC functionality.
 * <p>
 * This class extends {@link DisposableNativeObject} to properly manage the lifecycle of native resources.
 *
 * @author Alex Andres
 */
public class AudioDeviceModuleBase extends DisposableNativeObject {

    static {
        try {
            NativeLoader.loadLibrary("webrtc-java");
        }
        catch (Exception e) {
            throw new RuntimeException("Load library 'webrtc-java' failed", e);
        }
    }


    /**
     * Stores the audio sinks and its corresponding native handle.
     * This is used to manage the lifecycle of native resources associated with the sink.
     */
    private Map.Entry<AudioSink, Long> sinkEntry;

    /**
     * Stores the audio sources and its corresponding native handle.
     * This is used to manage the lifecycle of native resources associated with the source.
     */
    private Map.Entry<AudioSource, Long> sourceEntry;


    @Override
    public void dispose() {
        if (nonNull(sinkEntry)) {
            removeSinkInternal(sinkEntry.getValue());
        }
        if (nonNull(sourceEntry)) {
            removeSourceInternal(sourceEntry.getValue());
        }

        sinkEntry = null;
        sourceEntry = null;

        disposeInternal();
    }

    /**
     * Sets the audio sink for this audio device module. If a sink was previously set, it will
     * be removed and replaced with the new sink. If the provided sink is the same as the
     * current one, this method returns without making any changes.
     *
     * @param sink the audio sink to set.
     *
     * @throws NullPointerException if the provided sink is null.
     */
    public void setAudioSink(AudioSink sink) {
        requireNonNull(sink);

        if (nonNull(sinkEntry)) {
            if (sink.equals(sinkEntry.getKey())) {
                return;
            }

            removeSinkInternal(sinkEntry.getValue());
        }

        final long nativeSink = addSinkInternal(sink);

        sinkEntry = new SimpleEntry<>(sink, nativeSink);
    }

    /**
     * Sets the audio source for this audio device module. If a source was previously set, it
     * will be removed and replaced with the new source. If the provided source is the same as
     * the current one, this method returns without making any changes.
     *
     * @param source the audio source to set.
     *
     * @throws NullPointerException if the provided source is null.
     */
    public void setAudioSource(AudioSource source) {
        requireNonNull(source);

        if (nonNull(sourceEntry)) {
            if (source.equals(sourceEntry.getKey())) {
                return;
            }

            removeSourceInternal(sourceEntry.getValue());
        }

        final long nativeSource = addSourceInternal(source);

        sourceEntry = new SimpleEntry<>(source, nativeSource);
    }

    /**
     * Initializes the audio playout components and resources. This should be called before
     * starting playback.
     */
    public native void initPlayout();

    /**
     * Stops audio playback and releases associated resources.
     */
    public native void stopPlayout();

    /**
     * Starts audio playback after initialization. Must be called after {@link #initPlayout()}.
     */
    public native void startPlayout();

    /**
     * Initializes the audio recording components and resources. This should be called before
     * starting recording.
     */
    public native void initRecording();

    /**
     * Stops audio recording and releases associated resources.
     */
    public native void stopRecording();

    /**
     * Starts audio recording after initialization. Must be called after {@link #initRecording()}.
     */
    public native void startRecording();

    /**
     * Gets a list of available audio devices for playback.
     *
     * @return List of available audio playback devices.
     */
    public native List<AudioDevice> getPlayoutDevices();

    /**
     * Gets a list of available audio devices for recording.
     *
     * @return List of available audio recording devices.
     */
    public native List<AudioDevice> getRecordingDevices();

    /**
     * Sets the audio device to use for playback.
     *
     * @param device The audio device to use for playback.
     *
     * @throws NullPointerException if the device is null.
     */
    public native void setPlayoutDevice(AudioDevice device);

    /**
     * Sets the audio device to use for recording.
     *
     * @param device The audio device to use for recording.
     *
     * @throws NullPointerException if the device is null.
     */
    public native void setRecordingDevice(AudioDevice device);

    /**
     * Checks if the speaker is currently muted.
     *
     * @return true if the speaker is muted.
     */
    public native boolean isSpeakerMuted();

    /**
     * Checks if the microphone is currently muted.
     *
     * @return true if the microphone is muted.
     */
    public native boolean isMicrophoneMuted();

    /**
     * Gets the current speaker volume level.
     *
     * @return the current speaker volume level.
     */
    public native int getSpeakerVolume();

    /**
     * Gets the maximum possible speaker volume level.
     *
     * @return the maximum speaker volume level.
     */
    public native int getMaxSpeakerVolume();

    /**
     * Gets the minimum possible speaker volume level.
     *
     * @return the minimum speaker volume level.
     */
    public native int getMinSpeakerVolume();

    /**
     * Gets the current microphone volume level.
     *
     * @return the current microphone volume level.
     */
    public native int getMicrophoneVolume();

    /**
     * Gets the maximum possible microphone volume level.
     *
     * @return the maximum microphone volume level.
     */
    public native int getMaxMicrophoneVolume();

    /**
     * Gets the minimum possible microphone volume level.
     *
     * @return the minimum microphone volume level.
     */
    public native int getMinMicrophoneVolume();

    /**
     * Sets the speaker volume to the specified level.
     *
     * @param volume the volume level to set for the speaker.
     */
    public native void setSpeakerVolume(int volume);

    /**
     * Mutes or unmutes the speaker.
     *
     * @param mute true to mute the speaker, false to unmute.
     */
    public native void setSpeakerMute(boolean mute);

    /**
     * Sets the microphone volume to the specified level.
     *
     * @param volume the volume level to set for the microphone.
     */
    public native void setMicrophoneVolume(int volume);

    /**
     * Mutes or unmutes the microphone.
     *
     * @param mute true to mute the microphone, false to unmute.
     */
    public native void setMicrophoneMute(boolean mute);

    /**
     * Disposes native resources associated with this audio device module.
     * Called by the public {@link #dispose()} method.
     */
    private native void disposeInternal();

    /**
     * Adds an audio sink to the native layer and returns a handle to it.
     *
     * @param sink the audio sink to be added to the native layer.
     *
     * @return the native handle for the added sink.
     */
    private native long addSinkInternal(AudioSink sink);

    /**
     * Removes an audio sink from the native layer using its handle.
     *
     * @param sinkHandle the native handle of the sink to remove.
     */
    private native void removeSinkInternal(long sinkHandle);

    /**
     * Adds an audio source to the native layer and returns a handle to it.
     *
     * @param source the audio source to be added to the native layer.
     *
     * @return the native handle for the added source.
     */
    private native long addSourceInternal(AudioSource source);

    /**
     * Removes an audio source from the native layer using its handle.
     *
     * @param sourceHandle the native handle of the source to remove.
     */
    private native void removeSourceInternal(long sourceHandle);

}
