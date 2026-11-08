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

import java.util.Arrays;
import java.util.Locale;

/** An explicit export schema: never serializes raw stats or arbitrary string attributes. */
final class DiagnosticExport {
	private DiagnosticExport() { }

	static String json(PeerStats stats) {
		StringBuilder out = new StringBuilder("{\"schemaVersion\":1");
		field(out, "timestamp", stats.timestamp().toString());
		field(out, "state", stats.diagnostics().state().name());
		field(out, "os", platform(System.getProperty("os.name", "")));
		field(out, "architecture", allowed(System.getProperty("os.arch", ""),
				"amd64", "x86_64", "aarch64", "arm", "x86", "i386"));
		String javaVersion = System.getProperty("java.specification.version", "");
		field(out, "javaVersion", javaVersion.matches("[0-9]{1,3}(\\.[0-9]{1,3})?") ? javaVersion : "other");
		String version = PeerStats.class.getPackage().getImplementationVersion();
		field(out, "libraryVersion", version != null && version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(-SNAPSHOT)?")
				? version : "unversioned");
		number(out, "availableProcessors", Runtime.getRuntime().availableProcessors());
		number(out, "bytesSent", stats.bytesSent());
		number(out, "bytesReceived", stats.bytesReceived());
		number(out, "outgoingBitrate", stats.outgoingBitrate());
		number(out, "incomingBitrate", stats.incomingBitrate());
		number(out, "packetLoss", stats.packetLoss());
		number(out, "recentPacketLoss", stats.recentPacketLoss());
		out.append(",\"policy\":{\"confirmationSeconds\":").append(DiagnosticsReader.WINDOW.getSeconds());
		number(out, "minimumMeasurements", DiagnosticsReader.MIN_SAMPLES);
		number(out, "lossEnter", DiagnosticsReader.LOSS_ENTER);
		number(out, "lossExit", DiagnosticsReader.LOSS_EXIT);
		number(out, "maximumGapSeconds", DiagnosticsReader.MAX_GAP.getSeconds());
		out.append('}');
		out.append(",\"conditions\":[");
		boolean comma = false;
		for (QualityCondition condition : stats.diagnostics().conditions()) {
			if (comma) {
				out.append(',');
			}
			out.append("{\"cause\":");
			Json.writeString(out, condition.name());
			field(out, "reason", condition.reason());
			out.append(",\"problem\":").append(condition.isProblem()).append('}');
			comma = true;
		}
		out.append("],\"transports\":[");
		comma = false;
		for (TransportStats transport : stats.transports()) {
			if (comma) {
				out.append(',');
			}
			out.append("{\"relayed\":").append(transport.relayed());
			field(out, "candidateType", allowed(transport.candidateType(), "host", "srflx", "prflx", "relay"));
			number(out, "rttSeconds", transport.roundTripTime() == null ? null
					: transport.roundTripTime().toNanos() / 1e9);
			out.append('}');
			comma = true;
		}
		out.append("],\"video\":[");
		comma = false;
		for (boolean outbound : new boolean[] { false, true }) {
			for (VideoStats video : outbound ? stats.outboundVideo() : stats.inboundVideo()) {
				if (comma) {
					out.append(',');
				}
				out.append("{\"outbound\":").append(outbound);
				field(out, "codec", codec(video.codec()));
				number(out, "width", video.width());
				number(out, "height", video.height());
				number(out, "framesPerSecond", video.framesPerSecond());
				field(out, "qualityLimitation", allowed(video.qualityLimitation(), "none", "cpu", "bandwidth", "other"));
				out.append('}');
				comma = true;
			}
		}
		out.append("],\"audio\":[");
		comma = false;
		for (boolean outbound : new boolean[] { false, true }) {
			for (AudioStats audio : outbound ? stats.outboundAudio() : stats.inboundAudio()) {
				if (comma) {
					out.append(',');
				}
				out.append("{\"outbound\":").append(outbound);
				field(out, "codec", codec(audio.codec()));
				number(out, "packetsReceived", audio.packetsReceived());
				number(out, "jitterSeconds", audio.jitter() == null ? null : audio.jitter().toNanos() / 1e9);
				number(out, "concealedSamples", audio.concealedSamples());
				number(out, "concealmentEvents", audio.concealmentEvents());
				number(out, "audioLevel", audio.audioLevel());
				number(out, "totalSamplesReceived", audio.totalSamplesReceived());
				number(out, "pcmCallbacks", audio.pcmCallbacks());
				out.append('}');
				comma = true;
			}
		}
		return out.append("]}").toString();
	}

	private static String codec(String value) {
		if (value == null) {
			return null;
		}
		return allowed(value.toLowerCase(Locale.ROOT), "audio/opus", "audio/pcmu", "audio/pcma",
				"audio/g722", "audio/isac", "audio/cn", "audio/telephone-event", "audio/red",
				"video/vp8", "video/vp9", "video/h264", "video/h265", "video/av1", "video/rtx",
				"video/red", "video/ulpfec", "video/flexfec-03");
	}

	private static String allowed(String value, String... allowed) {
		return value == null ? null : Arrays.asList(allowed).contains(value) ? value : "other";
	}

	private static String platform(String name) {
		if (name.startsWith("Windows")) {
			return "Windows";
		}
		if (name.equals("Mac OS X")) {
			return "macOS";
		}
		return name.equals("Linux") ? "Linux" : "other";
	}

	private static void field(StringBuilder out, String name, String value) {
		out.append(',');
		Json.writeString(out, name);
		out.append(':');
		if (value == null) {
			out.append("null");
		}
		else Json.writeString(out, value);
	}

	private static void number(StringBuilder out, String name, Number value) {
		out.append(',');
		Json.writeString(out, name);
		out.append(':');
		out.append(value == null || !Double.isFinite(value.doubleValue()) ? "null" : value.toString());
	}
}
