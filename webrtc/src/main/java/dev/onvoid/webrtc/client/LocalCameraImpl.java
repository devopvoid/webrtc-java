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
import java.util.concurrent.TimeUnit;

import dev.onvoid.webrtc.media.video.VideoDevice;
import dev.onvoid.webrtc.media.video.VideoTrack;

/**
 * A camera track. A WebRTC track cannot change its source, so switching the
 * camera puts a new native track over the new camera's capture in place of the
 * old one, on the track and on every sender, and only then lets the old one go.
 */
final class LocalCameraImpl extends LocalVideoImpl implements LocalCamera {

	/** Guarded by this. */
	private LocalMediaImpl.SharedCamera camera;


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

		// Opening a camera can take a moment, so it happens off the caller's thread.
		media.executor.execute(() -> {
			try {
				switchTo(device, switched);
			}
			catch (Throwable e) {
				switched.completeExceptionally(e instanceof WebRtcException
						? e : new MediaException("Switching to the camera " + device.getName() + " failed", e));
			}
		});

		return switched;
	}

	private void switchTo(VideoDevice device, CompletableFuture<Void> switched) {
		LocalMediaImpl.SharedCamera current;

		synchronized (this) {
			current = camera;
		}

		if (current.options.device.equals(device)) {
			switched.complete(null);
			return;
		}

		CameraOptions target = current.options.withDevice(device);
		LocalMediaImpl.SharedCamera next;
		boolean currentReleased = false;
		MediaException failure = null;

		try {
			// Opened first, so that peers see no gap.
			next = media.openCamera(target);
		}
		catch (MediaException beside) {
			// Some cameras cannot capture at the same time, such as two
			// sensors of one device. Unless another track shares the current
			// camera, it is let go first, and comes back if the new one fails.
			if (!media.isOnlyUser(current)) {
				throw beside;
			}

			media.releaseCamera(current);
			currentReleased = true;

			try {
				next = media.openCamera(target);
			}
			catch (MediaException instead) {
				failure = instead;
				next = media.openCamera(current.options);
			}
		}

		replace(next, currentReleased);

		if (failure != null) {
			switched.completeExceptionally(failure);
		}
		else {
			switched.complete(null);
		}
	}

	/**
	 * Puts a track over the camera in place of the current one, on this
	 * track and on every sender, and lets the old one go once no sender sends
	 * it any more.
	 */
	private void replace(LocalMediaImpl.SharedCamera next, boolean oldCameraReleased) {
		VideoTrack nextTrack;

		try {
			nextTrack = rtc.factory.createVideoTrack("camera-" + UUID.randomUUID(), next.source);
		}
		catch (RuntimeException e) {
			media.releaseCamera(next);
			throw new MediaException("The track of the camera " + next.options.device.getName()
					+ " could not be created", e);
		}

		LocalTrackImpl.Replaced replaced;

		synchronized (this) {
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

		List<CompletableFuture<Void>> sending = new ArrayList<>();

		for (SenderImpl sender : replaced.senders) {
			sending.add(sender.trackReplaced(this));
		}

		try {
			CompletableFuture.allOf(sending.toArray(new CompletableFuture[0]))
					.get(rtc.operationTimeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (Exception e) {
			rtc.reportError(new WebRtcException("Not every peer took the new camera in time", e));
		}

		replaced.track.dispose();

		if (!oldCameraReleased) {
			replaced.release.run();
		}
	}
}
