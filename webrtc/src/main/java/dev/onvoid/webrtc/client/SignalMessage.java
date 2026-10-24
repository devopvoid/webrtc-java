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

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.Objects;

import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;

/**
 * A message one peer sends the other through its {@link Signaling}: a session
 * description or an ICE candidate.
 * <p>
 * {@link #toJson()} writes the shapes the browser API uses for {@code
 * RTCSessionDescriptionInit} and {@code RTCIceCandidateInit}, so a JavaScript
 * peer can pass them straight to {@code setRemoteDescription} and {@code
 * addIceCandidate}:
 * <pre>{@code
 * {"type":"offer","sdp":"v=0\r\n..."}
 * {"candidate":"candidate:842163049 1 udp ...","sdpMid":"0","sdpMLineIndex":0}
 * {"candidate":""}
 * }</pre>
 * The last one is the end of candidates. {@link #fromJson(String)} reads these,
 * as well as the {@code {"description": ...}} and {@code {"candidate": {...}}}
 * envelopes of the perfect negotiation examples for browsers.
 */
public final class SignalMessage {

	/** What a message carries. */
	public enum Type {

		/** A session description offer. */
		OFFER,

		/** A session description answer. */
		ANSWER,

		/** An ICE candidate. */
		CANDIDATE,

		/** The remote peer has no more candidates. */
		END_OF_CANDIDATES

	}

	private static final SignalMessage END_OF_CANDIDATES =
			new SignalMessage(Type.END_OF_CANDIDATES, null, null);

	/**
	 * The largest {@code sdpMLineIndex}, which the browser API types as an
	 * {@code unsigned short}.
	 */
	private static final int MAX_M_LINE_INDEX = 0xFFFF;

	private final Type type;

	private final RTCSessionDescription description;

	private final RTCIceCandidate candidate;


	private SignalMessage(Type type, RTCSessionDescription description,
			RTCIceCandidate candidate) {
		this.type = type;
		this.description = description;
		this.candidate = candidate;
	}

	/**
	 * @param description an offer or an answer.
	 *
	 * @return a message that carries the description.
	 *
	 * @throws IllegalArgumentException if the description is neither an offer
	 *                                  nor an answer.
	 */
	public static SignalMessage of(RTCSessionDescription description) {
		requireNonNull(description, "description");
		requireNonNull(description.sdp, "description.sdp");

		if (description.sdpType == RTCSdpType.OFFER) {
			return new SignalMessage(Type.OFFER, description, null);
		}
		if (description.sdpType == RTCSdpType.ANSWER) {
			return new SignalMessage(Type.ANSWER, description, null);
		}

		throw new IllegalArgumentException("Only offers and answers are signaled, not " + description.sdpType);
	}

	/**
	 * @param candidate a local ICE candidate.
	 *
	 * @return a message that carries the candidate.
	 *
	 * @throws IllegalArgumentException if the candidate's {@code
	 *                                  sdpMLineIndex} is not from 0 to
	 *                                  65535, which {@link #fromJson} would
	 *                                  not read back.
	 */
	public static SignalMessage of(RTCIceCandidate candidate) {
		requireNonNull(candidate, "candidate");
		requireNonNull(candidate.sdp, "candidate.sdp");

		if (candidate.sdp.isEmpty()) {
			return END_OF_CANDIDATES;
		}

		checkMLineIndex(candidate.sdpMLineIndex);

		return new SignalMessage(Type.CANDIDATE, null, candidate);
	}

	/**
	 * @return the message that says no more candidates follow.
	 */
	public static SignalMessage endOfCandidates() {
		return END_OF_CANDIDATES;
	}

	/**
	 * Reads a message written by {@link #toJson()} or by a browser.
	 *
	 * @param json the message as JSON.
	 *
	 * @return the message.
	 *
	 * @throws IllegalArgumentException if the text is no strict JSON or no
	 *                                  signaling message, is longer than a
	 *                                  MiB, nests arrays and objects deeper
	 *                                  than 64 levels, or has an {@code
	 *                                  sdpMLineIndex} that is not an integer
	 *                                  from 0 to 65535.
	 */
	public static SignalMessage fromJson(String json) {
		requireNonNull(json, "json");

		Object value = Json.parse(json);

		if (!(value instanceof Map)) {
			throw new IllegalArgumentException("A signaling message is a JSON object");
		}

		return fromObject((Map<?, ?>) value);
	}

	/**
	 * @return what the message carries.
	 */
	public Type type() {
		return type;
	}

	/**
	 * @return the session description of an {@link Type#OFFER} or an {@link
	 * Type#ANSWER}, otherwise null.
	 */
	public RTCSessionDescription description() {
		return description;
	}

