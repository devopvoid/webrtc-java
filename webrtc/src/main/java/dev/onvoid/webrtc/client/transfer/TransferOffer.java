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

import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A file the remote peer wants to send, handed to {@link
 * FileTransfers#onOffer}. It is accepted or rejected once; until then the
 * sender waits, and sends nothing else.
 * <p>
 * The name and the size come from the remote peer. The name may hold path
 * separators, {@code ..} or characters a file system refuses, so it must not
 * become a path as it is; {@link #safeFileName()} gives one that may.
 */
public final class TransferOffer {

	private static final int MAX_FILE_NAME = 200;

	private final FileTransfers owner;

	private final String id;

	private final String name;

	private final long size;

	private final AtomicBoolean decided = new AtomicBoolean();


	TransferOffer(FileTransfers owner, String id, String name, long size) {
		this.owner = owner;
		this.id = id;
		this.name = name;
		this.size = size;
	}

	/**
	 * @return the id of the transfer. An offer that resumes a transfer has
	 * the id of the one it resumes.
	 */
	public String id() {
		return id;
	}

	/**
	 * @return the name the sender gave the file, as it sent it.
	 */
	public String name() {
		return name;
	}

	/**
	 * @return the size of the file in bytes, as the sender announced it.
	 * More is never accepted.
	 */
	public long size() {
		return size;
	}

	/**
	 * Makes the sender's name usable as a single file name: it keeps the
	 * part after the last {@code /} or {@code \}, replaces control,
	 * formatting and reserved characters and a leading dot with {@code _},
	 * drops trailing dots and spaces, prefixes Windows device names such as
	 * {@code CON} with {@code _}, and cuts it to 200 characters. A name with
	 * nothing left becomes {@code file}.
	 * <pre>{@code
	 * offer.accept(downloads.resolve(offer.safeFileName()));
	 * }</pre>
	 *
	 * @return a name that stays in the directory it is resolved against.
	 */
	public String safeFileName() {
		return safeFileName(name);
	}

	/**
	 * Accepts the file. Its data goes to {@link
	 * IncomingTransfer#partialFile()}, which replaces the target, if it
	 * exists, once the checksum matched. A partial file the same transfer
	 * left behind before is continued.
	 *
	 * @param target the file to write, chosen by the application.
	 *
	 * @return the transfer.
	 *
	 * @throws IllegalStateException if the offer was accepted or rejected
	 *                               before.
	 */
	public IncomingTransfer accept(Path target) {
		requireNonNull(target, "target");

		if (target.getFileName() == null) {
			throw new IllegalArgumentException("The target " + target + " names no file");
		}

		decide();

		return owner.accept(this, target);
	}

	/**
	 * Rejects the file. The sender's transfer fails with the reason, and its
	 * next file follows.
	 *
	 * @param reason what the sender is told, at most 1024 bytes of UTF-8 of
	 *               it.
	 *
	 * @throws IllegalStateException if the offer was accepted or rejected
	 *                               before.
	 */
	public void reject(String reason) {
		requireNonNull(reason, "reason");
		decide();

		owner.reject(this, reason);
	}

	/**
	 * Returns the offer's id, size and the length of its name, without the
	 * name itself, which the remote peer chose.
	 */
	@Override
	public String toString() {
		return "TransferOffer[id=" + id + ", size=" + size + ", name length=" + name.length() + "]";
	}

	boolean isDecided() {
		return decided.get();
	}

	private void decide() {
		if (!decided.compareAndSet(false, true)) {
			throw new IllegalStateException("The offer " + id + " was already accepted or rejected");
		}
	}

	static String safeFileName(String name) {
		String base = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
		StringBuilder safe = new StringBuilder(Math.min(base.length(), MAX_FILE_NAME));

		for (int i = 0; i < base.length() && safe.length() < MAX_FILE_NAME; ) {
			int c = base.codePointAt(i);
			i += Character.charCount(c);

			boolean replace = Character.isISOControl(c)
					|| Character.getType(c) == Character.FORMAT
					|| Character.getType(c) == Character.LINE_SEPARATOR
					|| Character.getType(c) == Character.PARAGRAPH_SEPARATOR
					|| Character.getType(c) == Character.SURROGATE
					|| Character.getType(c) == Character.UNASSIGNED
					|| "<>:\"|?*".indexOf(c) >= 0
					|| (c == '.' && safe.length() == 0);

			if (replace) {
				safe.append('_');
			}
			else if (safe.length() + Character.charCount(c) <= MAX_FILE_NAME) {
				safe.appendCodePoint(c);
			}
			else {
				break;
			}
		}

		int end = safe.length();

		while (end > 0 && (safe.charAt(end - 1) == '.' || safe.charAt(end - 1) == ' ')) {
			end--;
		}
		safe.setLength(end);

		while (safe.length() > 0 && safe.charAt(0) == ' ') {
			safe.deleteCharAt(0);
		}

		if (safe.length() == 0) {
			return "file";
		}

		String stem = safe.toString();
		int dot = stem.indexOf('.');
		String device = (dot < 0 ? stem : stem.substring(0, dot)).trim().toUpperCase(Locale.ROOT);

		if (device.matches("CON|PRN|AUX|NUL|COM[0-9¹²³]|LPT[0-9¹²³]|CONIN\\$|CONOUT\\$")) {
			stem = "_" + stem;
		}

		return stem;
	}
}
