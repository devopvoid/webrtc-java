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
 * on the media executor whenever the system reports a change. The listener is
 * registered before the first read, so that a change during that read queues
 * another one rather than being missed, and the reads run one after another,
 * so that the last one to start is the one that stays. Where the system
 * reports no changes, as a Linux machine without PulseAudio, the lists are
 * read on each call instead.
 * <p>
 * A list the platform cannot read is empty: a machine without an audio system
 * has no microphones to offer. A query that fails otherwise throws a {@link
 * MediaException} when the list is read, and errors of the JVM pass through.
 */
final class DevicesImpl implements Devices {

	/**
	 * Where the devices, and the changes to them, come from. The runtime uses
	 * the system's, tests a source they control.
	 */
	interface Source {

		/** The devices of the system, through {@link MediaDevices}. */
		Source SYSTEM = new Source() {

			@Override
			public List<VideoDevice> cameras() {
				return MediaDevices.getVideoCaptureDevices();
			}

			@Override
			public List<AudioDevice> microphones() {
				return MediaDevices.getAudioCaptureDevices();
			}

			@Override
			public List<AudioDevice> speakers() {
				return MediaDevices.getAudioRenderDevices();
			}

			@Override
			public void watch(DeviceChangeListener listener) {
				MediaDevices.addDeviceChangeListener(listener);
			}

			@Override
			public void unwatch(DeviceChangeListener listener) {
				MediaDevices.removeDeviceChangeListener(listener);
			}
		};

		List<VideoDevice> cameras();

		List<AudioDevice> microphones();

		List<AudioDevice> speakers();

		void watch(DeviceChangeListener listener);

		void unwatch(DeviceChangeListener listener);

	}


	private final LocalMediaImpl media;

	private final AudioDeviceModuleBase audioModule;

	private final Source source;

	private final Handlers<Devices> handlers;

	private final DeviceChangeListener listener;

	/** True while the system reports changes, which keep the snapshots current. */
	private final boolean watching;

	// Written in refresh(), under this.

	private volatile Snapshot<VideoDevice> cameras = Snapshot.empty();

	private volatile Snapshot<AudioDevice> microphones = Snapshot.empty();

	private volatile Snapshot<AudioDevice> speakers = Snapshot.empty();


	DevicesImpl(LocalMediaImpl media, AudioDeviceModuleBase audioModule) {
		this(media, audioModule, Source.SYSTEM);
	}

	DevicesImpl(LocalMediaImpl media, AudioDeviceModuleBase audioModule, Source source) {
		this.media = media;
		this.audioModule = audioModule;
		this.source = source;
		this.handlers = new Handlers<>(media.rtc::reportError);
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

		// Listening first: a change from here on queues a read behind this
		// one, which then reflects it.
		this.watching = watch();

		if (watching) {
			try {
				refresh();
			}
			catch (Throwable e) {
				// An error of the JVM. No one would remove the listener.
				unwatch(e);
				throw e;
			}
		}
	}

	@Override
	public List<VideoDevice> cameras() {
		return (watching ? cameras : read("cameras", source::cameras)).get();
	}

	@Override
	public List<AudioDevice> microphones() {
		return (watching ? microphones : read("microphones", source::microphones)).get();
	}

