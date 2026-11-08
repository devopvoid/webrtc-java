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

import java.nio.file.Path;

/**
 * A file this side receives, from {@link TransferOffer#accept(Path)}.
 * <p>
 * The data goes to {@link #partialFile()} next to the target, which only once
 * the checksum matched replaces {@link #file()}. A transfer that failed with
 * a {@link TransferException#isResumable() resumable} error leaves the partial
 * file behind (unless {@link TransferOptions.Builder#resumable(boolean)} is
 * off): when the sender resumes the transfer and the offer is accepted with
 * the same target, it continues from there. An application that will not
 * resume it deletes the partial file.
 */
public interface IncomingTransfer extends Transfer {

	/**
	 * @return the file the data is written to until it is complete: the
	 * target's name with the start of the transfer id and {@code .part}
	 * appended, in the target's directory.
	 */
	Path partialFile();

}
