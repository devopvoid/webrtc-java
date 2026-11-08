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

import dev.onvoid.webrtc.client.WebRtcException;

/**
 * A file transfer did not complete: it was rejected, the file could not be
 * read or written, its checksum did not match, the remote peer broke the
 * protocol, or the data channel closed.
 */
public class TransferException extends WebRtcException {

	private static final long serialVersionUID = 1L;

	private final boolean resumable;


	/**
	 * Creates the exception with a message that says what went wrong.
	 *
	 * @param message   the detail message.
	 * @param resumable whether the transfer may be resumed.
	 */
	public TransferException(String message, boolean resumable) {
		super(message);
		this.resumable = resumable;
	}

	/**
	 * Creates the exception with a message and the failure that caused it.
	 *
	 * @param message   the detail message.
	 * @param cause     the underlying failure.
	 * @param resumable whether the transfer may be resumed.
	 */
	public TransferException(String message, Throwable cause, boolean resumable) {
		super(message, cause);
		this.resumable = resumable;
	}

	/**
	 * Tells whether the transfer stopped only because its data channel or
	 * {@link FileTransfers} closed. The sender may then resume it with {@link
	 * FileTransfers#resume(OutgoingTransfer)} over a new channel, and the
	 * receiver, unless {@link TransferOptions.Builder#resumable(boolean)} is
	 * off, keeps the data received so far for it.
	 *
	 * @return true when the transfer may be resumed.
	 */
	public boolean isResumable() {
		return resumable;
	}

}
