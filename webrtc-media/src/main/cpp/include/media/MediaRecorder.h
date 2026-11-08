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

#ifndef WEBRTC_JAVA_MEDIA_MEDIA_RECORDER_H_
#define WEBRTC_JAVA_MEDIA_MEDIA_RECORDER_H_

#include "media/MediaRecorderObserver.h"
#include "media/RecorderOutput.h"
#include "media/RecorderTrack.h"
#include "webrtc_java_api.h"

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

extern "C" {
#include <libavformat/avformat.h>
}

namespace ffmpeg
{
	// Records the encoded frames of RTCRtpSenders and RTCRtpReceivers into a
	// media file, as they are: nothing is decoded or encoded again, so a
	// recording costs a copy of each frame and the writing, nothing more.
	//
	// Frames arrive through webrtc-java's encoded frame observers, on the
	// WebRTC threads that carry media. There they are only checked and copied
	// into a queue, and a thread of the recorder's own writes them out, so
	// that a slow disk never holds up a call.
	//
	// The file header describes every stream, and a stream is only known once
	// its first frames arrived (for video, its first key frame). So the
	// header is written once every track has described itself, or once the
	// tracks that did have waited long enough for the rest; frames that come
	// before are held back until then.
	//
	// A recorder writes one file, or a series of segments, each of which is
	// a file of its own that starts at a key frame of every video track; or
	// it keeps the last seconds in memory, from which a replay is saved on
	// demand.
	class MediaRecorder
	{
		public:
			// How a recording ended, in the order of the Java
			// MediaRecorderResult.
			enum class Result
			{
				// Every frame taken was written, and the file was finished
				// and closed.
				kComplete,
				// Media went into the file, but writing, finishing or closing
				// it failed later.
				kPartial,
				// Nothing went wrong, and no media went into the file.
				kEmpty,
				// Writing failed before any media went into the file.
				kFailed
			};

			// What one track lost, in the order the Java MediaRecorderStats
			// reads it.
			struct TrackStats
			{
				// Frames the writer's queue had no room for.
				int64_t dropped_queue_full = 0;
				// Frames that could not be copied for want of memory.
				int64_t dropped_allocation_failed = 0;
				// Video frames dropped on the way to the next key frame.
				int64_t dropped_awaiting_key_frame = 0;
			};

			// Where segments go and when one ends.
			struct SegmentConfig
			{
				// A segment's file name is the prefix, its index in decimal,
				// zero-padded to the width, and the suffix, whose extension
				// picks the container.
				std::string prefix;
				std::string suffix;
				int width = 1;
				int64_t first_index = 0;
				// A segment ends at the next key frame once it lasts this
				// long, or holds this much; zero for no limit.
				int64_t max_duration_us = 0;
				int64_t max_bytes = 0;
			};

			// What a replay buffer keeps.
			struct ReplayConfig
			{
				// How far back a replay reaches.
				int64_t window_us = 0;
				// The most it holds, beyond which the oldest frames go.
				int64_t max_bytes = 0;
			};

			struct Stats
			{
				// The most that ever waited for the writer.
				int64_t max_queued_bytes = 0;
				// In the order of AddTrack().
				std::vector<TrackStats> tracks;
			};

			// Records into one file.
			MediaRecorder(const webrtc_java_api * api, std::string path);

			// Records into a series of segments.
			MediaRecorder(const webrtc_java_api * api, SegmentConfig segments);

			// Keeps the last seconds for replays.
			MediaRecorder(const webrtc_java_api * api, ReplayConfig replay);

			// Stops the recording if it still runs.
			~MediaRecorder();

			MediaRecorder(const MediaRecorder &) = delete;
			MediaRecorder & operator=(const MediaRecorder &) = delete;

			// Takes over the observer. Call before Start().
			void SetObserver(std::unique_ptr<MediaRecorderObserver> observer);

			// Opens the output file, choosing the container from its name.
			// Segments are opened as they begin, and replays as they are
			// saved. Returns 0 or a negative AVERROR.
			int Open();

			// Adds a sender or receiver to record, by the handle that
			// NativeApi.encodedFramesOf() returned. The recorder takes over
			// that handle's reference. Returns the track index. Only before
			// Start().
			int AddTrack(void * frames);

