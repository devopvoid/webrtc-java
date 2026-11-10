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

#ifndef WEBRTC_JAVA_MEDIA_RECORDER_OUTPUT_H_
#define WEBRTC_JAVA_MEDIA_RECORDER_OUTPUT_H_

#include "media/RecorderTrack.h"

#include <cstdint>
#include <string>
#include <vector>

extern "C" {
#include <libavformat/avformat.h>
}

namespace ffmpeg
{
	// One file a recorder writes: the whole recording, one of its segments,
	// or a replay saved from its buffer.
	//
	// Frames arrive placed on the timeline of the recording, and the file
	// starts at a point of that timeline of its own, so that every file
	// begins near zero however late in the recording it was started. All
	// streams are shifted by the same amount, which keeps them in sync.
	//
	// Used on the recorder's writer thread only.
	class RecorderOutput
	{
		public:
			// start_us is the point of the timeline the file begins at; index
			// is the number of the segment or replay, for reports.
			RecorderOutput(std::string path, int64_t start_us, int64_t index);

			// Frees the file without finishing it, closing it if still open.
			~RecorderOutput();

			RecorderOutput(const RecorderOutput &) = delete;
			RecorderOutput & operator=(const RecorderOutput &) = delete;

			// Opens the file, choosing the container from its name; a name
			// FFmpeg cannot place gets Matroska. An exclusive open refuses a
			// file that exists with AVERROR(EEXIST). Returns 0 or a negative
			// AVERROR.
			int Open(bool exclusive);

			const std::string & GetPath() const;
			int64_t GetIndex() const;
			int64_t GetStartUs() const;
			const AVOutputFormat * GetFormat() const;

			// Adds a stream for the ready track. Before WriteHeader() only.
			int AddStream(const RecorderTrack & track);
			bool HasStream(int track) const;
			bool HasStreams() const;

			int WriteHeader();
			bool IsHeaderWritten() const;

			// Writes the frame into the stream of its track. A frame to keep
			// has a reference of its packet written, otherwise the packet is
			// handed over and left blank.
			int Write(RecordedFrame & frame, bool keep);

			// Finishes the file; the trailer of a file whose header was never
			// written is skipped.
			int WriteTrailer();

			// Closes the file if it is open. Flushes what is still buffered.
			int Close();

			// Whether a packet went into the file.
			bool HasMedia() const;

			// The span from the start to the last frame written.
			int64_t GetDurationUs() const;

			// About what the file takes on disk: the header and the packets
			// written, without what the container adds around them.
			int64_t GetBytes() const;

			// Marks the file as incomplete, after a write into it failed.
			void SetFailed();
			bool IsFailed() const;

		private:
			struct Stream
			{
				AVStream * stream = nullptr;
				int clock_rate = 0;
				int64_t last_pts = -1;
			};

			const std::string path_;
			const int64_t start_us_;
			const int64_t index_;

			AVFormatContext * context_ = nullptr;
			// By track index; a track without a stream has none.
			std::vector<Stream> streams_;

			bool header_written_ = false;
			bool failed_ = false;
			int64_t packets_ = 0;
			int64_t bytes_ = 0;
			int64_t last_us_ = 0;
	};
}

#endif