	/**
	 * @return the ICE candidate of a {@link Type#CANDIDATE}, otherwise null.
	 */
	public RTCIceCandidate candidate() {
		return candidate;
	}

	/**
	 * @return the message as JSON, in the shapes the browser API uses.
	 */
	public String toJson() {
		StringBuilder out = new StringBuilder();

		switch (type) {
			case OFFER:
			case ANSWER:
				out.append("{\"type\":");
				Json.writeString(out, type == Type.OFFER ? "offer" : "answer");
				out.append(",\"sdp\":");
				Json.writeString(out, description.sdp);
				out.append('}');
				break;

			case CANDIDATE:
				out.append("{\"candidate\":");
				Json.writeString(out, candidate.sdp);
				out.append(",\"sdpMid\":");
				if (candidate.sdpMid == null) {
					out.append("null");
				}
				else {
					Json.writeString(out, candidate.sdpMid);
				}
				out.append(",\"sdpMLineIndex\":").append(candidate.sdpMLineIndex);
				out.append('}');
				break;

			default:
				out.append("{\"candidate\":\"\"}");
		}

		return out.toString();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof SignalMessage)) {
			return false;
		}

		SignalMessage other = (SignalMessage) obj;

		return type == other.type && Objects.equals(toJson(), other.toJson());
	}

	@Override
	public int hashCode() {
		return toJson().hashCode();
	}

	@Override
	public String toString() {
		return "SignalMessage" + toJson();
	}

	private static SignalMessage fromObject(Map<?, ?> object) {
		if (object.containsKey("description")) {
			Object description = object.get("description");

			if (!(description instanceof Map)) {
				throw new IllegalArgumentException("\"description\" is not an object");
			}

			return fromObject((Map<?, ?>) description);
		}

		if (object.containsKey("candidate")) {
			Object candidate = object.get("candidate");

			if (candidate == null) {
				return END_OF_CANDIDATES;
			}
			if (candidate instanceof Map) {
				return fromObject((Map<?, ?>) candidate);
			}
			if (!(candidate instanceof String)) {
				throw new IllegalArgumentException("\"candidate\" is not a string");
			}

			String sdp = (String) candidate;

			if (sdp.isEmpty()) {
				return END_OF_CANDIDATES;
			}

			Object mid = object.get("sdpMid");
			Object index = object.get("sdpMLineIndex");

			if (mid != null && !(mid instanceof String)) {
				throw new IllegalArgumentException("\"sdpMid\" is not a string");
			}
			if (index != null && !(index instanceof Number)) {
				throw new IllegalArgumentException("\"sdpMLineIndex\" is not a number");
			}
			if (mid == null && index == null) {
				throw new IllegalArgumentException("A candidate needs \"sdpMid\" or \"sdpMLineIndex\"");
			}

			return new SignalMessage(Type.CANDIDATE, null, new RTCIceCandidate(
					(String) mid, index == null ? 0 : mLineIndex((Number) index), sdp));
		}

		Object type = object.get("type");
		Object sdp = object.get("sdp");

		if (!(type instanceof String)) {
			throw new IllegalArgumentException("The message has neither \"type\" nor \"candidate\"");
		}
		if (!(sdp instanceof String)) {
			throw new IllegalArgumentException("The description has no \"sdp\" string");
		}

		switch ((String) type) {
			case "offer":
				return of(new RTCSessionDescription(RTCSdpType.OFFER, (String) sdp));
			case "answer":
				return of(new RTCSessionDescription(RTCSdpType.ANSWER, (String) sdp));
			default:
				throw new IllegalArgumentException("Unsupported description type \"" + type + "\"");
		}
	}

	/**
	 * @param number the {@code sdpMLineIndex} of a JSON message.
	 *
	 * @return the index, if the number is an integer from 0 to 65535.
	 */
	private static int mLineIndex(Number number) {
		double index = number.doubleValue();

		// Checked as a double, not after a cast, which would truncate 1.9 to 1
		// and clamp 1e300 to Integer.MAX_VALUE.
		if (index != Math.rint(index)) {
			throw new IllegalArgumentException("\"sdpMLineIndex\" is not an integer: " + number);
		}
		if (index < 0 || index > MAX_M_LINE_INDEX) {
			throw new IllegalArgumentException("\"sdpMLineIndex\" is not from 0 to " + MAX_M_LINE_INDEX
					+ ": " + (Math.abs(index) < 1e15 ? Long.toString((long) index) : number.toString()));
		}

		return (int) index;
	}

	private static void checkMLineIndex(int index) {
		if (index < 0 || index > MAX_M_LINE_INDEX) {
			throw new IllegalArgumentException("\"sdpMLineIndex\" is not from 0 to " + MAX_M_LINE_INDEX
					+ ": " + index);
		}
	}
}
