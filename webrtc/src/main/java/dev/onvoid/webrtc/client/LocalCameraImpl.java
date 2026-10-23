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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import dev.onvoid.webrtc.media.video.VideoDevice;
import dev.onvoid.webrtc.media.video.VideoTrack;

/**
 * A camera track. A WebRTC track cannot change its source, so switching the
 * camera puts a new native track over the new camera's capture in place of the
 * old one, on the track and on every sender, and only then lets the old one go.
 */
final class LocalCameraImpl extends LocalVideoImpl implements LocalCamera {

	// Guarded by this.

	private LocalMediaImpl.SharedCamera camera;

	/** Replaced native tracks with their cameras, which late senders may still send. */
	private final List<LocalTrackImpl.Replaced> retiring = new ArrayList<>();


	LocalCameraImpl(LocalMediaImpl media, VideoTrack track, LocalMediaImpl.SharedCamera camera) {
		super(media, track, () -> media.releaseCamera(camera));

		this.camera = camera;
	}

	@Override
	public synchronized VideoDevice device() {
		return camera.options.device;
	}

	@Override
	public CompletableFuture<Void> switchCamera(VideoDevice device) {
		requireNonNull(device, "device");

		CompletableFuture<Void> switched = new CompletableFuture<>();
		media.rtc.track(switched, null);

		// Opening a camera can take a moment, so it happens off the caller's thread.
		media.executor.execute(() -> {
			try {
				switchTo(device);
				switched.complete(null);
			}
			catch (Throwable e) {
				switched.completeExceptionally(e instanceof WebRtcException
						? e : new MediaException("Switching to the camera " + device.getName() + " failed", e));
			}
		});

		return switched;
	}

	private void switchTo(VideoDevice device) {
		LocalMediaImpl.SharedCamera current;

		synchronized (this) {
			current = camera;
		}

		if (current.options.device.equals(device)) {
			return;
		}

		CameraOptions target = current.options.withDevice(device);
		LocalMediaImpl.SharedCamera next;

		try {
			// Opened first, so that peers see no gap.
			next = media.openCamera(target);
		}
		catch (MediaException beside) {
			// Some cameras cannot capture at the same time, such as two
			// sensors of one device. Unless another track shares the current
			// camera, it is let go first.
			if (!media.isOnlyUser(current)) {
				throw beside;
			}

			switchInstead(current, target);
			return;
		}

		WebRtcException notTaken = replace(next, true);

		if (notTaken != null) {
			throw notTaken;
		}
	}

	/**
	 * Switches after letting go of the current camera, which comes back if
	 * the new one fails. A track left without either camera ends.
	 */
	private void switchInstead(LocalMediaImpl.SharedCamera current, CameraOptions target) {
		// The track no longer owns the camera, so that it does not let go of
		// it a second time when it is released.
		takeRelease().run();

		MediaException failure = null;
		WebRtcException notTaken;

		try {
			LocalMediaImpl.SharedCamera next;

			try {
				next = media.openCamera(target);
			}
			catch (MediaException instead) {
				failure = instead;
				next = media.openCamera(current.options);
			}

			notTaken = replace(next, false);
		}
		catch (RuntimeException e) {
			closeInternal();

			MediaException ended = new MediaException("The track let go of the camera "
					+ current.options.device.getName() + " to switch, got no camera back, and ended", e);

			if (failure != null && failure != e) {
				ended.addSuppressed(failure);
			}

			throw ended;
		}

		if (failure != null) {
			if (notTaken != null) {
				failure.addSuppressed(notTaken);
			}

			throw failure;
		}
		if (notTaken != null) {
			throw notTaken;
		}
	}

	/**
	 * Puts a track over the camera in place of the current one, on this
	 * track and on every sender, and lets the old one go once every sender
	 * sends the new one. When not every sender does in time, the track goes
	 * back to the old camera if it can, and keeps the new one otherwise.
	 *
	 * @param canGoBack whether the old camera is still open.
	 *
	 * @return null once every sender sends the new track, else why not.
	 */
	private WebRtcException replace(LocalMediaImpl.SharedCamera next, boolean canGoBack) {
		VideoTrack nextTrack;

		try {
			nextTrack = rtc.factory.createVideoTrack("camera-" + UUID.randomUUID(), next.source);
		}
		catch (RuntimeException e) {
			media.releaseCamera(next);
			throw new MediaException("The track of the camera " + next.options.device.getName()
					+ " could not be created", e);
		}

		LocalMediaImpl.SharedCamera previous;
		LocalTrackImpl.Replaced replaced;

		synchronized (this) {
			previous = camera;
			replaced = replaceNative(nextTrack, () -> media.releaseCamera(next));

			if (replaced != null) {
				moveSinks((VideoTrack) replaced.track);
				camera = next;
			}
		}

		if (replaced == null) {
			// Closed meanwhile.
			nextTrack.dispose();
			media.releaseCamera(next);
			throw new MediaException("The camera track is closed");
		}

		CompletableFuture<Void> sent = send(replaced.senders);
		WebRtcException notTaken = await(sent, "Not every peer took the camera " + next.options.device.getName());

		if (notTaken != null && canGoBack && goBack(replaced, previous, sent)) {
			return notTaken;
		}

		retire(replaced, sent);

		return notTaken;
	}

