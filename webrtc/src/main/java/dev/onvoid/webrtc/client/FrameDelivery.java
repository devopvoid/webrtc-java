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

/**
 * What a video frame subscription does with frames when its handler falls
 * behind. Frames that are dropped are released.
 */
public final class FrameDelivery {

	/** Keeps only the newest frame: the handler always gets the latest picture. */
	public static final FrameDelivery LATEST = new FrameDelivery(1);

	final int capacity;


	private FrameDelivery(int capacity) {
		this.capacity = capacity;
	}

	/**
	 * Queues frames, and drops the oldest one when the queue is full.
	 *
	 * @param capacity the number of frames the queue holds.
	 *
	 * @return the delivery.
	 */
	public static FrameDelivery queue(int capacity) {
		if (capacity < 1) {
			throw new IllegalArgumentException("The capacity must be 1 or more, got " + capacity);
		}

		return capacity == 1 ? LATEST : new FrameDelivery(capacity);
	}

	@Override
	public String toString() {
		return capacity == 1 ? "FrameDelivery.LATEST" : "FrameDelivery.queue(" + capacity + ")";
	}
}
