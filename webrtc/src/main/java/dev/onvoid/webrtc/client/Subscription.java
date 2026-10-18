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
 * A registered handler. Closing it removes the handler; closing it again does
 * nothing. A handler is also removed when the object it listens to closes.
 */
@FunctionalInterface
public interface Subscription extends AutoCloseable {

	/**
	 * Removes the handler. It may still be called once if a call was already
	 * on its way.
	 */
	@Override
	void close();

}
