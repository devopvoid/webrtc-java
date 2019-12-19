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

package dev.onvoid.webrtc.demo.javafx.control.skin;

import static java.util.Objects.nonNull;

import dev.onvoid.webrtc.RTCStats;
import dev.onvoid.webrtc.RTCStatsReport;
import dev.onvoid.webrtc.RTCStatsType;
import dev.onvoid.webrtc.demo.javafx.control.Stats;

import java.math.BigInteger;

import javafx.scene.control.Label;
import javafx.scene.control.SkinBase;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.VBox;

public class StatsSkin extends SkinBase<Stats> {

	/**
	 * Creates a new StatsSkin.
	 *
	 * @param control The control for which this Skin should attach to.
	 */
	public StatsSkin(Stats control) {
		super(control);

		initLayout(control);
	}

	@Override
	public void dispose() {
		Stats control = getSkinnable();

		unregisterChangeListeners(control.statsReportProperty());

		super.dispose();
	}

	private void setBytesLabel(Object bytes, Label label) {
		if (nonNull(bytes)) {
			String bytesStr = bytes.toString();
			BigInteger value = new BigInteger(bytesStr);

			label.setText(toReadableByteCount(value.longValueExact()));
		}
	}

	private void setAudioCodecLabel(RTCStats codecStats, Label label) {
		if (nonNull(codecStats)) {
			String mimeType = codecStats.getMembers().get("mimeType").toString();
			String clockRate = codecStats.getMembers().get("clockRate").toString();

			label.setText(getCodecName(mimeType) + " @ " + clockRate + " Hz");
		}
	}

	private void setVideoCodecLabel(RTCStats codecStats, Label label) {
		if (nonNull(codecStats)) {
			String mimeType = codecStats.getMembers().get("mimeType").toString();

			label.setText(getCodecName(mimeType));
		}
	}

	private void setVideoFrameLabel(RTCStats trackStats, RTCStats mediaStats, Label label) {
		if (nonNull(trackStats)) {
			String frameWidth = trackStats.getMembers().get("frameWidth").toString();
			String frameHeight = trackStats.getMembers().get("frameHeight").toString();

			String frameInfo = frameWidth + "x" + frameHeight;

			if (nonNull(mediaStats)) {
				String framesPerSecond = mediaStats.getMembers().get("framesPerSecond").toString();
				frameInfo += " @ " + framesPerSecond + " fps";
			}

			label.setText(frameInfo);
		}
	}

	private void setCandidateValues(RTCStats candidateStats, Label[] labels) {
		if (nonNull(candidateStats)) {
			String protocol = candidateStats.getMembers().get("protocol").toString();
			String ip = candidateStats.getMembers().get("ip").toString();
			String port = candidateStats.getMembers().get("port").toString();

			labels[0].setText(protocol.toUpperCase());
			labels[1].setText(ip + " : " + port);
		}
	}

	private void setRtpValues(RTCStatsReport report, RTCStats stats,
			Label[] audioLabels, Label[] videoLabels, boolean inbound) {
		String mediaType = stats.getMembers().get("mediaType").toString();
		Object codecId = stats.getMembers().get("codecId");
		Object bytes = stats.getMembers().get(inbound ? "bytesReceived" : "bytesSent");

		RTCStats codecStats = nonNull(codecId) ?
				report.getStats().get(codecId.toString()) :
				null;

		if (mediaType.equals("audio")) {
			setBytesLabel(bytes, audioLabels[inbound ? 0 : 1]);
			setAudioCodecLabel(codecStats, audioLabels[inbound ? 2 : 3]);
		}
		else if (mediaType.equals("video")) {
			Object trackId = stats.getMembers().get("trackId");
			Object mediaSourceId = stats.getMembers().get("mediaSourceId");

			RTCStats trackStats = nonNull(trackId) ?
					report.getStats().get(trackId.toString()) :
					null;
			RTCStats mediaStats = nonNull(mediaSourceId) ?
					report.getStats().get(mediaSourceId.toString()) :
					null;

			setBytesLabel(bytes, videoLabels[inbound ? 0 : 1]);
			setVideoCodecLabel(codecStats, videoLabels[inbound ? 2 : 3]);
			setVideoFrameLabel(trackStats, mediaStats, videoLabels[inbound ? 4 : 5]);
		}
	}

