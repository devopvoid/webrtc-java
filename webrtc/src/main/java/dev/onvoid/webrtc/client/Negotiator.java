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

import java.util.concurrent.CompletableFuture;

import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCSessionDescription;

/**
 * The offer and answer exchange by hand, for peers built with {@link
 * Negotiation#MANUAL}. Each step creates and applies a description in one go,
 * so nothing can change in between. Steps run in the order they were called.
 */
public interface Negotiator {

	/**
	 * Creates the local offer and applies it.
	 *
	 * @return a future with the offer, at once.
	 */
	CompletableFuture<RTCSessionDescription> createOffer();

	/**
	 * Creates the local offer and applies it.
	 *
	 * @param gathering {@link Gathering#COMPLETE} to wait for all candidates.
	 *
	 * @return a future with the offer.
	 */
	CompletableFuture<RTCSessionDescription> createOffer(Gathering gathering);

	/**
	 * Applies the remote offer, then creates the answer and applies it.
	 *
	 * @param offer the remote offer.
	 *
	 * @return a future with the answer, at once.
	 */
	CompletableFuture<RTCSessionDescription> answer(RTCSessionDescription offer);

	/**
	 * Applies the remote offer, then creates the answer and applies it.
	 *
	 * @param offer     the remote offer.
	 * @param gathering {@link Gathering#COMPLETE} to wait for all candidates.
	 *
	 * @return a future with the answer.
	 */
	CompletableFuture<RTCSessionDescription> answer(RTCSessionDescription offer, Gathering gathering);

	/**
	 * Applies the remote answer to the local offer.
	 *
	 * @param answer the remote answer.
	 *
	 * @return a future that completes once the answer is applied.
	 */
	CompletableFuture<Void> applyAnswer(RTCSessionDescription answer);

	/**
	 * Adds a remote candidate. One that arrives before the remote description
	 * is held until the description is applied.
	 *
	 * @param candidate the remote candidate.
	 *
	 * @return a future that completes once the candidate is added.
	 */
	CompletableFuture<Void> addCandidate(RTCIceCandidate candidate);

}
