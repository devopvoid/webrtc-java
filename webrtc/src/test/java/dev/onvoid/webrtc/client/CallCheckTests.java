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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.IntUnaryOperator;
import java.util.stream.Collectors;

import dev.onvoid.webrtc.client.CallCheckResult.Problem;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.SAME_THREAD)
class CallCheckTests extends ClientTestBase {

	/** Delays no other timer of the runtime has, so that only the check's fire. */
	private static final Duration DURATION = Duration.ofMillis(2500);

	private static final Duration TIMEOUT = Duration.ofMillis(7500);

	private final ManualTimer timer = new ManualTimer();

	/** A runtime whose measurements end only when the test fires them. */
	private WebRtc timed;


	@BeforeEach
	void createTimedRuntime() {
		timed = WebRtc.builder()
				.audio(AudioMode.HEADLESS)
				.errorHandler(errors::add)
				.timer(timer)
				.build();
	}

	@AfterEach
	void closeTimedRuntime() {
		timed.close();
	}

	@Test
	void brightVideoIsReady() throws Exception {
		CustomVideo video = timed.media().customVideo("camera");
		CompletableFuture<CallCheckResult> check = timed.checkCall(check().video(video).build());

		push(video, 320, 240, 200);
		CallCheckResult result = endMeasurement(check);

		assertTrue(result.isReady(), result::toString);
		assertEquals(Collections.emptySet(), result.problems());
		assertTrue(result.video().frames() >= 1);
		assertEquals(320, result.video().width());
		assertEquals(240, result.video().height());
		assertEquals(200 / 255.0, result.video().brightness(), 0.01);
		assertNull(result.audio());
		assertNull(result.network());

		// The track stays the application's.
		assertFalse(video.isEnded());
	}

	@Test
	void blackVideoIsDark() throws Exception {
		CustomVideo video = timed.media().customVideo("camera");
		CompletableFuture<CallCheckResult> check = timed.checkCall(check().video(video).build());

		push(video, 320, 240, 16);
		CallCheckResult result = endMeasurement(check);

		assertEquals(EnumSet.of(Problem.DARK_VIDEO), result.problems());
		assertFalse(result.isReady());
	}

	@Test
	void noFrameIsNoVideo() throws Exception {
		CustomVideo video = timed.media().customVideo("camera");
		CompletableFuture<CallCheckResult> check = timed.checkCall(check().video(video).build());

		assertEquals(1, timer.fire(TIMEOUT));
		CallCheckResult result = await(check);

		assertEquals(EnumSet.of(Problem.NO_VIDEO), result.problems());
		assertEquals(0, result.video().frames());
		assertEquals(0, result.video().framesPerSecond());
	}

	@Test
	void endedTrackFinishesAtOnce() throws Exception {
		CustomVideo video = timed.media().customVideo("camera");
		CompletableFuture<CallCheckResult> check = timed.checkCall(check().video(video).build());

		video.close();
		CallCheckResult result = await(check);

		assertEquals(EnumSet.of(Problem.NO_VIDEO), result.problems());
		// Nothing of the check is left to fire.
		assertEquals(0, timer.scheduled(TIMEOUT));
		assertEquals(0, timer.scheduled(DURATION));
	}

	@Test
	void speechLevelAudioIsReady() throws Exception {
		CallCheckResult result = checkAudio(tone(8000));

		assertTrue(result.isReady(), result::toString);
		assertTrue(result.audio().received().toMillis() >= 10);
		assertEquals(8000 / 32768.0, result.audio().peakLevel(), 0.01);
		assertEquals(8000 / 32768.0 / Math.sqrt(2), result.audio().rmsLevel(), 0.01);
		assertEquals(0, result.audio().clipping());
	}

	@Test
	void fullScaleAudioIsClipped() throws Exception {
		CallCheckResult result = checkAudio(square(32767));

		assertEquals(EnumSet.of(Problem.CLIPPED_AUDIO), result.problems());
		assertEquals(1, result.audio().peakLevel(), 0.001);
		assertEquals(1, result.audio().clipping(), 0.001);
	}

