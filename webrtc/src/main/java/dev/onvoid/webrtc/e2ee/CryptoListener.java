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

package dev.onvoid.webrtc.e2ee;

/**
 * Hears of the streams whose frames are dropped, e.g. to ask the signaling
 * channel for a key that has not arrived.
 * <p>
 * Each RTP stream is reported when it begins to fail, and again only when it
 * fails for another reason, or after frames went through again in between,
 * so that a stream without its key does not report each of its frames. The
 * {@link CryptoStats statistics} count every frame.
 * <p>
 * The listener runs on the transform thread of the sender or receiver, and
 * holds up its frames while it runs: hand anything slow to another thread.
 *
 * @author Alex Andres
 */
@FunctionalInterface
public interface CryptoListener {

	/**
	 * Called when a stream begins to drop frames for a reason.
	 *
	 * @param event What fails, and why.
	 */
	void onFailure(CryptoEvent event);

}
