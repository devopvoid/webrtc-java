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
 * A subscription handed out before its handler is registered on another
 * thread. Closing it before then keeps the handler from being registered.
 */
final class DeferredSubscription implements Subscription {

	private Subscription registered;

	private boolean closed;


	synchronized void set(Subscription subscription) {
		if (closed) {
			subscription.close();
		}
		else {
			registered = subscription;
		}
	}

	@Override
	public void close() {
		Subscription subscription;

		synchronized (this) {
			if (closed) {
				return;
			}

			closed = true;
			subscription = registered;
			registered = null;
		}

		if (subscription != null) {
			subscription.close();
		}
	}
}