	@Test
	void quietAudioIsSilent() throws Exception {
		CallCheckResult result = checkAudio(square(50));

		assertEquals(EnumSet.of(Problem.SILENT_AUDIO), result.problems());
	}

	@Test
	void noAudioIsNoAudio() throws Exception {
		CustomAudio audio = timed.media().customAudio("audio");
		CompletableFuture<CallCheckResult> check = timed.checkCall(check().audio(audio).build());

		assertEquals(1, timer.fire(TIMEOUT));
		CallCheckResult result = await(check);

		assertEquals(EnumSet.of(Problem.NO_AUDIO), result.problems());
		assertTrue(result.audio().received().isZero());
	}

	@Test
	void microphoneIsSentOnPeersOfItsOwn() throws Exception {
		// A headless runtime records nothing, so no audio arrives; what counts
		// is that the check sends the track, which makes WebRTC record a
		// microphone, and closes what it created for that.
		LocalAudio microphone = microphone(timed);
		CompletableFuture<CallCheckResult> check = timed.checkCall(check().audio(microphone).build());

		assertEquals(Arrays.asList("call-check-audio", "call-check-audio-receiver"), names(timed.peers()));

		assertEquals(1, timer.fire(TIMEOUT));
		CallCheckResult result = await(check);

		assertEquals(EnumSet.of(Problem.NO_AUDIO), result.problems());
		assertTrue(timed.peers().isEmpty());
		assertFalse(microphone.isEnded());
	}

	@Test
	void connectsToTestPeer() throws Exception {
		SignalingPair link = Signaling.loopback();
		Peer testPeer = rtc.createPeer(PeerOptions.builder()
				.name("test-peer")
				.signaling(link.second())
				.polite(true)
				.build());

		CallCheckResult result = await(rtc.checkCall(CallCheck.builder()
				.network(PeerOptions.builder().name("network-check").signaling(link.first()).build())
				.build()));

		assertTrue(result.isReady(), result::toString);
		assertTrue(result.network().isConnected());
		assertNotNull(result.network().connectTime());
		assertNull(result.network().failure());
		assertFalse(result.network().isRelayed());
		assertEquals("host", result.network().candidateType());

		// The check's own peer is closed, the test peer is the application's.
		assertEquals(Collections.singletonList(testPeer), rtc.peers());
	}

	@Test
	void unansweredNetworkTimesOut() throws Exception {
		CompletableFuture<CallCheckResult> check = timed.checkCall(check()
				.network(PeerOptions.builder().signaling(message -> { }).build())
				.build());

		assertEquals(1, timer.fire(TIMEOUT));
		CallCheckResult result = await(check);

		assertEquals(EnumSet.of(Problem.NO_CONNECTION), result.problems());
		assertFalse(result.network().isConnected());
		assertNull(result.network().connectTime());
		assertInstanceOf(TimeoutException.class, result.network().failure());
		assertTrue(timed.peers().isEmpty());
	}

	@Test
	void allPartsTogether() throws Exception {
		CustomVideo video = timed.media().customVideo("camera");
		CustomAudio audio = timed.media().customAudio("audio");
		CompletableFuture<CallCheckResult> check = timed.checkCall(check()
				.video(video)
				.audio(audio)
				.network(PeerOptions.builder().signaling(message -> { }).build())
				.build());

		push(video, 160, 120, 128);
		audio.push(tone(8000), PcmFormat.of(48000, 1));
		timer.awaitScheduled(DURATION, 2, TIMEOUT_SECONDS);

		// The video and audio started measuring, which leaves the network's timeout.
		assertEquals(1, timer.fire(TIMEOUT));
		assertFalse(check.isDone());

		assertEquals(2, timer.fire(DURATION));
		CallCheckResult result = await(check);

		assertEquals(EnumSet.of(Problem.NO_CONNECTION), result.problems());
		assertNotNull(result.video());
		assertNotNull(result.audio());
		assertNotNull(result.network());
	}

	@Test
	void runtimeCloseFailsCheck() {
		CustomVideo video = timed.media().customVideo("camera");
		CompletableFuture<CallCheckResult> check = timed.checkCall(check().video(video).build());

		timed.close();

		ExecutionException failed = assertThrows(ExecutionException.class, () -> await(check));
		assertInstanceOf(PeerClosedException.class, failed.getCause());
		assertThrows(PeerClosedException.class, () -> timed.checkCall(check().video(video).build()));
	}

