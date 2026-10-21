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

import dev.onvoid.webrtc.client.internal.EncodedFrames;

/**
 * The client API's side of {@link EncodedFrames}, registered when the first
 * peer class loads.
 */
final class EncodedFramesAccess implements EncodedFrames.Access {

	@Override
	public CompletableFuture<EncodedFrames.Source> of(Sender sender) {
		if (!(sender instanceof SenderImpl)) {
			throw new IllegalArgumentException("Not a sender of the client API: " + sender);
		}

		return ((SenderImpl) sender).encodedFrames();
	}

	@Override
	public CompletableFuture<EncodedFrames.Source> of(RemoteTrack track) {
		if (!(track instanceof RemoteTrackImpl)) {
			throw new IllegalArgumentException("Not a remote track of the client API: " + track);
		}

		return ((RemoteTrackImpl) track).encodedFrames();
	}
}
