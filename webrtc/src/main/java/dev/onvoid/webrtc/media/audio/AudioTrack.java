/*
 * Copyright 2019 Alex Andres
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

package dev.onvoid.webrtc.media.audio;

import static java.util.Objects.isNull;

import dev.onvoid.webrtc.RTCRtpPacketInfo;
import dev.onvoid.webrtc.internal.SinkRegistry;
import dev.onvoid.webrtc.media.MediaStreamTrack;

/**
 * An audio track. Its sinks and voice activity listeners may be added and
 * removed from any thread, also at the same time, and also from within the
 * callback of a sink or listener, its own included.
 * <p>
 * WebRTC holds a lock while it hands audio to the sinks of some sources, such
 * as that of a remote track or a {@link CustomAudioSource}, which adding and
 * removing a sink take as well. A change made from within a callback
 * therefore takes effect for the sinks at once, but the native sink is added
 * or removed on another thread right after the callback has returned.
 */
public class AudioTrack extends MediaStreamTrack {

	private final SinkRegistry<AudioTrackSink> sinks = new SinkRegistry<>(this::removeSinkInternal);

	private final SinkRegistry<VoiceActivityListener> voiceActivityListeners =
			new SinkRegistry<>(this::removeVoiceActivityListenerInternal);


	private AudioTrack() {
		super();
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Removes the sinks and voice activity listeners of the track first, and
	 * waits for those that other threads are still adding or removing.
	 *
	 * @throws IllegalStateException if called from within the callback of a
	 *                               sink or listener, of this track or any
	 *                               other.
	 */
	@Override
	public void dispose() {
		try {
			sinks.close();
		}
		finally {
			voiceActivityListeners.close();
		}

		super.dispose();
	}

	/**
	 * Adds an AudioSink to the track. A track can have any number of AudioSinks.
	 * Called from within the callback of a sink, it returns before the sink is
	 * registered, and the sink gets the audio that follows once it is.
	 *
	 * @param sink The audio sink that will receive audio data from the track.
	 *             Adding it again does nothing.
	 *
	 * @throws IllegalStateException if the track has been disposed.
	 */
	public void addSink(AudioTrackSink sink) {
		if (isNull(sink)) {
			throw new NullPointerException();
		}

		sinks.add(sink, (s, registration) -> addSinkInternal(new SinkDelivery(s, registration),
				takesPacketInfos(s)));
	}

	/**
	 * Whether the sink implements the method that receives the RTP packets,
	 * which are only collected for a sink that does.
	 */
	private static boolean takesPacketInfos(AudioTrackSink sink) {
		try {
			return sink.getClass().getMethod("onData", byte[].class, int.class, int.class, int.class,
					int.class, RTCRtpPacketInfo[].class).getDeclaringClass() != AudioTrackSink.class;
		}
		catch (NoSuchMethodException e) {
			return false;
		}
	}

	/**
	 * Removes an AudioSink from the track. If the AudioSink was not attached to
	 * the track, this is a no-op. Once this returns, the sink gets no further
	 * audio, except what another thread is handing it at that moment. A sink
	 * may remove itself in its {@code onData}.
	 */
	public void removeSink(AudioTrackSink sink) {
		if (isNull(sink)) {
			throw new NullPointerException();
		}

		sinks.remove(sink);
	}

	/**
	 * Adds a listener that hears when speech begins and ends in the audio of
	 * the track. The audio is analyzed in native code, and only the changes
	 * reach the listener, on the thread that delivers the audio. Like a sink,
	 * it gets what the source of the track hands to sinks: that of a remote
	 * track or a {@link CustomAudioSource} does, a microphone's does not. The
	 * microphone's speech comes from {@link
	 * dev.onvoid.webrtc.PeerConnectionFactory#addVoiceActivityListener}.
	 *
	 * @param config   when audio counts as speech.
	 * @param listener the listener. Adding it again does nothing.
	 *
	 * @throws IllegalStateException if the track has been disposed.
	 */
	public void addVoiceActivityListener(VoiceActivityConfig config, VoiceActivityListener listener) {
		if (isNull(config) || isNull(listener)) {
			throw new NullPointerException();
		}

		// Preserve partial milliseconds before native code rounds to 10 ms chunks.
		int hangoverMs = (int) config.getHangover().toMillis()
				+ (config.getHangover().getNano() % 1_000_000 == 0 ? 0 : 1);

		voiceActivityListeners.add(listener, (l, registration) -> addVoiceActivityListenerInternal(
				new ListenerDelivery(l, registration), (float) config.getThreshold(), hangoverMs));
	}

	/**
	 * Removes a listener added with {@link #addVoiceActivityListener}. Once
	 * this returns, it is no longer called, except by a call that another
	 * thread is making at that moment. If it was not added, this is a no-op. A
	 * listener may remove itself in its callback.
	 */
	public void removeVoiceActivityListener(VoiceActivityListener listener) {
		if (isNull(listener)) {
			throw new NullPointerException();
		}

		voiceActivityListeners.remove(listener);
	}

	/**
	 * Get the signal level from the audio track.
	 *
	 * @return The signal level.
	 */
	public native int getSignalLevel();

	/**
	 * Sets the playout volume of a remote audio track, the one of an {@link
	 * dev.onvoid.webrtc.RTCRtpReceiver}, from 0 (silent) to 10, where 1 plays
	 * the audio as received. Values above 1 amplify it. It applies to what the
	 * audio device plays, not to the audio passed to an {@link AudioTrackSink}.
	 * A local track ignores it.
	 *
	 * @param volume The volume, from 0 to 10.
	 *
	 * @throws IllegalArgumentException If the volume is outside 0 to 10.
	 */
	public void setVolume(double volume) {
		if (!(volume >= 0 && volume <= 10)) {
			throw new IllegalArgumentException("Volume must be between 0 and 10: " + volume);
		}

		setVolumeInternal(volume);
	}

	private native void setVolumeInternal(double volume);

	private native long addSinkInternal(AudioTrackSink sink, boolean packetInfos);

	private native void removeSinkInternal(long sinkHandle);

	private native long addVoiceActivityListenerInternal(VoiceActivityListener listener, float threshold,
			int hangoverMs);

	private native void removeVoiceActivityListenerInternal(long sinkHandle);



	/**
	 * What the native sink calls: it tells the registry when a thread hands
	 * the sink audio, and drops the audio of a removed sink.
	 */
	private static final class SinkDelivery implements AudioTrackSink {

		private final AudioTrackSink sink;

		private final SinkRegistry.Registration registration;


		SinkDelivery(AudioTrackSink sink, SinkRegistry.Registration registration) {
			this.sink = sink;
			this.registration = registration;
		}

		@Override
		public void onData(byte[] data, int bitsPerSample, int sampleRate, int channels, int frames) {
			if (!registration.enter()) {
				return;
			}

			try {
				sink.onData(data, bitsPerSample, sampleRate, channels, frames);
			}
			finally {
				registration.exit();
			}
		}

		@Override
		public void onData(byte[] data, int bitsPerSample, int sampleRate, int channels, int frames,
				RTCRtpPacketInfo[] packetInfos) {
			if (!registration.enter()) {
				return;
			}

			try {
				sink.onData(data, bitsPerSample, sampleRate, channels, frames, packetInfos);
			}
			finally {
				registration.exit();
			}
		}
	}



	/**
	 * What the native sink of a voice activity listener calls, like {@link
	 * SinkDelivery}.
	 */
	private static final class ListenerDelivery implements VoiceActivityListener {

		private final VoiceActivityListener listener;

		private final SinkRegistry.Registration registration;


		ListenerDelivery(VoiceActivityListener listener, SinkRegistry.Registration registration) {
			this.listener = listener;
			this.registration = registration;
		}

		@Override
		public void onVoiceActivity(boolean speaking) {
			if (!registration.enter()) {
				return;
			}

			try {
				listener.onVoiceActivity(speaking);
			}
			finally {
				registration.exit();
			}
		}
	}

}