	@Test
	void rejectsForeignTrack() throws Exception {
		CustomVideo foreign = rtc.media().customVideo("camera");

		assertThrows(IllegalArgumentException.class, () -> timed.checkCall(check().video(foreign).build()));
		assertTrue(timed.peers().isEmpty());
	}

	@Test
	void needsSomethingToCheck() {
		assertThrows(IllegalStateException.class, () -> CallCheck.builder().build());
		assertThrows(NullPointerException.class, () -> CallCheck.builder().video(null));
		assertThrows(NullPointerException.class, () -> CallCheck.builder().audio(null));
		assertThrows(NullPointerException.class, () -> CallCheck.builder().network(null));
		assertThrows(IllegalArgumentException.class, () -> CallCheck.builder().duration(Duration.ZERO));
		assertThrows(IllegalArgumentException.class, () -> CallCheck.builder().timeout(Duration.ofSeconds(-1)));
	}

	private static CallCheck.Builder check() {
		return CallCheck.builder().duration(DURATION).timeout(TIMEOUT);
	}

	private CallCheckResult checkAudio(byte[] pcm) throws Exception {
		CustomAudio audio = timed.media().customAudio("audio");
		CompletableFuture<CallCheckResult> check = timed.checkCall(check().audio(audio).build());

		// The same all along, so however much of it is measured, the result is the same.
		audio.push(pcm, PcmFormat.of(48000, 1));

		return endMeasurement(check);
	}

	/**
	 * Ends the measurement, once the first sample started it.
	 */
	private CallCheckResult endMeasurement(CompletableFuture<CallCheckResult> check) throws Exception {
		timer.awaitScheduled(DURATION, TIMEOUT_SECONDS);

		assertEquals(0, timer.scheduled(TIMEOUT));
		assertEquals(1, timer.fire(DURATION));

		return await(check);
	}

	private static void push(CustomVideo video, int width, int height, int luma) {
		NativeI420Buffer buffer = NativeI420Buffer.allocate(width, height);
		fill(buffer.getDataY(), luma);
		fill(buffer.getDataU(), 128);
		fill(buffer.getDataV(), 128);

		VideoFrame frame = new VideoFrame(buffer, System.nanoTime());

		try {
			video.push(frame);
		}
		finally {
			frame.release();
		}
	}

	private static void fill(ByteBuffer plane, int value) {
		for (int i = 0; i < plane.capacity(); i++) {
			plane.put(i, (byte) value);
		}
	}

	/** Half a second of 48 kHz mono at the amplitude, a 1 kHz sine. */
	private static byte[] tone(int amplitude) {
		return pcm(i -> (int) Math.round(Math.sin(2 * Math.PI * 1000 * i / 48000.0) * amplitude));
	}

	/** Half a second of 48 kHz mono, a 1 kHz square wave. */
	private static byte[] square(int amplitude) {
		return pcm(i -> (i / 24) % 2 == 0 ? amplitude : -amplitude);
	}

	private static byte[] pcm(IntUnaryOperator sample) {
		int frames = 24000;
		byte[] pcm = new byte[frames * 2];

		for (int i = 0; i < frames; i++) {
			int value = sample.applyAsInt(i);
			pcm[2 * i] = (byte) value;
			pcm[2 * i + 1] = (byte) (value >> 8);
		}

		return pcm;
	}

	/**
	 * A microphone track of a headless runtime, which records nothing.
	 */
	private static LocalAudio microphone(WebRtc runtime) {
		LocalMediaImpl media = (LocalMediaImpl) runtime.media();
		AudioTrackSource source = runtime.factory.createAudioSource(new AudioOptions());

		return media.register(new LocalAudioImpl(media,
				runtime.factory.createAudioTrack("microphone", source), source::dispose, true));
	}

	private static List<String> names(List<Peer> peers) {
		return peers.stream().map(Peer::name).sorted().collect(Collectors.toList());
	}
}