	/**
	 * Puts the replaced track and camera back, on this track and on every
	 * sender, and lets go of the new ones.
	 *
	 * @return false if the track closed meanwhile, whose release then lets go
	 * of the new ones.
	 */
	private boolean goBack(LocalTrackImpl.Replaced replaced, LocalMediaImpl.SharedCamera previous,
			CompletableFuture<Void> sent) {
		LocalTrackImpl.Replaced undone;

		synchronized (this) {
			undone = replaceNative(replaced.track, replaced.release);

			if (undone != null) {
				moveSinks((VideoTrack) undone.track);
				camera = previous;
			}
		}

		if (undone == null) {
			return false;
		}

		// A sender that is late sends the old track anyway, as it reads the
		// track when its task runs.
		CompletableFuture<Void> back = send(undone.senders);
		WebRtcException notBack = await(back,
				"Not every peer went back to the camera " + previous.options.device.getName());

		if (notBack != null) {
			rtc.reportError(notBack);
		}

		retire(undone, CompletableFuture.allOf(sent, back));

		return true;
	}

	/**
	 * Lets go of a replaced native track and its camera once no sender uses
	 * them any more, or when this track is released, if that comes first.
	 * Late senders are done by then, as the senders detach from this track
	 * on the same executors.
	 */
	private void retire(LocalTrackImpl.Replaced replaced, CompletableFuture<Void> sent) {
		if (sent.isDone()) {
			retireNow(replaced);
			return;
		}

		synchronized (this) {
			retiring.add(replaced);
		}

		sent.whenComplete((ignored, error) -> media.executor.execute(() -> {
			boolean pending;

			synchronized (this) {
				pending = retiring.remove(replaced);
			}

			if (pending) {
				retireNow(replaced);
			}
		}));
	}

	private void retireNow(LocalTrackImpl.Replaced replaced) {
		try {
			letGo(replaced);
		}
		catch (Throwable e) {
			rtc.reportError(new WebRtcException("Releasing the camera track replaced failed", e));
		}
	}

	@Override
	void beforeRelease() {
		List<LocalTrackImpl.Replaced> left;

		synchronized (this) {
			left = new ArrayList<>(retiring);
			retiring.clear();
		}

		Throwable failure = attempt(null, super::beforeRelease);

		for (LocalTrackImpl.Replaced replaced : left) {
			failure = attempt(failure, () -> letGo(replaced));
		}

		if (failure instanceof RuntimeException) {
			throw (RuntimeException) failure;
		}
		if (failure instanceof Error) {
			throw (Error) failure;
		}
	}

	/**
	 * Makes the senders send the native track this track has now.
	 */
	private CompletableFuture<Void> send(List<SenderImpl> senders) {
		List<CompletableFuture<Void>> sending = new ArrayList<>();

		for (SenderImpl sender : senders) {
			sending.add(sender.trackReplaced(this));
		}

		return CompletableFuture.allOf(sending.toArray(new CompletableFuture[0]));
	}

	/**
	 * @return null once the senders send within the operation timeout, else
	 * why not.
	 */
	private WebRtcException await(CompletableFuture<Void> sent, String failure) {
		try {
			sent.get(rtc.operationTimeout.toMillis(), TimeUnit.MILLISECONDS);

			return null;
		}
		catch (ExecutionException e) {
			return new WebRtcException(failure, e.getCause());
		}
		catch (TimeoutException e) {
			return new WebRtcException(failure + " within " + rtc.operationTimeout, e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new WebRtcException(failure, e);
		}
	}

	private static void letGo(LocalTrackImpl.Replaced replaced) {
		try {
			replaced.track.dispose();
		}
		finally {
			// The camera holds a reference of its own, so it goes even when the track does not.
			replaced.release.run();
		}
	}
}
