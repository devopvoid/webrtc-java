/*
 * Copyright 2021 Alex Andres
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

#include "media/audio/AudioConverter.h"

#include <algorithm>
#include <memory>
#include <stdexcept>
#include <string>

#include "audio/utility/channel_mixer.h"
#include "common_audio/include/audio_util.h"
#include "common_audio/resampler/include/push_resampler.h"

namespace jni
{
    class CopyConverter : public AudioConverter {
        public:
            CopyConverter(size_t srcFrames, size_t srcChannels, size_t dstFrames, size_t dstChannels)
                : AudioConverter(srcFrames, srcChannels, dstFrames, dstChannels)
            {
            }

            ~CopyConverter() override
            {
            }

            void convert(const int16_t * src, size_t srcSize, int16_t * dst, size_t dstSize) override {
                checkSizes(srcSize, dstSize);

                if (src != dst) {
                    std::memcpy(dst, src, dstFrames * dstChannels * sizeof(int16_t));
                }
            }
    };


    class ChannelConverter : public AudioConverter {
        public:
            ChannelConverter(size_t srcFrames, size_t srcChannels, size_t dstFrames, size_t dstChannels)
                : AudioConverter(srcFrames, srcChannels, dstFrames, dstChannels)
            {
                RTC_CHECK_EQ(srcFrames, dstFrames);

                // The mixer works in place in an AudioFrame, which must hold both layouts.
                if (std::max(srcChannels, dstChannels) * srcFrames > webrtc::AudioFrame::kMaxDataSizeSamples) {
                    throw std::invalid_argument("Too many samples to remix: " + std::to_string(srcFrames) + " per channel, "
                        + std::to_string(std::max(srcChannels, dstChannels)) + " channels");
                }

                webrtc::ChannelLayout srcLayout = webrtc::GuessChannelLayout(static_cast<int>(srcChannels));
                webrtc::ChannelLayout dstLayout = webrtc::GuessChannelLayout(static_cast<int>(dstChannels));

                mixer = std::make_unique<webrtc::ChannelMixer>(srcLayout, dstLayout);
                frame = std::make_unique<webrtc::AudioFrame>();
            }

            ~ChannelConverter() override
            {
            }

            void convert(const int16_t * src, size_t srcSize, int16_t * dst, size_t dstSize) override {
                checkSizes(srcSize, dstSize);

                // Transform() leaves the frame in the output layout, so the input layout is set on every call.
                std::memcpy(frame->mutable_data(srcFrames, srcChannels).data().data(), src, srcFrames * srcChannels * sizeof(int16_t));

                mixer->Transform(frame.get());

                std::memcpy(dst, frame->data(), dstFrames * dstChannels * sizeof(int16_t));
            }

        private:
            std::unique_ptr<webrtc::ChannelMixer> mixer;
            std::unique_ptr<webrtc::AudioFrame> frame;
    };


    class ResampleConverter : public AudioConverter {
        public:
            ResampleConverter(size_t srcFrames, size_t srcChannels, size_t dstFrames, size_t dstChannels)
                : AudioConverter(srcFrames, srcChannels, dstFrames, dstChannels)
            {
                RTC_CHECK_EQ(srcChannels, dstChannels);

                resampler = std::make_unique<webrtc::PushResampler<int16_t>>(srcFrames, dstFrames, srcChannels);
            }

            ~ResampleConverter() override
            {
            }

            void convert(const int16_t * src, size_t srcSize, int16_t * dst, size_t dstSize) override {
                checkSizes(srcSize, dstSize);

                // The views count samples per channel, the sizes count all channels.
                webrtc::InterleavedView<const int16_t> srcView(src, srcFrames, srcChannels);
                webrtc::InterleavedView<int16_t> dstView(dst, dstFrames, dstChannels);

                resampler->Resample(srcView, dstView);
            }

        private:
            std::unique_ptr<webrtc::PushResampler<int16_t>> resampler;
    };


    class CompositionConverter : public AudioConverter {
        public:
            explicit CompositionConverter(std::vector<std::unique_ptr<AudioConverter>> converters_)
                : AudioConverter(converters_.front()->getSrcFrames(), converters_.front()->getSrcChannels(),
                    converters_.back()->getDstFrames(), converters_.back()->getDstChannels()),
                  converters(std::move(converters_))
            {
                RTC_CHECK_GE(converters.size(), 2);

                // We need an intermediate buffer after every converter.
                for (auto it = converters.begin(); it != converters.end() - 1; ++it) {
                    buffers.push_back(std::vector<int16_t>((*it)->getDstFrames() * (*it)->getDstChannels(), 0));
                }
            }

            ~CompositionConverter() override
            {
            }

            void convert(const int16_t * src, size_t srcSize, int16_t * dst, size_t dstSize) override {
                checkSizes(srcSize, dstSize);

                converters.front()->convert(src, srcSize, buffers.front().data(), buffers.front().size());

                // Converter i reads the buffer of converter i - 1.
                for (size_t i = 1; i < converters.size() - 1; ++i) {
                    auto & src_buffer = buffers[i - 1];
                    auto & dst_buffer = buffers[i];

                    converters[i]->convert(src_buffer.data(), src_buffer.size(), dst_buffer.data(), dst_buffer.size());
                }

                converters.back()->convert(buffers.back().data(), buffers.back().size(), dst, dstSize);
            }

        private:
            std::vector<std::unique_ptr<AudioConverter>> converters;
            std::vector<std::vector<int16_t>> buffers;
    };


	std::unique_ptr<AudioConverter> AudioConverter::create(size_t srcFrames, size_t srcChannels, size_t dstFrames, size_t dstChannels)
	{
        auto checkFormat = [](size_t frames, size_t channels, const char * name) {
            if (frames == 0 || frames > webrtc::kMaxSamplesPerChannel10ms) {
                throw std::invalid_argument(std::string("Invalid number of ") + name + " samples per channel: "
                    + std::to_string(frames) + ", must be between 1 and " + std::to_string(webrtc::kMaxSamplesPerChannel10ms));
            }
            if (channels == 0 || channels > static_cast<size_t>(webrtc::kMaxConcurrentChannels)) {
                throw std::invalid_argument(std::string("Invalid number of ") + name + " channels: "
                    + std::to_string(channels) + ", must be between 1 and " + std::to_string(webrtc::kMaxConcurrentChannels));
            }
        };

        checkFormat(srcFrames, srcChannels, "source");
        checkFormat(dstFrames, dstChannels, "destination");

        std::unique_ptr<AudioConverter> converter;

        if (srcChannels > dstChannels) {
            if (srcFrames != dstFrames) {
                std::vector<std::unique_ptr<AudioConverter>> converters;
                converters.push_back(std::unique_ptr<AudioConverter>(new ChannelConverter(srcFrames, srcChannels, srcFrames, dstChannels)));
                converters.push_back(std::unique_ptr<AudioConverter>(new ResampleConverter(srcFrames, dstChannels, dstFrames, dstChannels)));

                converter.reset(new CompositionConverter(std::move(converters)));
            }
            else {
                converter.reset(new ChannelConverter(srcFrames, srcChannels, dstFrames, dstChannels));
            }
        }
        else if (srcChannels < dstChannels) {
            if (srcFrames != dstFrames) {
                std::vector<std::unique_ptr<AudioConverter>> converters;
                converters.push_back(std::unique_ptr<AudioConverter>(new ResampleConverter(srcFrames, srcChannels, dstFrames, srcChannels)));
                converters.push_back(std::unique_ptr<AudioConverter>(new ChannelConverter(dstFrames, srcChannels, dstFrames, dstChannels)));

                converter.reset(new CompositionConverter(std::move(converters)));
            }
            else {
                converter.reset(new ChannelConverter(srcFrames, srcChannels, dstFrames, dstChannels));
            }
        }
        else if (srcFrames != dstFrames) {
            converter.reset(new ResampleConverter(srcFrames, srcChannels, dstFrames, dstChannels));
        }
        else {
            converter.reset(new CopyConverter(srcFrames, srcChannels, dstFrames, dstChannels));
        }

        return converter;
	}

    AudioConverter::AudioConverter(size_t srcFrames, size_t srcChannels, size_t dstFrames, size_t dstChannels) :
        srcFrames(srcFrames),
        srcChannels(srcChannels),
        dstFrames(dstFrames),
        dstChannels(dstChannels)
    {
    }

    void AudioConverter::checkSizes(size_t srcSize, size_t dstCapacity) const {
        if (srcSize != srcChannels * srcFrames) {
            throw std::invalid_argument("Source must hold " + std::to_string(srcChannels * srcFrames)
                + " samples, but has " + std::to_string(srcSize));
        }
        if (dstCapacity < dstChannels * dstFrames) {
            throw std::invalid_argument("Destination must hold " + std::to_string(dstChannels * dstFrames)
                + " samples, but has room for " + std::to_string(dstCapacity));
        }
    }
}