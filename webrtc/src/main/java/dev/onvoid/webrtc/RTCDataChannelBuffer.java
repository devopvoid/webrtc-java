/*
 * Copyright 2019 Alex Andres
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

package dev.onvoid.webrtc;

import java.nio.ByteBuffer;

/**
 * A NIO based buffer used to send data over an {@link RTCDataChannel}.
 *
 * @author Alex Andres
 */
public class RTCDataChannelBuffer {

	/**
	 * The underlying data.
	 */
	public final ByteBuffer data;

	/**
	 * Indicates whether the data contains UTF-8 text or binary data.
	 */
	public final boolean binary;


	/**
	 * Creates an instance of RTCDataChannelBuffer with the specified payload
	 * and indicator what kind of data the payload contains.
	 *
	 * @param data   The byte buffer containing the data to send.
	 * @param binary Whether the buffer contains UTF-8 text or binary data.
	 */
	public RTCDataChannelBuffer(ByteBuffer data, boolean binary) {
		this.data = data;
		this.binary = binary;
	}

}
