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

package dev.onvoid.webrtc;

import dev.onvoid.webrtc.internal.DisposableNativeObject;
import dev.onvoid.webrtc.internal.NativeLoader;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.MediaType;
import dev.onvoid.webrtc.media.audio.AudioDeviceModuleBase;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioProcessing;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import dev.onvoid.webrtc.media.video.VideoTrackSource;
import dev.onvoid.webrtc.media.video.VideoTrack;

import java.util.Map;
import java.util.Objects;

/**
 * The PeerConnectionFactory is the main entry point for a WebRTC application.
 * It provides factory methods for {@link RTCPeerConnection} and audio/video
 * {@link MediaStreamTrack}s.
 * <p>
 * A factory sends audio from exactly one kind of input. Either its
 * {@link AudioDeviceModuleBase audio device module} captures the audio, which
 * is what tracks created from {@link #createAudioSource(AudioOptions)} send,
 * or the application pushes the audio through a {@link CustomAudioSource}.
 * WebRTC feeds device-captured audio into every audio sender of a factory, so
 * a sender backed by a custom source would receive both, which is a race
 * inside WebRTC that aborts the process. The factory therefore commits to the
 * kind that is used first and rejects the other with an
 * {@link IllegalStateException}. It commits as soon as a source or track of
 * either kind is created, whether or not that track ever sends. Once a custom
 * source is in use, the device is never opened for capture; playout of
 * received audio is not affected. Applications that need both create a second
 * factory.
 *
 * @author Alex Andres
 */
public class PeerConnectionFactory extends DisposableNativeObject {

	static {
		try {
			NativeLoader.loadLibrary("webrtc-java");
		}
		catch (Exception e) {
			throw new RuntimeException("Load library 'webrtc-java' failed", e);
		}
	}

	/**
	 * The kind of input that provides the audio this factory sends.
	 */
	private enum AudioInputMode {

		/** The audio device module captures the audio. */
		DEVICE,

		/** The application pushes the audio through a {@link CustomAudioSource}. */
		CUSTOM

	}


	@SuppressWarnings("unused")
	private long networkThreadHandle;

	@SuppressWarnings("unused")
	private long signalingThreadHandle;

	@SuppressWarnings("unused")
	private long workerThreadHandle;

	@SuppressWarnings("unused")
	private long audioModuleHandle;

	/** Guards {@link #audioInputMode}. */
	private final Object audioInputLock = new Object();

	/** Set the first time either kind of audio input is used; never reset. */
	private AudioInputMode audioInputMode;


	/**
	 * Creates an instance of PeerConnectionFactory.
	 */
	public PeerConnectionFactory() {
		initialize(null, null, null);
	}

	/**
	 * Creates an instance of PeerConnectionFactory with the provided audio
	 * processing module.
	 *
	 * @param audioProcessing The custom audio processing module.
	 */
	public PeerConnectionFactory(AudioProcessing audioProcessing) {
		initialize(null, null, audioProcessing);
	}

	/**
	 * Creates an instance of PeerConnectionFactory with the provided audio
	 * device module.
	 *
	 * @param audioModule The custom audio device module.
	 */
	public PeerConnectionFactory(AudioDeviceModuleBase audioModule) {
		initialize(null, audioModule, null);
	}

	/**
	 * Creates an instance of PeerConnectionFactory with provided modules for
	 * audio devices and audio processing.
	 *
	 * @param audioModule     The custom audio device module.
	 * @param audioProcessing The custom audio processing module.
	 */
	public PeerConnectionFactory(AudioDeviceModuleBase audioModule,
			AudioProcessing audioProcessing) {
		initialize(null, audioModule, audioProcessing);
	}

	/**
	 * Creates an instance of PeerConnectionFactory with the provided field
	 * trials, which allow enabling experimental WebRTC features.
	 *
	 * @param fieldTrials The field trials to set, e.g. {@code
	 *                    {"WebRTC-Bar": "Enabled"}}. Keys and values must be
	 *                    non-null and non-empty.
	 */
	public PeerConnectionFactory(Map<String, String> fieldTrials) {
		initialize(fieldTrials, null, null);
	}

	/**
	 * Creates an instance of PeerConnectionFactory with the provided field
	 * trials and audio processing module.
	 *
	 * @param fieldTrials     The field trials to set, e.g. {@code
	 *                        {"WebRTC-Bar": "Enabled"}}. Keys and values must
	 *                        be non-null and non-empty.
	 * @param audioProcessing The custom audio processing module.
	 */
	public PeerConnectionFactory(Map<String, String> fieldTrials,
			AudioProcessing audioProcessing) {
		initialize(fieldTrials, null, audioProcessing);
	}

	/**
	 * Creates an instance of PeerConnectionFactory with the provided field
	 * trials and audio device module.
	 *
	 * @param fieldTrials The field trials to set, e.g. {@code
	 *                    {"WebRTC-Bar": "Enabled"}}. Keys and values must be
	 *                    non-null and non-empty.
	 * @param audioModule The custom audio device module.
	 */
	public PeerConnectionFactory(Map<String, String> fieldTrials,
			AudioDeviceModuleBase audioModule) {
		initialize(fieldTrials, audioModule, null);
	}

	/**
	 * Creates an instance of PeerConnectionFactory with the provided field
	 * trials and modules for audio devices and audio processing.
	 *
	 * @param fieldTrials     The field trials to set, e.g. {@code
	 *                        {"WebRTC-Bar": "Enabled"}}. Keys and values must
	 *                        be non-null and non-empty.
	 * @param audioModule     The custom audio device module.
	 * @param audioProcessing The custom audio processing module.
	 */
	public PeerConnectionFactory(Map<String, String> fieldTrials,
			AudioDeviceModuleBase audioModule, AudioProcessing audioProcessing) {
		initialize(fieldTrials, audioModule, audioProcessing);
	}