	@Override
	public List<AudioDevice> speakers() {
		return (watching ? speakers : read("speakers", source::speakers)).get();
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

			switchDevice(() -> audioModule.setRecordingDevice(device),
					audioModule::stopRecording, () -> {
						audioModule.initRecording();
						audioModule.startRecording();
					});
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

			switchDevice(() -> audioModule.setPlayoutDevice(device),
					audioModule::stopPlayout, () -> {
						audioModule.initPlayout();
						audioModule.startPlayout();
					});
			return null;
		});
	}

	@Override
	public Subscription onChange(Consumer<Devices> handler) {
		return handlers.add(requireNonNull(handler, "handler"));
	}

	void dispose() {
		if (watching) {
			source.unwatch(listener);
		}
		handlers.clear();
	}

	/**
	 * Selects a device. Most platforms switch only while the module is stopped,
	 * so a refused switch is tried again with the module stopped, and the
	 * module is started again whether that works or not: a switch that fails
	 * leaves it on the device it had.
	 * <p>
	 * A device that is no longer there fails at once. Stopping the module
	 * would not bring it back.
	 * <p>
	 * Of what fails after the refusal, the stop or the second selection is
	 * thrown, else the restart. The other failures, the refusal among them,
	 * are suppressed in it.
	 */
	static void switchDevice(Runnable select, Runnable stop, Runnable start) {
		try {
			select.run();
			return;
		}
		catch (IllegalArgumentException e) {
			throw e;
		}
		catch (Throwable refused) {
			Throwable failure = null;

			try {
				stop.run();
				select.run();
			}
			catch (Throwable e) {
				failure = e;
			}

			try {
				start.run();
			}
			catch (Throwable e) {
				failure = suppress(failure, e);
			}

			if (failure != null) {
				throw DevicesImpl.<RuntimeException>sneakyThrow(suppress(failure, refused));
			}
		}
	}

	/**
	 * Tells whether a query failed in the platform, which the native layer
	 * reports as a plain {@code Error} with the message of the C++ exception,
	 * as when there is no audio system. Its subclasses are errors of the JVM,
	 * such as an {@code OutOfMemoryError} or an {@code UnsatisfiedLinkError}.
	 */
	static boolean platformFailure(Throwable e) {
		return e.getClass() == Error.class;
	}

	/** Called on a thread of the system, so it only posts. */
	private void changed() {
		media.executor.execute(() -> {
			refresh();
			media.events.execute(() -> handlers.accept(this));
		});
	}

	/**
	 * Reads all lists. One read waits for another, so that an older read
	 * never overwrites one the listener queued after it.
	 */
	private synchronized void refresh() {
		cameras = read("cameras", source::cameras);
		microphones = read("microphones", source::microphones);
		speakers = read("speakers", source::speakers);
	}

	private static <T> Snapshot<T> read(String kind, Supplier<List<T>> query) {
		try {
			List<T> devices = query.get();

			return devices == null
					? Snapshot.empty()
					: new Snapshot<>(Collections.unmodifiableList(new ArrayList<>(devices)), kind, null);
		}
		catch (Throwable e) {
			if (platformFailure(e)) {
				// No device of the kind, such as without an audio system.
				return Snapshot.empty();
			}
			if (e instanceof Error) {
				throw (Error) e;
			}

			return new Snapshot<>(Collections.emptyList(), kind, e);
		}
	}

	private boolean watch() {
		try {
			source.watch(listener);
			return true;
		}
		catch (Throwable e) {
			if (platformFailure(e)) {
				// The system reports no changes, as Linux without PulseAudio.
				return false;
			}
			if (e instanceof Error) {
				throw (Error) e;
			}

			media.rtc.reportError(new MediaException("Watching the devices failed, so they are read on each call: "
					+ e.getMessage(), e));
			return false;
		}
	}

	private void unwatch(Throwable failure) {
		try {
			source.unwatch(listener);
		}
		catch (Throwable e) {
			suppress(failure, e);
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

	/**
	 * @return the failure with the other one suppressed in it, or the other
	 * one if there is no failure yet.
	 */
	private static Throwable suppress(Throwable failure, Throwable other) {
		if (failure == null) {
			return other;
		}
		if (other != failure) {
			failure.addSuppressed(other);
		}

		return failure;
	}

	/**
	 * Throws a throwable as it is, also a checked one that a native method
	 * threw without declaring it.
	 */
	@SuppressWarnings("unchecked")
	private static <E extends Throwable> E sneakyThrow(Throwable e) throws E {
		throw (E) e;
	}

	/**
	 * Runs a switch, which uses the audio module, on the media executor. It
	 * is registered like the creation of a track, so that a runtime that
	 * begins to close either waits for it before it frees the module, or is
	 * seen closing here. The future handed out completes on the event
	 * executor, so that the application's callbacks never hold up the media
	 * executor.
	 */
	private <T> CompletableFuture<T> onMediaExecutor(Supplier<T> action) {
		CompletableFuture<T> result = new CompletableFuture<>();
		LocalMediaImpl.Creation operation;

		try {
			operation = media.begin();
		}
		catch (PeerClosedException e) {
			result.completeExceptionally(e);
			return result;
		}

		media.rtc.track(result, null);

		media.executor.execute(() -> {
			try {
				result.complete(action.get());
			}
			catch (Throwable e) {
				result.completeExceptionally(e instanceof WebRtcException
						? e : new MediaException("Switching the device failed: " + e.getMessage(), e));
			}
			finally {
				operation.close();
			}
		}, () -> {
			operation.close();
			result.completeExceptionally(new PeerClosedException("The runtime is closed"));
		});

		return media.toPublic(result);
	}

	/**
	 * A list as it was read, or the failure of its query. The failure is
	 * thrown anew on each call, so that callers share no exception.
	 */
	private static final class Snapshot<T> {

		private static final Snapshot<?> EMPTY = new Snapshot<>(Collections.emptyList(), null, null);

		private final List<T> devices;

		private final String kind;

		private final Throwable failure;


		Snapshot(List<T> devices, String kind, Throwable failure) {
			this.devices = devices;
			this.kind = kind;
			this.failure = failure;
		}

		@SuppressWarnings("unchecked")
		static <T> Snapshot<T> empty() {
			return (Snapshot<T>) EMPTY;
		}

		List<T> get() {
			if (failure != null) {
				throw new MediaException("Reading the " + kind + " failed: " + failure.getMessage(), failure);
			}

			return devices;
		}
	}
}
