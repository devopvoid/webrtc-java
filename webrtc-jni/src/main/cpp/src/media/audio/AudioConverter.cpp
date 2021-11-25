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

#include <memory>

#include "audio/utility/channel_mixer.h"
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
                webrtc::ChannelLayout srcLayout = webrtc::GuessChannelLayout(static_cast<int>(srcChannels));
                webrtc::ChannelLayout dstLayout = webrtc::GuessChannelLayout(static_cast<int>(dstChannels));

                mixer = std::make_unique<webrtc::ChannelMixer>(srcLayout, dstLayout);
                frame = std::make_unique<webrtc::AudioFrame>();

                frame->samples_per_channel_ = srcFrames;
                frame->num_channels_ = srcChannels;
            }

            ~ChannelConverter() override
            {
            }

            void convert(const int16_t * src, size_t srcSize, int16_t * dst, size_t dstSize) override {
                checkSizes(srcSize, dstSize);

                std::memcpy(frame->mutable_data(), src, srcFrames * srcChannels * sizeof(int16_t));

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
                resampler = std::make_unique<webrtc::PushResampler<int16_t>>();
                resampler->InitializeIfNeeded(static_cast<int>(srcFrames * 100), static_cast<int>(dstFrames * 100), srcChannels);
            }

            ~ResampleConverter() override
            {
            }

            void convert(const int16_t * src, size_t srcSize, int16_t * dst, size_t dstSize) override {
                checkSizes(srcSize, dstSize);

                resampler->Resample(src, srcSize, dst, dstSize);
            }

        private:
            std::unique_ptr<webrtc::PushResampler<int16_t>> resampler;
    };


    class CompositionConverter : public AudioConverter {
        public:
            explicit CompositionConverter(std::vector<std::unique_ptr<AudioConverter>> converters_)
                : converters(std::move(converters_))
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
                converters.front()->convert(src, srcSize, buffers.front().data(), buffers.front().size());

                for (size_t i = 2; i < converters.size(); ++i) {
                    auto & src_buffer = buffers[i - 2];
                    auto & dst_buffer = buffers[i - 1];

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

    AudioConverter::AudioConverter() :
        srcFrames(0),
        srcChannels(0),
        dstFrames(0),
        dstChannels(0)
    {
    }

    AudioConverter::AudioConverter(size_t srcFrames, size_t srcChannels, size_t dstFrames, size_t dstChannels) :
        srcFrames(srcFrames),
        srcChannels(srcChannels),
        dstFrames(dstFrames),
        dstChannels(dstChannels)
    {
    }

    void AudioConverter::checkSizes(size_t srcSize, size_t dstCapacity) const {
        RTC_CHECK_EQ(srcSize, srcChannels * srcFrames);
        RTC_CHECK_GE(dstCapacity, dstChannels * dstFrames);
    }
}