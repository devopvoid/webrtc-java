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

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Sends one track on one peer, made by {@link Peer#addTrack}.
 */
public interface Sender {

	/**
	 * @return the track sent now, or null if nothing is sent.
	 */
	LocalTrack track();

	/**
	 * Sends another track of the same kind, without renegotiating.
	 *
	 * @param track the track to send, or null to send nothing.
	 *
	 * @return a future that completes once the track is replaced.
	 */
	CompletableFuture<Void> replaceTrack(LocalTrack track);

	/**
	 * Changes how the track is encoded. Values left unset stay as they are.
	 *
	 * @param encoding the values to change.
	 *
	 * @return a future that completes once the values are applied.
	 */
	CompletableFuture<Void> configure(Encoding encoding);

	/**
	 * Sends DTMF tones on an audio sender, 100 ms each with 70 ms between
	 * them. See {@link #sendTones(String, Duration, Duration)}.
	 *
	 * @param tones the tones to send.
	 *
	 * @return a future that completes once the tones have been sent.
	 */
	CompletableFuture<Void> sendTones(String tones);

	/**
	 * Sends DTMF tones on an audio sender, as telephone systems expect. The
	 * characters 0 to 9, A to D, # and * are tones, and a comma is a pause of
	 * 2 seconds; other characters are skipped. Tones still queued from an
	 * earlier call are replaced, and the future of that call is cancelled.
	 *
	 * @param tones    the tones to send.
	 * @param duration how long each tone sounds, 40 ms to 6 seconds.
	 * @param gap      the pause between two tones, at least 30 ms.
	 *
	 * @return a future that completes once the tones have been sent. It fails
	 * with an {@link IllegalStateException} if the sender cannot send tones,
	 * because it sends video or the remote peer does not accept them.
	 */
	CompletableFuture<Void> sendTones(String tones, Duration duration, Duration gap);

	/**
	 * Calls the handler as each DTMF tone starts, and with an empty string once
	 * all queued tones have been sent.
	 *
	 * @param handler called on the peer's event thread.
	 *
	 * @return the subscription that removes the handler.
	 */
	Subscription onTone(Consumer<String> handler);

	/**
	 * Stops sending. With {@link Negotiation#AUTOMATIC} the peer renegotiates.
	 * The track stays open.
	 *
	 * @return a future that completes once the sender is removed.
	 */
	CompletableFuture<Void> remove();

}