			// Attaches to every track and starts writing. A track that cannot
			// be attached to, or a writer thread that cannot be started, throws
			// and leaves the recorder unstarted, as it was before.
			void Start();

			// Detaches from every track, writes out what is queued, finishes
			// the file, waits for the writer thread and closes the file.
			// Returns how the recording ended. Stopping twice does nothing
			// more, and returns the same.
			Result Stop();

			// What was lost so far. Stays available once stopped.
			Stats GetStats();

			// Saves what the buffer holds of the last seconds into the given
			// file, which exists and is empty, and reports it to the
			// observer with the given id. Returns false if the buffer takes
			// no more requests, because it is stopped or failed, and the
			// observer hears nothing.
			bool SaveReplay(int id, std::string path);

		private:
			enum class Mode
			{
				kFile,
				kSegments,
				kReplay
			};

			struct SaveRequest
			{
				int id = 0;
				std::string path;
			};

			MediaRecorder(const webrtc_java_api * api, Mode mode, std::string path);

			// What the observer callback knows about a track. Guarded by
			// mutex_, since WebRTC calls in on threads of its own.
			struct Input
			{
				MediaRecorder * recorder = nullptr;
				int index = 0;
				void * frames = nullptr;
				void * observer = nullptr;

				// Set by the first frame the track takes, which fixes the
				// stream it records: one SSRC (one simulcast layer) and one
				// codec.
				bool locked = false;
				uint32_t ssrc = 0;
				std::string mime_type;
				int64_t last_time_us = 0;

				// A video track takes nothing until it sees a key frame, at
				// the start and after it had to drop frames.
				bool waiting_for_key_frame = true;
				bool key_frame_wanted = false;
				int64_t last_key_frame_request_us = 0;

				bool reported_codec_change = false;

				TrackStats stats;
			};

			// When this returns, no callback runs any more.
			void RemoveObservers();

			static void OnEncodedFrame(void * opaque, const wj_encoded_frame * frame);

			void Enqueue(Input * input, const wj_encoded_frame * frame);

			// Makes up for a frame that could not be queued: the video after
			// it is useless until the next key frame.
			void Dropped(Input * input, bool video);
			void WantKeyFrameLocked(Input * input);

			// Makes a video track wait for its next key frame, and asks for
			// one.
			void RestartAtKeyFrame(int track);

			// The entry of the writer thread, which runs Record() and lets no
			// exception out of it.
			void Run();
			void Record();

			// Ends a recording that Record() left by an exception: fails it,
			// lets go of what waits to be written, reports it and finishes
			// the file. Only to be called from a catch block.
			void Abort() noexcept;

			void Handle(RecordedFrame frame);

			// A track that changed its codec is described anew: it goes on in
			// the next segment, or in the replays from then on.
			void ChangeCodec(RecorderTrack & track, const RecordedFrame & frame);

			// Writes the frame into the given file, and fails the recording
			// if that does.
			void Write(RecorderOutput & output, RecordedFrame & frame);

			// Where a frame goes once the file or the first segment began.
			void Route(RecordedFrame & frame);
			void RouteSegment(RecordedFrame & frame);

			// Opens the next segment, starting at the given point of the
			// timeline, with a stream for every ready track. On failure the
			// recording is failed, and false returned.
			bool OpenSegment(int64_t start_us);

			// Ends the segment that writes the frame, and begins the next.
			void Rotate(const RecordedFrame & frame);
			void DueRotation();

			// Ends the segment before once its video tracks have all moved
			// on, or waited long enough, and keeps asking for the key frames
			// the segments wait for.
			void CheckSegments(int64_t now_us);

			// Whether a video track still writes into the segment before.
			bool Draining() const;

			std::string SegmentPath(int64_t index) const;

			// Finishes and closes a segment, and reports it.
			void FinishSegment(std::unique_ptr<RecorderOutput> segment);

			// Asks every video track that is ready for a key frame.
			void RequestKeyFrames();

			// Counts a frame that was dropped on the writer thread on the way
			// to its track's next key frame.
			void CountAwaitingKeyFrame(int track);

