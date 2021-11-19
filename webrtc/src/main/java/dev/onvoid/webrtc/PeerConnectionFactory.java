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
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioProcessing;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSource;
import dev.onvoid.webrtc.media.video.VideoTrack;

/**
 * The PeerConnectionFactory is the main entry point for a WebRTC application.
 * It provides factory methods for {@link RTCPeerConnection} and audio/video
 * {@link MediaStreamTrack}s.
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


	@SuppressWarnings("unused")
	private long networkThreadHandle;

	@SuppressWarnings("unused")
	private long signalingThreadHandle;

	@SuppressWarnings("unused")
	private long workerThreadHandle;


	/**
	 * Creates an instance of PeerConnectionFactory.
	 */
	public PeerConnectionFactory() {
		this(null, null);
	}

	/**
	 * Creates an instance of PeerConnectionFactory with the provided audio
	 * processing module.
	 *
	 * @param audioProcessing The custom audio processing module.
	 */
	public PeerConnectionFactory(AudioProcessing audioProcessing) {
		initialize(null, audioProcessing);
	}

	/**
	 * Creates an instance of PeerConnectionFactory with the provided audio
	 * device module.
	 *
	 * @param audioModule The custom audio device module.
	 */
	public PeerConnectionFactory(AudioDeviceModule audioModule) {
		initialize(audioModule, null);
	}

	/**
	 * Creates an instance of PeerConnectionFactory with provided modules for
	 * audio devices and audio processing.
	 *
	 * @param audioModule     The custom audio device module.
	 * @param audioProcessing The custom audio processing module.
	 */
	public PeerConnectionFactory(AudioDeviceModule audioModule,
			AudioProcessing audioProcessing) {
		initialize(audioModule, audioProcessing);
	}

	/**
	 * Creates an {@link AudioTrackSource}. The audio source may be used by one
	 * or more {@link AudioTrack}s.
	 *
	 * @param options Audio options to control the audio processing.
	 *
	 * @return The created audio source.
	 */
	public native AudioTrackSource createAudioSource(AudioOptions options);

	/**
	 * Creates an new {@link AudioTrack}. The audio track can be added to the
	 * {@link RTCPeerConnection} using the {@link RTCPeerConnection#addTrack
	 * addTrack} or {@link RTCPeerConnection#addTransceiver addTransceiver}
	 * methods.
	 *
	 * @param label  The identifier string of the audio track.
	 * @param source The audio source that provides audio data.
	 *
	 * @return The created audio track.
	 */
	public native AudioTrack createAudioTrack(String label, AudioTrackSource source);

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

	private native void initialize(AudioDeviceModule audioModule,
			AudioProcessing audioProcessing);

}
