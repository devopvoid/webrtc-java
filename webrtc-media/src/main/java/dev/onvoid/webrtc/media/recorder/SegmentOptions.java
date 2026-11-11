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

package dev.onvoid.webrtc.media.recorder;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * How a {@link MediaRecorder} splits a recording into segments, and how many
 * of them it keeps.
 * <p>
 * Segments are named after a pattern whose file name holds the segment's
 * number as {@code %d}, or zero-padded to a width as in {@code %05d}; a
 * literal percent sign is written {@code %%}. The extension picks the
 * container of every segment, as it does for a single file:
 * <pre>{@code
 * SegmentOptions segments = SegmentOptions.builder(Paths.get("recordings", "camera-%05d.mkv"))
 *         .maxDuration(Duration.ofMinutes(10))
 *         .maxTotalSize(20L * 1024 * 1024 * 1024)
 *         .build();
 * }</pre>
 * <p>
 * A segment ends once it lasts {@link Builder#maxDuration(Duration) long
 * enough} or {@link Builder#maxSize(long) holds enough}, at the next key frame,
 * since every segment has to begin with one to play on its own. Which limits
 * apply, at least one of the two, is up to the builder; once a limit is
 * reached, the recorder asks for a key frame, so a segment ends a moment after
 * it, not much later.
 * <p>
 * Without limits on what is kept, every segment stays. With them, the oldest
 * segments of the recording are deleted whenever a segment is finished, until
 * the rest are within them. The newest segment is always kept.
 *
 * @author Alex Andres
 */
public final class SegmentOptions {

	private final Path pattern;

	private final Path directory;

	/** The file name before the number, and after it. */
	private final String namePrefix;

	private final String nameSuffix;

	/** How many digits the number has at least. */
	private final int width;

	private final Duration maxDuration;

	private final long maxSize;

	private final int maxSegments;

	private final long maxTotalSize;


	private SegmentOptions(Builder builder) {
		this.pattern = builder.pattern;
		this.directory = builder.directory;
		this.namePrefix = builder.namePrefix;
		this.nameSuffix = builder.nameSuffix;
		this.width = builder.width;
		this.maxDuration = builder.maxDuration;
		this.maxSize = builder.maxSize;
		this.maxSegments = builder.maxSegments;
		this.maxTotalSize = builder.maxTotalSize;
	}

	/**
	 * Returns a builder for segments named after the given pattern, whose file
	 * name holds the number of a segment as {@code %d} or {@code %0Nd}. The
	 * directory must exist when the recorder is created.
	 *
	 * @param pattern The path of a segment, with its number as a placeholder.
	 *
	 * @return A builder without limits.
	 *
	 * @throws IllegalArgumentException If the file name holds no placeholder,
	 *                                  more than one, or a percent sign that is
	 *                                  not one or {@code %%}.
	 */
	public static Builder builder(Path pattern) {
		return new Builder(pattern);
	}

	/**
	 * @return The pattern segments are named after.
	 */
	public Path getPattern() {
		return pattern;
	}

	/**
	 * @return How long a segment lasts before it ends at the next key frame,
	 * or {@code null} for no limit.
	 */
	public Duration getMaxDuration() {
		return maxDuration;
	}

	/**
	 * @return How many bytes a segment holds before it ends at the next key
	 * frame, or 0 for no limit.
	 */
	public long getMaxSize() {
		return maxSize;
	}

	/**
	 * @return How many segments are kept, or 0 for all of them.
	 */
	public int getMaxSegments() {
		return maxSegments;
	}

	/**
	 * @return How many bytes the segments that are kept take together at
	 * most, or 0 for no limit.
	 */
	public long getMaxTotalSize() {
		return maxTotalSize;
	}

	@Override
	public String toString() {
		return "SegmentOptions[pattern=" + pattern + ", maxDuration=" + maxDuration + ", maxSize=" + maxSize
				+ ", maxSegments=" + maxSegments + ", maxTotalSize=" + maxTotalSize + "]";
	}

	Path getDirectory() {
		return directory;
	}

	/**
	 * @return The path of a segment up to its number, as the native recorder
	 * puts it together.
	 */
	String pathPrefix() {
		String dir = directory.toString();
		String separator = directory.getFileSystem().getSeparator();

		return (dir.endsWith(separator) ? dir : dir + separator) + namePrefix;
	}

	String nameSuffix() {
		return nameSuffix;
	}

	int width() {
		return width;
	}

	/**
	 * Returns the number that follows the highest one of the segments in the
	 * directory, so that a recording started again goes on after the last,
	 * or 0 if there are none.
	 */
	long nextIndex() throws IOException {
		long highest = -1;

		try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
			for (Path file : files) {
				highest = Math.max(highest, indexOf(file.getFileName().toString()));
			}
		}

		return highest + 1;
	}

	/**
	 * @return The number of the segment with the given file name, or -1 if
	 * the name is not one of a segment.
	 */
	long indexOf(String name) {
		if (name.length() <= namePrefix.length() + nameSuffix.length()
				|| !name.startsWith(namePrefix) || !name.endsWith(nameSuffix)) {
			return -1;
		}

		String digits = name.substring(namePrefix.length(), name.length() - nameSuffix.length());

		// Far below the range of a long, which no recording reaches.
		if (digits.length() > 18) {
			return -1;
		}
		for (int i = 0; i < digits.length(); i++) {
			char c = digits.charAt(i);

			if (c < '0' || c > '9') {
				return -1;
			}
		}

		return Long.parseLong(digits);
	}



	/** Builds {@link SegmentOptions}. */
	public static final class Builder {

		private final Path pattern;

		private final Path directory;

		private String namePrefix;

		private String nameSuffix;

		private int width;

		private Duration maxDuration;

		private long maxSize;

		private int maxSegments;

		private long maxTotalSize;


		private Builder(Path pattern) {
			Objects.requireNonNull(pattern, "Pattern is null");

			Path absolute = pattern.toAbsolutePath();
			Path name = absolute.getFileName();

			if (name == null) {
				throw new IllegalArgumentException("The pattern " + pattern + " has no file name");
			}

			this.pattern = pattern;
			this.directory = absolute.getParent();

			parse(name.toString());
		}

		/**
		 * Ends a segment once it lasts this long, at the next key frame.
		 *
		 * @param duration At least a second.
		 *
		 * @return This builder.
		 */
		public Builder maxDuration(Duration duration) {
			Objects.requireNonNull(duration, "Duration is null");

			if (duration.compareTo(Duration.ofSeconds(1)) < 0) {
				throw new IllegalArgumentException("A segment lasts at least a second, not " + duration);
			}

			this.maxDuration = duration;
			return this;
		}

		/**
		 * Ends a segment once it holds this many bytes, at the next key frame,
		 * so that it ends up somewhat larger.
		 *
		 * @param bytes More than 0.
		 *
		 * @return This builder.
		 */
		public Builder maxSize(long bytes) {
			if (bytes <= 0) {
				throw new IllegalArgumentException("The size must be positive, got " + bytes);
			}

			this.maxSize = bytes;
			return this;
		}

		/**
		 * Keeps no more than this many segments of the recording, deleting the
		 * oldest.
		 *
		 * @param count More than 0.
		 *
		 * @return This builder.
		 */
		public Builder maxSegments(int count) {
			if (count <= 0) {
				throw new IllegalArgumentException("The count must be positive, got " + count);
			}

			this.maxSegments = count;
			return this;
		}

		/**
		 * Keeps no more segments of the recording than take this many bytes
		 * together, deleting the oldest. The newest is kept even if it is
		 * larger, and the segment being written does not count, so the
		 * recording takes up to this much and one segment more.
		 *
		 * @param bytes More than 0.
		 *
		 * @return This builder.
		 */
		public Builder maxTotalSize(long bytes) {
			if (bytes <= 0) {
				throw new IllegalArgumentException("The size must be positive, got " + bytes);
			}

			this.maxTotalSize = bytes;
			return this;
		}

		/**
		 * @return The options.
		 *
		 * @throws IllegalStateException If neither a duration nor a size ends
		 *                               a segment.
		 */
		public SegmentOptions build() {
			if (maxDuration == null && maxSize == 0) {
				throw new IllegalStateException("A segment ends by its duration or its size; neither is set");
			}

			return new SegmentOptions(this);
		}

		private void parse(String name) {
			StringBuilder prefix = new StringBuilder();
			StringBuilder suffix = new StringBuilder();
			StringBuilder current = prefix;
			boolean found = false;
			int i = 0;

			while (i < name.length()) {
				char c = name.charAt(i++);

				if (c != '%') {
					current.append(c);
					continue;
				}
				if (i < name.length() && name.charAt(i) == '%') {
					current.append('%');
					i++;
					continue;
				}

				int digits = i;

				while (i < name.length() && name.charAt(i) >= '0' && name.charAt(i) <= '9') {
					i++;
				}

				String flags = name.substring(digits, i);

				if (i >= name.length() || name.charAt(i) != 'd'
						|| (!flags.isEmpty() && (flags.charAt(0) != '0' || flags.length() < 2))) {
					throw new IllegalArgumentException("The file name " + name
							+ " has a placeholder other than %d or %0Nd, or a % that should be %%");
				}
				if (found) {
					throw new IllegalArgumentException("The file name " + name + " has more than one placeholder");
				}

				int padding = flags.isEmpty() ? 1 : Integer.parseInt(flags.substring(1));

				if (padding < 1 || padding > 18) {
					throw new IllegalArgumentException("The number in " + name + " is padded to 1 to 18 digits");
				}

				i++;
				found = true;
				width = padding;
				current = suffix;
			}

			if (!found) {
				throw new IllegalArgumentException("The file name " + name + " has no placeholder for the number, "
						+ "such as %d or %05d");
			}

			namePrefix = prefix.toString();
			nameSuffix = suffix.toString();
		}
	}

}
