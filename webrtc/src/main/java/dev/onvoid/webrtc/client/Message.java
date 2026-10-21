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

import java.nio.charset.StandardCharsets;

/**
 * A message received on a {@link DataChannel}. It holds a copy of the data and
 * may be kept as long as needed.
 */
public final class Message {

	private final byte[] data;

	private final boolean binary;


	Message(byte[] data, boolean binary) {
		this.data = data;
		this.binary = binary;
	}

	/**
	 * @return true when the message was sent as text.
	 */
	public boolean isText() {
		return !binary;
	}

	/**
	 * @return the message decoded as UTF-8.
	 */
	public String text() {
		return new String(data, StandardCharsets.UTF_8);
	}

	/**
	 * @return a copy of the message's bytes.
	 */
	public byte[] bytes() {
		return data.clone();
	}

	/**
	 * @return the number of bytes.
	 */
	public int size() {
		return data.length;
	}

	@Override
	public String toString() {
		return binary ? "Message[" + data.length + " bytes]" : "Message[" + text() + "]";
	}
}
