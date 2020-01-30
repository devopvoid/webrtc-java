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

package dev.onvoid.webrtc.demo.net;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelInit;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.RTCRtpSender;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCRtpTransceiverDirection;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.demo.config.Configuration;
import dev.onvoid.webrtc.demo.config.DesktopCaptureConfiguration;
import dev.onvoid.webrtc.demo.config.VideoConfiguration;
import dev.onvoid.webrtc.demo.model.Contact;
import dev.onvoid.webrtc.demo.model.message.ChatMessage;
import dev.onvoid.webrtc.demo.net.codec.JsonBCodec;
import dev.onvoid.webrtc.media.MediaSource.State;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioSource;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.video.VideoCaptureCapability;
import dev.onvoid.webrtc.media.video.VideoDesktopSource;
import dev.onvoid.webrtc.media.video.VideoDevice;
import dev.onvoid.webrtc.media.video.VideoDeviceSource;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class PeerConnectionClient implements PeerConnectionObserver {

	private static Logger LOGGER = System.getLogger(PeerConnectionClient.class.getName());

	private final ExecutorService executor;

	private final Configuration config;

	private final SignalingClient signalingClient;

	private final Contact contact;

	private final JsonBCodec dataCodec;

	private Timer statsTimer;

	private PeerConnectionFactory factory;

	private RTCPeerConnection peerConnection;

	private RTCDataChannel dataChannel;

	private RTCDataChannel remoteDataChannel;

	/*
	 * Queued remote ICE candidates are consumed only after both local and
	 * remote descriptions are set. Similarly local ICE candidates are sent to
	 * remote peer after both local and remote description are set.
	 */
	private List<RTCIceCandidate> queuedRemoteCandidates;

	private PeerConnectionContext peerConnectionContext;

	private VideoDesktopSource desktopSource;

	private VideoDeviceSource videoSource;


	public PeerConnectionClient(Configuration config, Contact contact,
			PeerConnectionContext context, SignalingClient signalingClient,
			ExecutorService executor) {
		this.config = config;
		this.contact = contact;
		this.peerConnectionContext = context;
		this.signalingClient = signalingClient;
		this.executor = executor;
		this.dataCodec = new JsonBCodec();
		this.queuedRemoteCandidates = new ArrayList<>();

		executeAndWait(() -> {
			factory = new PeerConnectionFactory();
			peerConnection = factory.createPeerConnection(config.getRTCConfig(), this);
		});
	}

	public void enableStatsEvents(boolean enable, int periodMs) {
		if (enable) {
			try {
				statsTimer = new Timer();
				statsTimer.schedule(new TimerTask() {

					@Override
					public void run() {
						getStats();
					}
				}, 0, periodMs);
			}
			catch (Exception e) {
				LOGGER.log(Level.ERROR, "Can not schedule statistics timer", e);
			}
		}
		else {
			statsTimer.cancel();
		}
	}

	public boolean hasLocalVideoStream() {
		return nonNull(videoSource);
	}

	public boolean hasRemoteVideoStream() {
		RTCRtpReceiver[] receivers = peerConnection.getReceivers();

		if (nonNull(receivers)) {
			for (RTCRtpReceiver receiver : receivers) {
				if (receiver.getTrack().getKind().equals(MediaStreamTrack.VIDEO_TRACK_KIND)) {
					return true;
				}
			}
		}

		return false;
	}

	@Override
	public void onRenegotiationNeeded() {
		if (nonNull(peerConnection.getRemoteDescription())) {
			LOGGER.log(Level.INFO, "Renegotiation needed");

//			createOffer();
		}
	}

	@Override
	public void onConnectionChange(RTCPeerConnectionState state) {
		notify(peerConnectionContext.onPeerConnectionState, state);
	}

	@Override
	public void onDataChannel(RTCDataChannel channel) {
		remoteDataChannel = channel;

		initDataChannel(channel);
	}

	@Override
	public void onIceCandidate(RTCIceCandidate candidate) {
		if (isNull(peerConnection)) {
			LOGGER.log(Level.ERROR, "PeerConnection was not initialized");
			return;
		}

		try {
			signalingClient.send(contact, candidate);
		}
		catch (Exception e) {
			LOGGER.log(Level.ERROR, "Send RTCIceCandidate failed", e);
		}
	}

	@Override
	public void onIceCandidatesRemoved(RTCIceCandidate[] candidates) {
		if (isNull(peerConnection)) {
			LOGGER.log(Level.ERROR, "PeerConnection was not initialized");
			return;
		}

		try {
			signalingClient.send(contact, candidates);
		}
		catch (Exception e) {
			LOGGER.log(Level.ERROR, "Send removed RTCIceCandidates failed", e);
		}
	}

	@Override
	public void onTrack(RTCRtpTransceiver transceiver) {
		MediaStreamTrack track = transceiver.getReceiver().getTrack();

		if (track.getKind().equals(MediaStreamTrack.VIDEO_TRACK_KIND)) {
			VideoTrack videoTrack = (VideoTrack) track;
			videoTrack.addSink(frame -> publishFrame(peerConnectionContext.onRemoteFrame, frame));

			notify(peerConnectionContext.onRemoteVideoStream, true);
		}
	}

	@Override
	public void onRemoveTrack(RTCRtpReceiver receiver) {
		MediaStreamTrack track = receiver.getTrack();

		if (track.getKind().equals(MediaStreamTrack.VIDEO_TRACK_KIND)) {
			notify(peerConnectionContext.onRemoteVideoStream, false);
		}
	}

	public void setDesktopActive(boolean active) {
		RTCRtpSender[] senders = peerConnection.getSenders();

		if (nonNull(senders)) {
			for (RTCRtpSender sender : senders) {
				MediaStreamTrack track = sender.getTrack();

				if (track.getKind().equals(MediaStreamTrack.VIDEO_TRACK_KIND) &&
					track.getId().equals("desktopTrack")) {
					track.setEnabled(active);

					LOGGER.log(Level.INFO, "Track \"{0}\" set enabled to \"{1}\"",
							track.getId(), active);
				}
			}
		}
	}

	public void setMicrophoneActive(boolean active) {
		RTCRtpSender[] senders = peerConnection.getSenders();

		if (nonNull(senders)) {
			for (RTCRtpSender sender : senders) {
				MediaStreamTrack track = sender.getTrack();

				if (track.getKind().equals(MediaStreamTrack.AUDIO_TRACK_KIND)) {
					track.setEnabled(active);

					LOGGER.log(Level.INFO, "Track \"{0}\" set enabled to \"{1}\"",
							track.getId(), active);
				}
			}
		}
	}

	public void setCameraActive(boolean active) {
		RTCRtpSender[] senders = peerConnection.getSenders();

		if (nonNull(senders)) {
			for (RTCRtpSender sender : senders) {
				MediaStreamTrack track = sender.getTrack();

				if (track.getKind().equals(MediaStreamTrack.VIDEO_TRACK_KIND)) {
					track.setEnabled(active);

					LOGGER.log(Level.INFO, "Track \"{0}\" set enabled to \"{1}\"",
							track.getId(), active);
				}
			}
		}
	}

	public CompletableFuture<Void> close() {
		return CompletableFuture.runAsync(() -> {
			if (nonNull(statsTimer)) {
				statsTimer.cancel();
			}
			if (nonNull(desktopSource)) {
				desktopSource.stop();
				desktopSource.dispose();
				desktopSource = null;
			}
			if (nonNull(videoSource)) {
				videoSource.stop();
				videoSource.dispose();
				videoSource = null;
			}
			if (nonNull(dataChannel)) {
				dataChannel.unregisterObserver();
				dataChannel.close();
				dataChannel.dispose();
				dataChannel = null;
			}
			if (nonNull(remoteDataChannel)) {
				remoteDataChannel.unregisterObserver();
				remoteDataChannel.close();
				remoteDataChannel.dispose();
				remoteDataChannel = null;
			}
			if (nonNull(peerConnection)) {
				peerConnection.close();
				peerConnection = null;
			}
			if (nonNull(factory)) {
				factory.dispose();
				factory = null;
			}
		}, executor);
	}

	public void initCall() {
		initMedia();
		createOffer();
	}

	public void establishDataLink() {
		execute(() -> {
			initMedia();
			addDataChannel();

			createOffer();
		});
	}

	public void establishMediaLinks() {
		addAudio(peerConnectionContext.audioDirection);
		addVideo(peerConnectionContext.videoDirection);

		createOffer();
	}

	public CompletableFuture<Void> sendMessage(ChatMessage message) {
		return CompletableFuture.runAsync(() -> {
			if (isNull(dataChannel)) {
				throw new CompletionException("RTCDataChannel was not initialized or negotiated", null);
			}

			try {
				String encoded = dataCodec.encode(message);

				ByteBuffer data = ByteBuffer.wrap(encoded.getBytes(StandardCharsets.UTF_8));
				RTCDataChannelBuffer buffer = new RTCDataChannelBuffer(data, false);

				dataChannel.send(buffer);
			}
			catch (Exception e) {
				throw new CompletionException(e);
			}
		}, executor);
	}

	private void initMedia() {
		addAudio(peerConnectionContext.audioDirection);
		addVideo(peerConnectionContext.videoDirection);
	}

	private void addAudio(RTCRtpTransceiverDirection direction) {
		if (direction == RTCRtpTransceiverDirection.INACTIVE) {
			return;
		}

		AudioOptions audioOptions = new AudioOptions();

		if (direction != RTCRtpTransceiverDirection.SEND_ONLY) {
			audioOptions.echoCancellation = true;
			audioOptions.noiseSuppression = true;
		}

		AudioSource audioSource = factory.createAudioSource(audioOptions);
		AudioTrack audioTrack = factory.createAudioTrack("audioTrack", audioSource);

		peerConnection.addTrack(audioTrack, List.of("stream"));

		for (RTCRtpTransceiver transceiver : peerConnection.getTransceivers()) {
			MediaStreamTrack track = transceiver.getSender().getTrack();

			if (nonNull(track) && track.getKind().equals(MediaStreamTrack.AUDIO_TRACK_KIND)) {
				transceiver.setDirection(direction);
				break;
			}
		}
	}

	private void addVideo(RTCRtpTransceiverDirection direction) {
		if (direction == RTCRtpTransceiverDirection.INACTIVE) {
			return;
		}

		VideoConfiguration videoConfig = config.getVideoConfiguration();
		VideoDevice device = videoConfig.getCaptureDevice();
		VideoCaptureCapability capability = videoConfig.getCaptureCapability();

		videoSource = new VideoDeviceSource();

		if (nonNull(device)) {
			videoSource.setVideoCaptureDevice(device);
		}
		if (nonNull(capability)) {
			videoSource.setVideoCaptureCapability(capability);
		}

		VideoTrack videoTrack = factory.createVideoTrack("videoTrack", videoSource);

		if (direction == RTCRtpTransceiverDirection.SEND_ONLY ||
			direction == RTCRtpTransceiverDirection.SEND_RECV) {
			VideoTrackSink sink = frame -> publishFrame(peerConnectionContext.onLocalFrame, frame);

			videoTrack.addSink(sink);

			videoSource.start();
		}

		peerConnection.addTrack(videoTrack, List.of("stream"));

		for (RTCRtpTransceiver transceiver : peerConnection.getTransceivers()) {
			MediaStreamTrack track = transceiver.getSender().getTrack();

			if (nonNull(track) && track.getKind().equals(MediaStreamTrack.VIDEO_TRACK_KIND)) {
				transceiver.setDirection(direction);
				break;
			}
		}

//		notify(peerConnectionContext.onLocalVideoStream, videoSource.getState() == State.LIVE);
	}

	private void addDesktop(RTCRtpTransceiverDirection direction) {
		if (direction == RTCRtpTransceiverDirection.INACTIVE) {
			return;
		}

		DesktopCaptureConfiguration desktopConfig = config.getDesktopCaptureConfiguration();

		desktopSource = new VideoDesktopSource();
		desktopSource.setDesktopCapturer(null);
		desktopSource.setFrameRate(desktopConfig.getFrameRate());

		VideoTrack videoTrack = factory.createVideoTrack("desktopTrack", desktopSource);

		if (direction == RTCRtpTransceiverDirection.SEND_ONLY ||
			direction == RTCRtpTransceiverDirection.SEND_RECV) {
			VideoTrackSink sink = System.out::println;

			videoTrack.addSink(sink);

			desktopSource.start();
		}

		peerConnection.addTrack(videoTrack, List.of("stream"));

		for (RTCRtpTransceiver transceiver : peerConnection.getTransceivers()) {
			MediaStreamTrack track = transceiver.getSender().getTrack();

			if (nonNull(track) &&
				track.getKind().equals(MediaStreamTrack.VIDEO_TRACK_KIND) &&
				track.getId().equals("desktopTrack")) {
				transceiver.setDirection(direction);
				break;
			}
		}
	}

	private void addDataChannel() {
		RTCDataChannelInit dict = new RTCDataChannelInit();
		dict.protocol = "demo-messaging";

		dataChannel = peerConnection.createDataChannel("data", dict);
	}

	private void initDataChannel(final RTCDataChannel channel) {
		channel.registerObserver(new RTCDataChannelObserver() {

			@Override
			public void onBufferedAmountChange(long previousAmount) {
				execute(() -> {
					LOGGER.log(Level.INFO, "RTCDataChannel \"{0}\" buffered amount changed to {1}",
							channel.getLabel(),
							previousAmount);
				});
			}

			@Override
			public void onStateChange() {
				execute(() -> {
					LOGGER.log(Level.INFO, "RTCDataChannel \"{0}\" state: {1}",
							channel.getLabel(),
							channel.getState());
				});
			}

			@Override
			public void onMessage(RTCDataChannelBuffer buffer) {
				execute(() -> {
					try {
						decodeMessage(buffer);
					}
					catch (Exception e) {
						LOGGER.log(Level.ERROR, "Decode data channel message failed", e);
					}
				});
			}
		});
	}

	private void getStats() {
		execute(() -> {
			peerConnection.getStats(report -> {
				if (nonNull(peerConnectionContext.onStatsReport)) {
					peerConnectionContext.onStatsReport.accept(report);
				}
			});
		});
	}

	private void decodeMessage(RTCDataChannelBuffer buffer) {
		ByteBuffer byteBuffer = buffer.data;
		byte[] payload;

		if (byteBuffer.hasArray()) {
			payload = byteBuffer.array();
		}
		else {
			payload = new byte[byteBuffer.limit()];

			byteBuffer.get(payload);
		}

		if (nonNull(peerConnectionContext.onMessage)) {
			String data = new String(payload, StandardCharsets.UTF_8);
			ChatMessage message = dataCodec.decode(data, ChatMessage.class);
			message.setOrigin(ChatMessage.Origin.REMOTE);
			message.setTime(LocalTime.now());

			peerConnectionContext.onMessage.accept(contact, message);
		}
	}

	public void setSessionDescription(RTCSessionDescription description) {
		execute(() -> {
			boolean receivingCall = description.sdpType == RTCSdpType.OFFER;

			peerConnection.setRemoteDescription(description, new SetSDObserver() {

				@Override
				public void onSuccess() {
					super.onSuccess();

					if (receivingCall) {
						initMedia();
						createAnswer();
					}
				}

			});
		});
	}

	public void addIceCandidate(RTCIceCandidate candidate) {
		execute(() -> {
			if (nonNull(queuedRemoteCandidates)) {
				queuedRemoteCandidates.add(candidate);
			}
			else {
				peerConnection.addIceCandidate(candidate);
			}
		});
	}

	public void removeIceCandidates(RTCIceCandidate[] candidates) {
		execute(() -> {
			drainIceCandidates();

			peerConnection.removeIceCandidates(candidates);
		});
	}

	private void drainIceCandidates() {
		if (nonNull(queuedRemoteCandidates)) {
			LOGGER.log(Level.INFO, "Add " + queuedRemoteCandidates.size() + " remote candidates");

			queuedRemoteCandidates.forEach(peerConnection::addIceCandidate);
			queuedRemoteCandidates = null;
		}
	}

	private void createOffer() {
		RTCOfferOptions options = new RTCOfferOptions();

		peerConnection.createOffer(options, new CreateSDObserver());
	}

	private void createAnswer() {
		RTCAnswerOptions options = new RTCAnswerOptions();

		peerConnection.createAnswer(options, new CreateSDObserver());
	}

	private void setLocalDescription(RTCSessionDescription description) {
		peerConnection.setLocalDescription(description, new SetSDObserver() {

			@Override
			public void onSuccess() {
				super.onSuccess();

				try {
					signalingClient.send(contact, description);
				}
				catch (Exception e) {
					LOGGER.log(Level.ERROR, "Send RTCSessionDescription failed", e);
				}
			}

		});
	}

	private void publishFrame(Consumer<VideoFrame> consumer, VideoFrame frame) {
		if (isNull(consumer)) {
			return;
		}

		frame.retain();
		consumer.accept(frame);
		frame.release();
	}

	private void publishFrame(BiConsumer<Contact, VideoFrame> consumer, VideoFrame frame) {
		if (isNull(consumer)) {
			return;
		}

		frame.retain();
		consumer.accept(contact, frame);
		frame.release();
	}

	private <T> void notify(Consumer<T> consumer, T value) {
		if (nonNull(consumer)) {
			execute(() -> consumer.accept(value));
		}
	}

	private <T> void notify(BiConsumer<Contact, T> consumer, T value) {
		if (nonNull(consumer)) {
			execute(() -> consumer.accept(contact, value));
		}
	}

	private void execute(Runnable runnable) {
		executor.execute(runnable);
	}

	private void executeAndWait(Runnable runnable) {
		try {
			executor.submit(runnable).get();
		}
		catch (Exception e) {
			LOGGER.log(Level.ERROR, "Execute task failed");
		}
	}



	private class CreateSDObserver implements CreateSessionDescriptionObserver {

		@Override
		public void onSuccess(RTCSessionDescription description) {
			execute(() -> setLocalDescription(description));
		}

		@Override
		public void onFailure(String error) {
			execute(() -> {
				LOGGER.log(Level.ERROR, "Create RTCSessionDescription failed: " + error);
			});
		}

	}



	private class SetSDObserver implements SetSessionDescriptionObserver {

		@Override
		public void onSuccess() {
			execute(() -> {
				if (nonNull(peerConnection.getLocalDescription()) &&
					nonNull(peerConnection.getRemoteDescription())) {
					drainIceCandidates();
				}
			});
		}

		@Override
		public void onFailure(String error) {
			execute(() -> {
				LOGGER.log(Level.ERROR, "Set RTCSessionDescription failed: " + error);
			});
		}
	}
}
