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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import dev.onvoid.webrtc.media.MediaStreamTrack;

/**
 * A {@link LocalTrack} of a {@link WebRtc} runtime.
 * <p>
 * Calls on the native track and source happen under this object's lock and
 * only while the track has not ended, so that closing never races with them.
 * Closing first makes every sender let go of the track, each on its peer's
 * control executor, and only then releases the native track and source. The
 * ended handlers and the futures handed out follow on the media's event
 * executor, so that neither holds up a release nor the runtime's close.
 */
abstract class LocalTrackImpl implements LocalTrack {

	final WebRtc rtc;

	final LocalMediaImpl media;

	/** Replaced when a camera switches; guarded by this, read by senders. */
	private volatile MediaStreamTrack track;

	private final MediaKind kind;

	private final String id;

	/** Guarded by this. */
	private Runnable releaseSource;

	/**
	 * Completed on the media executor once the native track is released,
	 * before the handlers hear of it. Never handed out, so that no caller can
	 * cancel what the runtime waits for.
	 */
	private final CompletableFuture<Void> closedInternal = new CompletableFuture<>();

	private volatile boolean enabled = true;

	// Guarded by this.

	/** A sender registers once, however often it is given this track. */
	private final Set<SenderImpl> senders = new LinkedHashSet<>();

	private final List<EndedHandler> endedHandlers = new ArrayList<>();

	private boolean ended;

	private boolean endFired;


	LocalTrackImpl(LocalMediaImpl media, MediaStreamTrack track, MediaKind kind,
			Runnable releaseSource) {
		this.rtc = media.rtc;
		this.media = media;
		this.track = track;
		this.kind = kind;
		this.id = track.getId();
		this.releaseSource = releaseSource;
	}

	@Override
	public String id() {
		return id;
	}

	@Override
	public MediaKind kind() {
		return kind;
	}

	@Override
	public boolean isEnabled() {
		return enabled;
	}

	@Override
	public synchronized void setEnabled(boolean enabled) {
		if (ended) {
			return;
		}

		track.setEnabled(enabled);
		this.enabled = enabled;
	}

	@Override
	public Subscription onEnded(Runnable handler) {
		requireNonNull(handler, "handler");

		synchronized (this) {
			if (!endFired) {
				EndedHandler entry = new EndedHandler(handler);
				endedHandlers.add(entry);

				return () -> {
					synchronized (LocalTrackImpl.this) {
						endedHandlers.remove(entry);
					}
				};
			}
		}

		media.events.execute(handler);

		return () -> { };
	}

	@Override
	public synchronized boolean isEnded() {
		return ended;
	}

	@Override
	public CompletableFuture<Void> closeAsync() {
		return media.toPublic(closeInternal());
	}

	@Override
	public void close() {
		closeInternal();
	}

	@Override
	public String toString() {
		return getClass().getSimpleName().replace("Impl", "") + "[" + id + "]";
	}

	MediaStreamTrack nativeTrack() {
		return track;
	}

	/**
	 * Puts another native track in place of the current one, keeping whether
	 * it is enabled.
	 *
	 * @return the track and release replaced, and the senders that send it now;
	 * null once the track ended.
	 */
	synchronized Replaced replaceNative(MediaStreamTrack next, Runnable nextRelease) {
		if (ended) {
			return null;
		}

		Replaced replaced = new Replaced(track, releaseSource, new ArrayList<>(senders));

		next.setEnabled(enabled);
		track = next;
		releaseSource = nextRelease;

		return replaced;
	}

	/**
	 * Takes the release of the source from the track, which then no longer
	 * lets go of the source, even once it ended.
	 *
	 * @return the release taken.
	 */
	synchronized Runnable takeRelease() {
		Runnable taken = releaseSource;
		releaseSource = () -> { };

		return taken;
	}

	synchronized boolean register(SenderImpl sender) {
		if (ended) {
			return false;
		}

		senders.add(sender);
		return true;
	}

	synchronized void unregister(SenderImpl sender) {
		senders.remove(sender);
	}

	synchronized List<SenderImpl> registeredSenders() {
		return new ArrayList<>(senders);
	}

	/**
	 * @return true while the native track may be used, which callers check
	 * under this object's lock.
	 */
	boolean isLive() {
		return !ended;
	}

	/**
	 * Starts closing, once.
	 *
	 * @return a future that completes on the media executor once the native
	 * track is released, before the handlers hear of it.
	 */
	CompletableFuture<Void> closeInternal() {
		List<SenderImpl> using;

		synchronized (this) {
			if (ended) {
				return closedInternal;
			}

			ended = true;
			using = new ArrayList<>(senders);
		}

		List<CompletableFuture<Void>> detached = new ArrayList<>();

		for (SenderImpl sender : using) {
			detached.add(sender.trackClosed(this));
		}

		CompletableFuture.allOf(detached.toArray(new CompletableFuture[0]))
				.whenComplete((ignored, error) -> {
					Runnable finish = () -> release(error);
					media.executor.execute(finish, () -> finishRelease(new PeerClosedException("The runtime is closed")));
				});

		return closedInternal;
	}

	/**
	 * Called once, before the native track is released, while no other call
	 * uses it.
	 */
	void beforeRelease() {
	}

	private void release(Throwable detachFailure) {
		// A sender may still use the track and its source. Keep both alive
		// until the runtime can account for the failed close.
		if (detachFailure != null) {
			finishRelease(detachFailure);
			return;
		}

		MediaStreamTrack last;
		Runnable lastRelease;

		synchronized (this) {
			last = track;
			lastRelease = releaseSource;
		}

		// Each step is tried, whether or not the one before failed: the source
		// holds a reference of its own and is released even when the track is not.
		Throwable failure = attempt(null, this::beforeRelease);
		failure = attempt(failure, last::dispose);
		failure = attempt(failure, lastRelease);

		finishRelease(failure);
	}

	private void finishRelease(Throwable failure) {
		if (failure == null) {
			media.forget(this);
		}
		else {
			// Retained so runtime shutdown sees the failure and keeps the factory.
			failure = new WebRtcException("Releasing " + this + " failed", failure);
			rtc.reportError(failure);
		}

		List<EndedHandler> handlers;

		synchronized (this) {
			endFired = true;
			handlers = new ArrayList<>(endedHandlers);
			endedHandlers.clear();
		}

		// Queued before the release completes, so that the futures handed out
		// complete after the handlers ran, and the runtime cannot stop the
		// event executor in between.
		media.events.execute(() -> {
			for (EndedHandler handler : handlers) {
				try {
					handler.handler.run();
				}
				catch (Throwable e) {
					rtc.reportError(e);
				}
			}
		});

		if (failure == null) {
			closedInternal.complete(null);
		}
		else {
			closedInternal.completeExceptionally(failure);
		}
	}

	/**
	 * Runs a step of the release.
	 *
	 * @return the failure so far, with the step's added to it.
	 */
	static Throwable attempt(Throwable failure, Runnable step) {
		try {
			step.run();
		}
		catch (Throwable e) {
			if (failure == null) {
				return e;
			}

			if (failure != e) {
				failure.addSuppressed(e);
			}
		}

		return failure;
	}



	private static final class EndedHandler {

		final Runnable handler;


		EndedHandler(Runnable handler) {
			this.handler = handler;
		}
	}


	/** What {@link #replaceNative} replaced. */
	static final class Replaced {

		final MediaStreamTrack track;

		final Runnable release;

		final List<SenderImpl> senders;


		Replaced(MediaStreamTrack track, Runnable release, List<SenderImpl> senders) {
			this.track = track;
			this.release = release;
			this.senders = senders;
		}
	}
}
