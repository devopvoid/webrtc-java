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

package dev.onvoid.webrtc.client.transfer;

/**
 * How far a {@link Transfer} got: the bytes the receiver wrote to its file and
 * confirmed. The sender learns of them with the receiver's acknowledgements,
 * not when it hands data to the transport.
 */
public final class TransferProgress {

	private final long bytes;

	private final long size;


	TransferProgress(long bytes, long size) {
		this.bytes = bytes;
		this.size = size;
	}

	/**
	 * @return the bytes confirmed so far.
	 */
	public long bytes() {
		return bytes;
	}

	/**
	 * @return the size of the file.
	 */
	public long size() {
		return size;
	}

	/**
	 * @return the share confirmed, from 0 to 1; 1 for an empty file.
	 */
	public double fraction() {
		return size == 0 ? 1 : (double) bytes / size;
	}

	@Override
	public String toString() {
		return "TransferProgress[" + bytes + " of " + size + " bytes]";
	}
}
