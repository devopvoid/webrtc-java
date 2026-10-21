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
import java.util.concurrent.CompletableFuture;

import dev.onvoid.webrtc.media.MediaStreamTrack;

/**
 * A {@link LocalTrack} of a {@link WebRtc} runtime.
 * <p>
 * Calls on the native track and source happen under this object's lock and
 * only while the track has not ended, so that closing never races with them.
 * Closing first makes every sender let go of the track, each on its peer's
 * control executor, and only then releases the native track and source.
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

	private final CompletableFuture<Void> closed = new CompletableFuture<>();

	private volatile boolean enabled = true;

	// Guarded by this.

	private final List<SenderImpl> senders = new ArrayList<>();

	private final List<Runnable> endedHandlers = new ArrayList<>();

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
				endedHandlers.add(handler);

				return () -> {
					synchronized (LocalTrackImpl.this) {
						endedHandlers.remove(handler);
					}
				};
			}
		}

		media.executor.execute(handler);

		return () -> { };
	}

	@Override
	public synchronized boolean isEnded() {
		return ended;
	}

	@Override
	public CompletableFuture<Void> closeAsync() {
		return closeInternal();
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

	/**
	 * @return true while the native track may be used, which callers check
	 * under this object's lock.
	 */
	boolean isLive() {
		return !ended;
	}

	CompletableFuture<Void> closeInternal() {
		List<SenderImpl> using;

		synchronized (this) {
			if (ended) {
				return closed;
			}

			ended = true;
			using = new ArrayList<>(senders);
			senders.clear();
		}

		List<CompletableFuture<Void>> detached = new ArrayList<>();

		for (SenderImpl sender : using) {
			detached.add(sender.trackClosed(this));
		}

		CompletableFuture.allOf(detached.toArray(new CompletableFuture[0]))
				.whenComplete((ignored, error) -> media.executor.execute(this::release));

		return closed;
	}

	/**
	 * Called once, before the native track is released, while no other call
	 * uses it.
	 */
	void beforeRelease() {
	}

	private void release() {
		try {
			beforeRelease();

			MediaStreamTrack last;
			Runnable lastRelease;

			synchronized (this) {
				last = track;
				lastRelease = releaseSource;
			}

			last.dispose();
			lastRelease.run();
		}
		catch (Throwable e) {
			rtc.reportError(e);
		}

		media.forget(this);

		List<Runnable> handlers;

		synchronized (this) {
			endFired = true;
			handlers = new ArrayList<>(endedHandlers);
			endedHandlers.clear();
		}

		for (Runnable handler : handlers) {
			try {
				handler.run();
			}
			catch (Throwable e) {
				rtc.reportError(e);
			}
		}

		closed.complete(null);
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