	private String getCodecName(String mimeType) {
		return mimeType.substring(mimeType.indexOf("/") + 1);
	}

	private void setValueClass(Label... labels) {
		for (Label label : labels) {
			label.getStyleClass().add("value");
			label.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
		}
	}

	private static String toReadableByteCount(long bytes) {
		int unit = 1024;
		if (bytes < unit) {
			return bytes + " B";
		}

		int exp = (int) (Math.log(bytes) / Math.log(unit));
		String pre = "KMGTPE".charAt(exp - 1) + "i";

		return String.format("%.1f %sB", bytes / Math.pow(unit, exp), pre);
	}

	private void initLayout(Stats control) {
		Label audioLabel = new Label("Audio");
		audioLabel.getStyleClass().add("header");

		Label videoLabel = new Label("Video");
		videoLabel.getStyleClass().addAll("header", "second");

		Label dataLabel = new Label("Data");
		dataLabel.getStyleClass().addAll("header", "second");

		Label localCandidateLabel = new Label("Local Candidate");
		localCandidateLabel.getStyleClass().addAll("header", "second");

		Label remoteCandidateLabel = new Label("Remote Candidate");
		remoteCandidateLabel.getStyleClass().addAll("header", "second");

		Label videoSentBytesLabel = new Label();
		Label videoReceivedBytesLabel = new Label();
		Label videoInboundCodecLabel = new Label();
		Label videoOutboundCodecLabel = new Label();

		setValueClass(videoSentBytesLabel, videoReceivedBytesLabel);
		setValueClass(videoInboundCodecLabel, videoOutboundCodecLabel);

		Label dataSentLabel = new Label("Sent");
		Label dataReceivedLabel = new Label("Received");

		Label dataSentBytesLabel = new Label();
		Label dataReceivedBytesLabel = new Label();

		setValueClass(dataSentBytesLabel, dataReceivedBytesLabel);

		RowConstraints row = new RowConstraints();
		row.setVgrow(Priority.NEVER);

		ColumnConstraints column1 = new ColumnConstraints();
		ColumnConstraints column2 = new ColumnConstraints();

		column1.setPercentWidth(50);
		column2.setPercentWidth(50);

		GridPane audioPane = new GridPane();
		audioPane.setMaxHeight(Region.USE_PREF_SIZE);
		audioPane.getColumnConstraints().addAll(column1, column2);
		audioPane.getRowConstraints().add(row);

		GridPane videoPane = new GridPane();
		videoPane.setMaxHeight(Region.USE_PREF_SIZE);
		videoPane.getColumnConstraints().addAll(column1, column2);
		videoPane.getRowConstraints().add(row);

		GridPane dataPane = new GridPane();
		dataPane.setMaxHeight(Region.USE_PREF_SIZE);
		dataPane.getRowConstraints().add(row);
		dataPane.add(dataSentLabel, 0, 1);
		dataPane.add(dataSentBytesLabel, 1, 1);
		dataPane.add(dataReceivedLabel, 0, 2);
		dataPane.add(dataReceivedBytesLabel, 1, 2);

		GridPane localCandidatePane = new GridPane();
		localCandidatePane.setMaxHeight(Region.USE_PREF_SIZE);
		localCandidatePane.getRowConstraints().add(row);

		GridPane remoteCandidatePane = new GridPane();
		remoteCandidatePane.setMaxHeight(Region.USE_PREF_SIZE);
		remoteCandidatePane.getRowConstraints().add(row);

		Label[] audioLabels = initAudioPane(audioPane);
		Label[] videoLabels = initVideoPane(videoPane);
		Label[] localCandidateLabels = initCandidatePane(localCandidatePane);
		Label[] remoteCandidateLabels = initCandidatePane(remoteCandidatePane);

		registerChangeListener(control.statsReportProperty(), o -> {
			final RTCStatsReport report = control.getStatsReport();

			for (RTCStats stats : report.getStats().values()) {
				if (stats.getType() == RTCStatsType.INBOUND_RTP) {
					setRtpValues(report, stats, audioLabels, videoLabels, true);
				}
				else if (stats.getType() == RTCStatsType.OUTBOUND_RTP) {
					setRtpValues(report, stats, audioLabels, videoLabels, false);
				}
				else if (stats.getType() == RTCStatsType.DATA_CHANNEL) {
					Object bytesSent = stats.getMembers().get("bytesSent");
					Object bytesReceived = stats.getMembers().get("bytesReceived");

					setBytesLabel(bytesSent, dataSentBytesLabel);
					setBytesLabel(bytesReceived, dataReceivedBytesLabel);
				}
				else if (stats.getType() == RTCStatsType.LOCAL_CANDIDATE) {
					setCandidateValues(stats, localCandidateLabels);
				}
				else if (stats.getType() == RTCStatsType.REMOTE_CANDIDATE) {
					setCandidateValues(stats, remoteCandidateLabels);
				}
			}
		});

		VBox pane = new VBox();
		pane.getChildren().addAll(
				audioLabel, audioPane,
				videoLabel, videoPane,
				dataLabel, dataPane,
				localCandidateLabel, localCandidatePane,
				remoteCandidateLabel, remoteCandidatePane
		);

		getChildren().add(pane);
	}

