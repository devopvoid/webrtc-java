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

package dev.onvoid.webrtc.client;

/**
 * A piece of decoded audio, usually 10 ms of 16-bit PCM with interleaved
 * channels.
 */
public final class AudioChunk {

	private final byte[] samples;

	private final int sampleRate;

	private final int channels;

	private final int frames;


	AudioChunk(byte[] samples, int sampleRate, int channels, int frames) {
		this.samples = samples;
		this.sampleRate = sampleRate;
		this.channels = channels;
		this.frames = frames;
	}

	/**
	 * @return the samples, 16-bit little-endian PCM with interleaved channels.
	 * The array belongs to the caller.
	 */
	public byte[] samples() {
		return samples;
	}

	/**
	 * @return the sample rate in Hz.
	 */
	public int sampleRate() {
		return sampleRate;
	}

	/**
	 * @return the number of channels.
	 */
	public int channels() {
		return channels;
	}

	/**
	 * @return the number of samples per channel.
	 */
	public int frames() {
		return frames;
	}

	@Override
	public String toString() {
		return "AudioChunk[" + frames + " frames, " + channels + " channels, " + sampleRate + " Hz]";
	}
}
