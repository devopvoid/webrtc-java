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

package dev.onvoid.webrtc.internal;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCRtpSender;
import dev.onvoid.webrtc.logging.Logging;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.AudioConverter;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioResampler;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Runs in a JVM of its own, with the leak detector on: forgets some objects,
 * releases others, waits until the garbage collector has found all of them
 * unreachable, and prints the reports. Exits with 0 if exactly the forgotten
 * objects were reported.
 */
public final class LeakDetectionUse {

	/**
	 * The system property that has {@link Logging} load the library, which
	 * then initializes {@link NativeObject} while it is being loaded.
	 */
	static final String LOGGING_FIRST_PROPERTY = "test.leak.logging.first";


	public static void main(String[] args) {
		if (Boolean.getBoolean(LOGGING_FIRST_PROPERTY)) {
			Logging.logTimestamps(false);
		}

		List<String> reports = Collections.synchronizedList(new ArrayList<>());
		LeakDetector.reporter = reports::add;

		List<LeakDetector.Record> records = new ArrayList<>();
		List<String> failures = new ArrayList<>();

		// Forgotten, created in Java.
		records.add(forgetResampler());

		// Released.
		AudioConverter converter = new AudioConverter(48000, 1, 16000, 1);
		records.add(record(converter));
		converter.dispose();
		converter = null;

		// Reference counted, not watched.
		NativeI420Buffer buffer = NativeI420Buffer.allocate(2, 2);

		if (record(buffer) != null) {
			failures.add("reference counted buffer watched");
		}

		buffer.release();

		// Created natively and released, and a view, which needs no dispose.
		AudioDeviceModule audioModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
		PeerConnectionFactory factory = new PeerConnectionFactory(audioModule);
		AudioTrackSource source = factory.createAudioSource(new AudioOptions());
		AudioTrack track = factory.createAudioTrack("audio", source);
		RTCPeerConnection peerConnection = factory.createPeerConnection(new RTCConfiguration(), candidate -> { });
		RTCRtpSender sender = peerConnection.addTrack(track, List.of("stream"));
		records.add(record(source));
		records.add(record(sender));
		records.add(forgetView(sender));

		sender.dispose();
		peerConnection.close();
		track.dispose();
		source.dispose();
		factory.dispose();
		audioModule.dispose();
		source = null;
		sender = null;
		track = null;
		peerConnection = null;

		for (LeakDetector.Record record : records) {
			if (record == null) {
				failures.add("an object to watch is not watched");
			}
		}

		if (failures.isEmpty()) {
			awaitChecked(records);
		}

		reports.forEach(System.out::println);

		if (!failures.isEmpty()) {
			failures.forEach(System.out::println);
			System.exit(1);
		}
		if (reports.size() != 1 || !reports.get(0).startsWith(AudioResampler.class.getName() + " was not disposed")) {
			System.out.println("Expected one report, of the AudioResampler, got " + reports.size());
			System.exit(1);
		}
	}

	/**
	 * The record of the object. A field of NativeObject, which subclasses in
	 * other packages do not inherit.
	 */
	static LeakDetector.Record record(NativeObject object) {
		return object.leakRecord;
	}

	private static LeakDetector.Record forgetResampler() {
		return record(new AudioResampler(48000, 16000, 1));
	}

	private static LeakDetector.Record forgetView(RTCRtpSender sender) {
		MediaStreamTrack view = sender.getTrack();
		LeakDetector.Record record = record(view);

		if (record != null && !record.released) {
			throw new IllegalStateException("view taken for an owner");
		}

		return record;
	}

	private static void awaitChecked(List<LeakDetector.Record> records) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);

		while (!records.stream().allMatch(record -> record.checked)) {
			if (System.nanoTime() > deadline) {
				System.out.println("Not all objects were collected");
				System.exit(1);
			}

			System.gc();

			// Gives the cleaner's thread time to run what the collection found.
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
		}
	}

}