			bool AnyVideoReady() const;
			bool SegmentLimitReached() const;

			// Replay buffer.
			void Buffer(RecordedFrame frame);
			void TrimBuffer();
			void RemoveFromBuffer(int track);
			void RequestReplayKeyFrames(int64_t now_us);
			void Save(const SaveRequest & request);
			void SaveFailed(const SaveRequest & request, const std::string & message, int error);

			// Writes the header once the tracks are known, or once waiting
			// for the rest is no longer worth it. Returns whether the header
			// is written.
			bool MaybeWriteHeader(bool finishing);

			void Finish();

			// Closes the file if it is open, and fails the recording if that
			// does.
			void Close();

			// Fails the recording, and marks the file that failed, if any.
			void Fail(const std::string & message, int error, RecorderOutput * output = nullptr);
			void Warn(const std::string & message);

			// Called with the lock held; the requests are sent once it is
			// released.
			void CollectKeyFrameRequestsLocked(std::vector<int> * tracks, int64_t now_us);

			const webrtc_java_api * api_;
			const Mode mode_;
			// The file, or what the files are named after, for reports.
			const std::string path_;
			// Made in advance, since a writer that ran out of memory may not
			// get any to describe that.
			const std::string subject_;
			const std::string out_of_memory_error_;

			std::unique_ptr<MediaRecorderObserver> observer_;

			SegmentConfig segments_;
			ReplayConfig replay_;

			// The container that tracks are checked against; null for a
			// replay buffer, whose replays may go into any.
			const AVOutputFormat * format_ = nullptr;

			// The writer thread's, once Start() ran.
			// The file, or the segment that is written.
			std::unique_ptr<RecorderOutput> output_;
			// The segment before, which takes the frames of the video tracks
			// that have not reached a key frame in the next one yet.
			std::unique_ptr<RecorderOutput> draining_;
			int64_t next_index_ = 0;
			int64_t drain_started_us_ = 0;
			bool rotation_due_ = false;
			int64_t rotation_due_us_ = 0;
			// By track: whether a video track has begun in output_.
			std::vector<bool> switched_;
			// By track: whether the track waits for the next segment, after
			// it changed its codec.
			std::vector<bool> awaiting_segment_;
			bool skipped_existing_ = false;

			// The replay buffer, in the order the frames arrived, and the
			// times of the key frames each video track has in it.
			std::deque<RecordedFrame> ring_;
			size_t ring_bytes_ = 0;
			std::vector<std::deque<int64_t>> key_times_;
			// By track: when its last key frame arrived, in the clock of the
			// frames, and where its last frame lies on the timeline.
			std::vector<int64_t> last_key_us_;
			std::vector<int64_t> last_frame_us_;
			int64_t key_frame_interval_us_ = 0;

			std::vector<std::unique_ptr<RecorderTrack>> tracks_;
			std::deque<RecordedFrame> pending_;
			size_t pending_bytes_ = 0;
			int64_t first_ready_us_ = 0;
			// Also read by Stop(), on the thread that stops.
			std::atomic<bool> header_written_{ false };
			// Read by Stop() once the writer has ended; the thread that stops
			// sets it when it closes a file that had no writer.
			bool failed_ = false;

			// When recording started, in the clock of the frames.
			int64_t start_us_ = 0;

			std::thread thread_;

			std::mutex mutex_;
			std::condition_variable wake_;
			std::vector<std::unique_ptr<Input>> inputs_;
			std::deque<RecordedFrame> queue_;
			size_t queued_bytes_ = 0;
			size_t max_queued_bytes_ = 0;
			// What the callbacks have to report, which the writer does.
			std::vector<std::string> pending_warnings_;
			std::vector<SaveRequest> save_requests_;
			// Set once the writer takes no more save requests.
			bool writer_ended_ = false;
			// Wakes the writer for something other than frames.
			bool poke_ = false;
			bool accepting_ = false;
			bool stopping_ = false;
			bool started_ = false;
			bool stopped_ = false;
			// Set by the first Stop(), once the file is closed.
			Result result_ = Result::kEmpty;
	};
}

#endif
