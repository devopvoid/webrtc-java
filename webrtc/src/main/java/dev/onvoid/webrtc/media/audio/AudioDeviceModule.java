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

import dev.onvoid.webrtc.internal.DisposableNativeObject;
import dev.onvoid.webrtc.internal.NativeLoader;

import java.util.AbstractMap.SimpleEntry;
import java.util.List;
import java.util.Map;

public class AudioDeviceModule extends DisposableNativeObject {

	static {
		try {
			NativeLoader.loadLibrary("webrtc-java");
		}
		catch (Exception e) {
			throw new RuntimeException("Load library 'webrtc-java' failed", e);
		}
	}


	private Map.Entry<AudioSink, Long> sinkEntry;

	private Map.Entry<AudioSource, Long> sourceEntry;


	public AudioDeviceModule() {
		initialize(AudioLayer.kPlatformDefaultAudio);
	}

	public AudioDeviceModule(AudioLayer audioLayer) {
		initialize(audioLayer);
	}

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

	public native void initPlayout();

	public native void stopPlayout();

	public native void startPlayout();

	public native void initRecording();

	public native void stopRecording();

	public native void startRecording();

	public native List<AudioDevice> getPlayoutDevices();

	public native List<AudioDevice> getRecordingDevices();

	public native void setPlayoutDevice(AudioDevice device);

	public native void setRecordingDevice(AudioDevice device);

	public native boolean isSpeakerMuted();

	public native boolean isMicrophoneMuted();

	public native int getSpeakerVolume();

	public native int getMaxSpeakerVolume();

	public native int getMinSpeakerVolume();

	public native int getMicrophoneVolume();

	public native int getMaxMicrophoneVolume();

	public native int getMinMicrophoneVolume();

	public native void setSpeakerVolume(int volume);

	public native void setSpeakerMute(boolean mute);

	public native void setMicrophoneVolume(int volume);

	public native void setMicrophoneMute(boolean mute);

	private native void initialize(AudioLayer audioLayer);

	private native void disposeInternal();

	private native long addSinkInternal(AudioSink sink);

	private native void removeSinkInternal(long sinkHandle);

	private native long addSourceInternal(AudioSource source);

	private native void removeSourceInternal(long sourceHandle);

}
