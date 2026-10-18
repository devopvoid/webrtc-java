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

package dev.onvoid.webrtc.client;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import dev.onvoid.webrtc.media.MediaDevices;
import dev.onvoid.webrtc.media.audio.AudioDeviceModuleBase;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.VideoCaptureCapability;
import dev.onvoid.webrtc.media.video.VideoDesktopSource;
import dev.onvoid.webrtc.media.video.VideoDevice;
import dev.onvoid.webrtc.media.video.VideoDeviceSource;
import dev.onvoid.webrtc.media.video.VideoTrack;

/**
 * The {@link LocalMedia} of a {@link WebRtc} runtime. It keeps every track it
 * made, to close them with the runtime, and shares one capture per camera.
 */
final class LocalMediaImpl implements LocalMedia {

	final WebRtc rtc;

	/** Releases tracks and runs their ended handlers, off WebRTC's and the application's threads. */
	final SerialExecutor executor;

	private final AudioDeviceModuleBase audioModule;

	private final Set<LocalTrackImpl> tracks = Collections.newSetFromMap(new ConcurrentHashMap<>());

	// Guarded by cameras.

	private final Map<String, SharedCamera> cameras = new HashMap<>();

	// Guarded by this.

	private DevicesImpl devices;


	LocalMediaImpl(WebRtc rtc, AudioDeviceModuleBase audioModule) {
		this.rtc = rtc;
		this.audioModule = audioModule;
		this.executor = new SerialExecutor(rtc.controlPool, rtc::reportError, false, null);
	}

	@Override
	public synchronized Devices devices() {
		rtc.checkOpen();

		if (devices == null) {
			devices = new DevicesImpl(this, audioModule);
		}

		return devices;
	}

	@Override
	public LocalVideo camera() {
		return camera(CameraOptions.builder().build());
	}

	@Override
	public LocalVideo camera(CameraOptions options) {
		requireNonNull(options, "options");
		rtc.checkOpen();

		VideoDevice device = options.device;

		if (device == null) {
			List<VideoDevice> available = devices().cameras();

			if (available.isEmpty()) {
				throw new MediaException("No camera was found");
			}

			device = available.get(0);
		}

		CameraOptions resolved = options.withDevice(device);
		SharedCamera camera = openCamera(resolved);

		try {
			VideoTrack track = rtc.factory.createVideoTrack(newId("camera"), camera.source);

			return register(new LocalVideoImpl(this, track, () -> releaseCamera(camera)));
		}
		catch (RuntimeException e) {
			releaseCamera(camera);
			throw new MediaException("The track of the camera " + device.getName() + " could not be created", e);
		}
	}

	@Override
	public LocalAudio microphone() {
		AudioOptions options = new AudioOptions();
		options.echoCancellation = true;
		options.noiseSuppression = true;
		options.autoGainControl = true;
		options.highpassFilter = true;

		return microphone(options);
	}

	@Override
	public LocalAudio microphone(AudioOptions options) {
		requireNonNull(options, "options");
		rtc.checkOpen();

		if (rtc.audioMode != AudioMode.DEVICES) {
			throw new ConfigurationException("The runtime was built with AudioMode." + rtc.audioMode
					+ ", which sends pushed audio only. Build it with AudioMode.DEVICES to capture the microphone, "
					+ "or use customAudio() to push audio");
		}

		AudioTrackSource source = rtc.factory.createAudioSource(options);

		try {
			AudioTrack track = rtc.factory.createAudioTrack(newId("microphone"), source);

			return register(new LocalAudioImpl(this, track, source::dispose));
		}
		catch (RuntimeException e) {
			source.dispose();
			throw new MediaException("The microphone track could not be created", e);
		}
	}

	@Override
	public LocalVideo screen(ScreenOptions options) {
		requireNonNull(options, "options");
		rtc.checkOpen();

		VideoDesktopSource source = new VideoDesktopSource();

		try {
			source.setSourceId(options.source.id, options.window);
			source.setFrameRate(options.frameRate);

			if (options.maxWidth > 0) {
				source.setMaxFrameSize(options.maxWidth, options.maxHeight);
			}

			source.start();

			VideoTrack track = rtc.factory.createVideoTrack(newId(options.window ? "window" : "screen"), source);

			return register(new LocalVideoImpl(this, track, () -> {
				source.stop();
				source.dispose();
			}));
		}
		catch (RuntimeException e) {
			source.dispose();
			throw new MediaException("Capturing " + options.source.title + " failed: " + e.getMessage(), e);
		}
	}

