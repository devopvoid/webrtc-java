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

package dev.onvoid.webrtc.demo.net.codec;

import static java.util.Objects.isNull;

import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;
import javax.json.JsonWriter;

public class JsonCodec {

	public String encode(Object obj) throws IOException {
		JsonObjectBuilder builder = null;

		if (obj instanceof RTCSessionDescription) {
			RTCSessionDescription desc = (RTCSessionDescription) obj;

			builder = Json.createObjectBuilder();
			builder.add("sdp", desc.sdp);
			builder.add("type", desc.sdpType.toString().toLowerCase());
		}
		else if (obj instanceof RTCIceCandidate) {
			RTCIceCandidate candidate = (RTCIceCandidate) obj;

			builder = Json.createObjectBuilder();
			builder.add("candidate", candidate.sdp);
			builder.add("sdpMid", candidate.sdpMid);
			builder.add("sdpMLineIndex", candidate.sdpMLineIndex);
		}

		if (isNull(builder)) {
			return null;
		}

		StringWriter stringWriter = new StringWriter();

		JsonWriter writer = Json.createWriter(stringWriter);
		writer.writeObject(builder.build());
		writer.close();

		return stringWriter.toString();
	}

	public Object decode(String json) throws IOException {
		JsonReader reader = Json.createReader(new StringReader(json));
		JsonObject jsonObject = reader.readObject();

		Object result = null;

		if (jsonObject.containsKey("sdp") && jsonObject.containsKey("type")) {
			String sdp = jsonObject.getString("sdp");
			String type = jsonObject.getString("type");

			RTCSdpType rtcType;

			if (type.equals("offer")) {
				rtcType = RTCSdpType.OFFER;
			}
			else if (type.equals("answer")) {
				rtcType = RTCSdpType.ANSWER;
			}
			else {
				throw new IOException("Invalid RTCSdpType");
			}

			result = new RTCSessionDescription(rtcType, sdp);
		}
		else if (jsonObject.containsKey("candidate")) {
			String candidate = jsonObject.getString("candidate");
			String sdpMid = jsonObject.getString("sdpMid");
			int sdpMLineIndex = jsonObject.getInt("sdpMLineIndex");

			result = new RTCIceCandidate(sdpMid, sdpMLineIndex, candidate, null);
		}

		reader.close();

		return result;
	}
}