	/**
	 * Creates an {@link AudioTrackSource} whose audio is captured by this
	 * factory's audio device module. The audio source may be used by one or
	 * more {@link AudioTrack}s.
	 * <p>
	 * Calling this commits the factory to device-captured audio; see the class
	 * description.
	 *
	 * @param options Audio options to control the audio processing.
	 *
	 * @return The created audio source.
	 *
	 * @throws IllegalStateException If this factory already sends audio from a
	 *                               {@link CustomAudioSource}.
	 */
	public AudioTrackSource createAudioSource(AudioOptions options) {
		Objects.requireNonNull(options, "AudioOptions is null");

		requireAudioInputMode(AudioInputMode.DEVICE);

		return createAudioSourceInternal(options);
	}

	/**
	 * Creates an new {@link AudioTrack}. The audio track can be added to the
	 * {@link RTCPeerConnection} using the {@link RTCPeerConnection#addTrack
	 * addTrack} or {@link RTCPeerConnection#addTransceiver addTransceiver}
	 * methods.
	 * <p>
	 * Passing a {@link CustomAudioSource} commits the factory to pushed audio
	 * and switches off capture from its audio device module; see the class
	 * description.
	 *
	 * @param label  The identifier string of the audio track.
	 * @param source The audio source that provides audio data.
	 *
	 * @return The created audio track.
	 *
	 * @throws IllegalStateException If the source is a {@link CustomAudioSource}
	 *                               and this factory already sends audio
	 *                               captured by its audio device module.
	 */
	public AudioTrack createAudioTrack(String label, AudioTrackSource source) {
		Objects.requireNonNull(label, "Audio track label is null");
		Objects.requireNonNull(source, "AudioTrackSource is null");

		if (source instanceof CustomAudioSource) {
			requireAudioInputMode(AudioInputMode.CUSTOM);
		}

		return createAudioTrackInternal(label, source);
	}

	/**
	 * Commits this factory to the given kind of audio input, or verifies that
	 * it is already committed to it. Committing to {@link AudioInputMode#CUSTOM}
	 * switches off capture from the audio device module.
	 *
	 * @param mode The kind of audio input about to be used.
	 *
	 * @throws IllegalStateException If the factory is committed to the other
	 *                               kind.
	 */
	private void requireAudioInputMode(AudioInputMode mode) {
		synchronized (audioInputLock) {
			if (audioInputMode == mode) {
				return;
			}
			if (audioInputMode == AudioInputMode.CUSTOM) {
				throw new IllegalStateException("This PeerConnectionFactory already sends audio pushed through a "
						+ "CustomAudioSource. WebRTC feeds the audio captured by a factory's AudioDeviceModule into "
						+ "every audio sender of that factory, so device-captured and custom audio sources cannot be "
						+ "combined in one factory. Create a separate PeerConnectionFactory for device-captured audio.");
			}
			if (audioInputMode == AudioInputMode.DEVICE) {
				throw new IllegalStateException("This PeerConnectionFactory already sends audio captured by its "
						+ "AudioDeviceModule. WebRTC feeds that audio into every audio sender of the factory, so "
						+ "device-captured and custom audio sources cannot be combined in one factory. Create a "
						+ "separate PeerConnectionFactory for CustomAudioSource tracks.");
			}

			if (mode == AudioInputMode.CUSTOM) {
				setDeviceCaptureEnabled(false);
			}

			audioInputMode = mode;
		}
	}

	/**
	 * Creates a new {@link VideoTrack}. The video track can be added to the
	 * {@link RTCPeerConnection} using the {@link RTCPeerConnection#addTrack
	 * addTrack} or {@link RTCPeerConnection#addTransceiver addTransceiver}
	 * methods.
	 *
	 * @param label  The identifier string of the video track.
	 * @param source The video source that provides video data.
	 *
	 * @return The created video track.
	 */
	public native VideoTrack createVideoTrack(String label, VideoTrackSource source);

	/**
	 * Creates a new {@link RTCPeerConnection}.
	 *
	 * @param config   The peer connection configuration.
	 * @param observer The observer that receives peer connection state
	 *                 changes.
	 *
	 * @return The created peer connection.
	 */
	public native RTCPeerConnection createPeerConnection(
			RTCConfiguration config, PeerConnectionObserver observer);

	/**
	 * Returns the capabilities of the system for receiving media of the given
	 * media type.
	 *
	 * @param type The type value must be either {@code AUDIO} or {@code
	 *             VIDEO}.
	 *
	 * @return The supported capabilities for an {@link RTCRtpReceiver}.
	 */
	public native RTCRtpCapabilities getRtpReceiverCapabilities(MediaType type);

	/**
	 * Returns the capabilities of the system for sending media of the given
	 * media type.
	 *
	 * @param type The type value must be either {@code AUDIO} or {@code
	 *             VIDEO}.
	 *
	 * @return The supported capabilities for an {@link RTCRtpSender}.
	 */
	public native RTCRtpCapabilities getRtpSenderCapabilities(MediaType type);

	@Override
	public native void dispose();

	private native void initialize(Map<String, String> fieldTrials,
			AudioDeviceModuleBase audioModule, AudioProcessing audioProcessing);

	private native AudioTrackSource createAudioSourceInternal(AudioOptions options);

	private native AudioTrack createAudioTrackInternal(String label, AudioTrackSource source);

	/**
	 * Enables or disables the capture path of the audio device module WebRTC
	 * uses for this factory. While disabled, WebRTC cannot start a recording
	 * and recorded audio never reaches the factory's audio senders.
	 *
	 * @param enabled Whether device-captured audio may reach the audio senders.
	 */
	private native void setDeviceCaptureEnabled(boolean enabled);

}