	@Override
	public CustomVideo customVideo(String label) {
		requireNonNull(label, "label");
		rtc.checkOpen();

		CustomVideoSource source = new CustomVideoSource();

		try {
			VideoTrack track = rtc.factory.createVideoTrack(label, source);

			return register(new CustomVideoImpl(this, track, source));
		}
		catch (RuntimeException e) {
			source.dispose();
			throw e;
		}
	}

	@Override
	public CustomAudio customAudio(String label) {
		requireNonNull(label, "label");
		rtc.checkOpen();

		if (rtc.audioMode == AudioMode.DEVICES) {
			throw new ConfigurationException("The runtime was built with AudioMode.DEVICES, which sends the "
					+ "microphone. WebRTC feeds captured audio into every audio sender of a runtime, so pushed "
					+ "audio needs a runtime built with AudioMode.CUSTOM or AudioMode.HEADLESS");
		}

		CustomAudioSource source = new CustomAudioSource();

		try {
			AudioTrack track = rtc.factory.createAudioTrack(label, source);

			return register(new CustomAudioImpl(this, track, source));
		}
		catch (RuntimeException e) {
			source.dispose();
			throw e;
		}
	}

	List<CompletableFuture<Void>> closeAll() {
		List<CompletableFuture<Void>> closing = new ArrayList<>();

		for (LocalTrackImpl track : new ArrayList<>(tracks)) {
			closing.add(track.closeInternal());
		}

		return closing;
	}

	synchronized void dispose() {
		if (devices != null) {
			devices.dispose();
			devices = null;
		}
	}

	void forget(LocalTrackImpl track) {
		tracks.remove(track);
	}

	private <T extends LocalTrackImpl> T register(T track) {
		tracks.add(track);

		if (rtc.isClosing()) {
			// The runtime began to close meanwhile and may have missed it.
			track.closeInternal();
		}

		return track;
	}

	private SharedCamera openCamera(CameraOptions options) {
		String key = options.device.getDescriptor();

		synchronized (cameras) {
			SharedCamera camera = cameras.get(key);

			if (camera != null) {
				if (!camera.options.equals(options)) {
					throw new MediaException("The camera " + options.device.getName() + " is already open with "
							+ camera.options + ". Open it with the same options to share it");
				}

				camera.users++;
				return camera;
			}

			VideoDeviceSource source = new VideoDeviceSource();

			try {
				source.setVideoCaptureDevice(options.device);

				VideoCaptureCapability capability = nearest(
						MediaDevices.getVideoCaptureCapabilities(options.device), options);

				if (capability != null) {
					source.setVideoCaptureCapability(capability);
				}

				source.start();
			}
			catch (Throwable e) {
				source.dispose();
				throw new MediaException("The camera " + options.device.getName() + " could not be opened: "
						+ e.getMessage(), e);
			}

			camera = new SharedCamera(key, options, source);
			camera.users = 1;
			cameras.put(key, camera);

			return camera;
		}
	}

	private void releaseCamera(SharedCamera camera) {
		synchronized (cameras) {
			if (--camera.users > 0) {
				return;
			}

			cameras.remove(camera.key);
		}

		camera.source.stop();
		camera.source.dispose();
	}

	private static VideoCaptureCapability nearest(List<VideoCaptureCapability> capabilities,
			CameraOptions options) {
		if (capabilities == null || capabilities.isEmpty()) {
			return null;
		}

		VideoCaptureCapability best = capabilities.get(0);
		long bestScore = Long.MAX_VALUE;

		for (VideoCaptureCapability capability : capabilities) {
			long score = 0;

			if (options.width > 0) {
				score += 1000L * (Math.abs(capability.width - options.width)
						+ Math.abs(capability.height - options.height));
			}
			if (options.frameRate > 0) {
				score += Math.abs(capability.frameRate - options.frameRate);
			}
			if (score < bestScore) {
				best = capability;
				bestScore = score;
			}
		}

		return best;
	}

	private static String newId(String kind) {
		return kind + "-" + UUID.randomUUID();
	}



	private static final class SharedCamera {

		final String key;

		final CameraOptions options;

		final VideoDeviceSource source;

		int users;


		SharedCamera(String key, CameraOptions options, VideoDeviceSource source) {
			this.key = key;
			this.options = options;
			this.source = source;
		}
	}
}