	private Label[] initCandidatePane(GridPane pane) {
		Label protocolLabel = new Label("Protocol");
		Label protocolValueLabel = new Label();
		Label addressLabel = new Label("Address");
		Label addressValueLabel = new Label();

		pane.add(protocolLabel, 0, 0);
		pane.add(protocolValueLabel, 1, 0);
		pane.add(addressLabel, 0, 1);
		pane.add(addressValueLabel, 1, 1);

		setValueClass(protocolValueLabel, addressValueLabel);

		return new Label[] { protocolValueLabel, addressValueLabel };
	}

	private Label[] initAudioPane(GridPane pane) {
		Label inboundLabel = new Label("Inbound");
		Label outboundLabel = new Label("Outbound");

		Label sentBytesLabel = new Label();
		Label receivedBytesLabel = new Label();
		Label inboundCodecLabel = new Label();
		Label outboundCodecLabel = new Label();

		pane.add(inboundLabel, 0, 0);
		pane.add(outboundLabel, 1, 0);
		pane.add(receivedBytesLabel, 0, 1);
		pane.add(sentBytesLabel, 1, 1);
		pane.add(inboundCodecLabel, 0, 2);
		pane.add(outboundCodecLabel, 1, 2);

		Label[] labels = {
				receivedBytesLabel, sentBytesLabel,
				inboundCodecLabel, outboundCodecLabel
		};

		setValueClass(labels);

		return labels;
	}

	private Label[] initVideoPane(GridPane pane) {
		Label inboundLabel = new Label("Inbound");
		Label outboundLabel = new Label("Outbound");

		Label sentBytesLabel = new Label();
		Label receivedBytesLabel = new Label();
		Label inboundCodecLabel = new Label();
		Label outboundCodecLabel = new Label();
		Label inboundFrameLabel = new Label();
		Label outboundFrameLabel = new Label();

		pane.add(inboundLabel, 0, 0);
		pane.add(outboundLabel, 1, 0);
		pane.add(receivedBytesLabel, 0, 1);
		pane.add(sentBytesLabel, 1, 1);
		pane.add(inboundCodecLabel, 0, 2);
		pane.add(outboundCodecLabel, 1, 2);
		pane.add(inboundFrameLabel, 0, 3);
		pane.add(outboundFrameLabel, 1, 3);

		Label[] labels = {
				receivedBytesLabel, sentBytesLabel,
				inboundCodecLabel, outboundCodecLabel,
				inboundFrameLabel, outboundFrameLabel
		};

		setValueClass(labels);

		return labels;
	}
}
