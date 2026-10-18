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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.onvoid.webrtc.media.Device;
import dev.onvoid.webrtc.media.DeviceChangeListener;
import dev.onvoid.webrtc.media.MediaDevices;
import dev.onvoid.webrtc.media.audio.AudioDevice;
import dev.onvoid.webrtc.media.audio.AudioDeviceModuleBase;
import dev.onvoid.webrtc.media.video.VideoDevice;
import dev.onvoid.webrtc.media.video.desktop.DesktopCapturer;
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer;
import dev.onvoid.webrtc.media.video.desktop.WindowCapturer;

/**
 * The {@link Devices} of a runtime. The device lists are read once, then again
 * on the media executor whenever the system reports a change.
 */
final class DevicesImpl implements Devices {

	private final LocalMediaImpl media;

	private final AudioDeviceModuleBase audioModule;

	private final Handlers<Devices> handlers;

	private final DeviceChangeListener listener;

	private volatile List<VideoDevice> cameras = Collections.emptyList();

	private volatile List<AudioDevice> microphones = Collections.emptyList();

	private volatile List<AudioDevice> speakers = Collections.emptyList();


	DevicesImpl(LocalMediaImpl media, AudioDeviceModuleBase audioModule) {
		this.media = media;
		this.audioModule = audioModule;
		this.handlers = new Handlers<>(media.rtc::reportError);

		refresh();

		this.listener = new DeviceChangeListener() {

			@Override
			public void deviceConnected(Device device) {
				changed();
			}

			@Override
			public void deviceDisconnected(Device device) {
				changed();
			}
		};

		MediaDevices.addDeviceChangeListener(listener);
	}

	@Override
	public List<VideoDevice> cameras() {
		return cameras;
	}

	@Override
	public List<AudioDevice> microphones() {
		return microphones;
	}

	@Override
	public List<AudioDevice> speakers() {
		return speakers;
	}

	@Override
	public List<DesktopSource> screens() {
		return desktopSources(new ScreenCapturer());
	}

	@Override
	public List<DesktopSource> windows() {
		return desktopSources(new WindowCapturer());
	}

	@Override
	public CompletableFuture<Void> useMicrophone(AudioDevice device) {
		requireNonNull(device, "device");

		return onMediaExecutor(() -> {
			if (media.rtc.audioMode != AudioMode.DEVICES) {
				throw new ConfigurationException("A runtime built with AudioMode." + media.rtc.audioMode
						+ " captures no microphone");
			}

			try {
				audioModule.setRecordingDevice(device);
			}
			catch (Throwable e) {
				// Most platforms switch only while not recording.
				audioModule.stopRecording();
				audioModule.setRecordingDevice(device);
				audioModule.initRecording();
				audioModule.startRecording();
			}
			return null;
		});
	}

	@Override
	public CompletableFuture<Void> useSpeaker(AudioDevice device) {
		requireNonNull(device, "device");

		return onMediaExecutor(() -> {
			if (media.rtc.audioMode == AudioMode.HEADLESS) {
				throw new ConfigurationException("A runtime built with AudioMode.HEADLESS plays to no speakers");
			}

			try {
				audioModule.setPlayoutDevice(device);
			}
			catch (Throwable e) {
				// Most platforms switch only while not playing.
				audioModule.stopPlayout();
				audioModule.setPlayoutDevice(device);
				audioModule.initPlayout();
				audioModule.startPlayout();
			}
			return null;
		});
	}

	@Override
	public Subscription onChange(Consumer<Devices> handler) {
		return handlers.add(requireNonNull(handler, "handler"));
	}

	void dispose() {
		MediaDevices.removeDeviceChangeListener(listener);
		handlers.clear();
	}

	/** Called on a thread of the system, so it only posts. */
	private void changed() {
		media.executor.execute(() -> {
			refresh();
			handlers.accept(this);
		});
	}

	private void refresh() {
		cameras = read(MediaDevices::getVideoCaptureDevices);
		microphones = read(MediaDevices::getAudioCaptureDevices);
		speakers = read(MediaDevices::getAudioRenderDevices);
	}

	private <T> List<T> read(Supplier<List<T>> query) {
		try {
			List<T> devices = query.get();

			return devices == null
					? Collections.<T>emptyList()
					: Collections.unmodifiableList(new ArrayList<>(devices));
		}
		catch (Throwable e) {
			media.rtc.reportError(new MediaException("Reading the devices failed", e));
			return Collections.emptyList();
		}
	}

	private static List<DesktopSource> desktopSources(DesktopCapturer capturer) {
		try {
			List<DesktopSource> sources = capturer.getDesktopSources();

			return sources == null
					? Collections.<DesktopSource>emptyList()
					: Collections.unmodifiableList(new ArrayList<>(sources));
		}
		catch (Throwable e) {
			throw new MediaException("Reading the capture sources failed: " + e.getMessage(), e);
		}
		finally {
			capturer.dispose();
		}
	}

	private <T> CompletableFuture<T> onMediaExecutor(Supplier<T> action) {
		CompletableFuture<T> result = new CompletableFuture<>();

		media.executor.execute(() -> {
			try {
				result.complete(action.get());
			}
			catch (Throwable e) {
				result.completeExceptionally(e instanceof WebRtcException
						? e : new MediaException("Switching the device failed: " + e.getMessage(), e));
			}
		});

		return result;
	}
}
