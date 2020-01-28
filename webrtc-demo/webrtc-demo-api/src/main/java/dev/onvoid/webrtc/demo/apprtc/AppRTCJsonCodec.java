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

package dev.onvoid.webrtc.demo.apprtc;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.demo.apprtc.AppRTCMessage.Type;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonBuilderFactory;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;
import javax.json.JsonWriter;

public class AppRTCJsonCodec {

	AppRTCSignalingParameters toSignalingParameters(String json) {
		JsonReader reader = Json.createReader(new StringReader(json));
		JsonObject room = reader.readObject();

		String result = room.getString("result");
		if (!result.equals("SUCCESS")) {
			throw new RuntimeException(result);
		}

		room = room.getJsonObject("params");

		String clientId = room.getString("client_id");
		String wssUrl = room.getString("wss_url");
		String wssPostUrl = room.getString("wss_post_url");
		String iceServerUrl = room.getString("ice_server_url");
		boolean initiator = Boolean.parseBoolean(room.getString("is_initiator"));

		List<RTCIceCandidate> iceCandidates = null;
		RTCSessionDescription offer = null;

		if (!initiator) {
			iceCandidates = new ArrayList<>();
			JsonArray messages = room.getJsonArray("messages");

			for (int i = 0; i < messages.size(); ++i) {
				String messageString = messages.getString(i);

				reader = Json.createReader(new StringReader(messageString));

				JsonObject message = reader.readObject();
				String messageType = message.getString("type");

				if (messageType.equals("offer")) {
					offer = new RTCSessionDescription(
							RTCSdpType.valueOf(messageType.replace("_", "").toUpperCase()),
							message.getString("sdp"));
				}
				else if (messageType.equals("candidate")) {
					RTCIceCandidate candidate = new RTCIceCandidate(
							message.getString("id"),
							message.getInt("label"),
							message.getString("candidate"));

					iceCandidates.add(candidate);
				}
			}
		}

		List<RTCIceServer> iceServers = toJavaIceServers(room.getString("pc_config"));

		return new AppRTCSignalingParameters(iceServers, initiator, clientId,
											 wssUrl, wssPostUrl, iceServerUrl,
											 offer, iceCandidates);
	}

	String toJsonCommand(AppRTCCommand command) {
		JsonObjectBuilder builder = Json.createObjectBuilder();

		if (command instanceof AppRTCRegisterCommand) {
			AppRTCRegisterCommand registerCommand = (AppRTCRegisterCommand) command;

			builder.add("cmd", registerCommand.getCommand());
			builder.add("roomid", registerCommand.getRoomId());
			builder.add("clientid", registerCommand.getClientId());
		}
		else if (command instanceof AppRTCSendCommand) {
			AppRTCSendCommand sendCommand = (AppRTCSendCommand) command;

			builder.add("cmd", sendCommand.getCommand());
			builder.add("msg", sendCommand.getMessage());
		}

		return build(builder);
	}

	String toJsonMessage(AppRTCMessage message) {
		JsonObjectBuilder builder = Json.createObjectBuilder();

		switch (message.getType()) {
			case CANDIDATE:
				RTCIceCandidate candidate = (RTCIceCandidate) message.getObject();

				builder.add("type", "candidate");
				builder.add("label", candidate.sdpMLineIndex);
				builder.add("id", candidate.sdpMid);
				builder.add("candidate", candidate.sdp);
				break;

			case REMOVE_CANDIDATES:
				RTCIceCandidate[] candidates = (RTCIceCandidate[]) message.getObject();
				JsonBuilderFactory factory = Json.createBuilderFactory(Map.of());
				JsonArrayBuilder jsonArray = factory.createArrayBuilder();

				for (RTCIceCandidate c : candidates) {
					jsonArray.add(toJsonCandidate(c));
				}

				builder.add("type", "remove-candidates");
				builder.add("candidates", jsonArray);
				break;

			case ANSWER:
				RTCSessionDescription answer = (RTCSessionDescription) message.getObject();

				builder.add("type", "answer");
				builder.add("sdp", answer.sdp);
				break;

			case OFFER:
				RTCSessionDescription offer = (RTCSessionDescription) message.getObject();

				builder.add("type", "offer");
				builder.add("sdp", offer.sdp);
				break;

			case BYE:
				builder.add("type", "bye");
				break;

			default:
				break;
		}

		return build(builder);
	}

	AppRTCMessage toJavaMessage(String json) {
		JsonReader reader = Json.createReader(new StringReader(json));
		JsonObject jsonObject = reader.readObject();

		String msgText = jsonObject.getString("msg");
		String errorText = jsonObject.getString("error");

		if (msgText.length() > 0) {
			reader = Json.createReader(new StringReader(msgText));
			jsonObject = reader.readObject();
			String type = jsonObject.getString("type");

			switch (type) {
				case "candidate":
					return new AppRTCMessage(Type.CANDIDATE,
											 toJavaCandidate(jsonObject));

				case "remove-candidates":
					return new AppRTCMessage(Type.REMOVE_CANDIDATES,
											 toJavaCandidates(jsonObject));

				case "answer":
					return new AppRTCMessage(Type.ANSWER,
											 toJavaSessionDescription(jsonObject));

				case "offer":
					return new AppRTCMessage(Type.OFFER,
											 toJavaSessionDescription( jsonObject));

				case "bye":
					return new AppRTCMessage(Type.BYE);

				default:
					return new AppRTCMessage(Type.ERROR,
											 "Unexpected message: " + json);
			}
		}
		else {
			if (nonNull(errorText) && errorText.length() > 0) {
				return new AppRTCMessage(Type.ERROR, errorText);
			}
			else {
				return new AppRTCMessage(Type.ERROR,
										 "Unexpected message: " + json);
			}
		}
	}

	List<RTCIceServer> toJavaIceServers(String json) {
		JsonReader reader = Json.createReader(new StringReader(json));
		JsonObject jsonObject = reader.readObject();
		JsonArray servers = jsonObject.getJsonArray("iceServers");

		List<RTCIceServer> result = new ArrayList<>();

		if (isNull(servers)) {
			return result;
		}

		for (int i = 0; i < servers.size(); ++i) {
			JsonObject server = servers.getJsonObject(i);
			JsonArray urls = server.getJsonArray("urls");

			String credential = server.containsKey("credential") ?
					server.getString("credential") :
					"";
			String username = server.containsKey("username") ?
					server.getString("username") :
					"";

			RTCIceServer iceServer = new RTCIceServer();
			iceServer.username = username;
			iceServer.password = credential;

			for (int j = 0; j < urls.size(); j++) {
				iceServer.urls.add(urls.getString(j));
			}

			result.add(iceServer);
		}

		return result;
	}

	String toJavaPostResponse(String response) {
		JsonReader reader = Json.createReader(new StringReader(response));
		JsonObject roomJson = reader.readObject();

		return roomJson.getString("result");
	}

	private JsonObject toJsonCandidate(final RTCIceCandidate candidate) {
		JsonObjectBuilder builder = Json.createObjectBuilder()
				.add("label", candidate.sdpMLineIndex)
				.add("id", candidate.sdpMid)
				.add("candidate", candidate.sdp);

		return builder.build();
	}

	private RTCIceCandidate toJavaCandidate(JsonObject json) {
		return new RTCIceCandidate(json.getString("id"),
								   json.getInt("label"),
								   json.getString("candidate"));
	}

	private RTCIceCandidate[] toJavaCandidates(JsonObject json) {
		JsonArray candidateArray = json.getJsonArray("candidates");
		RTCIceCandidate[] candidates = new RTCIceCandidate[candidateArray.size()];

		for (int i = 0; i < candidateArray.size(); ++i) {
			candidates[i] = toJavaCandidate(candidateArray.getJsonObject(i));
		}

		return candidates;
	}

	private RTCSessionDescription toJavaSessionDescription(JsonObject json) {
		String type = json.getString("type");

		return new RTCSessionDescription(
				RTCSdpType.valueOf(type.replace("_", "").toUpperCase()),
				json.getString("sdp"));
	}

	private static String build(JsonObjectBuilder builder) {
		JsonObject json = builder.build();

		if (isNull(json) || json.isEmpty()) {
			return null;
		}

		StringWriter stringWriter = new StringWriter();

		JsonWriter writer = Json.createWriter(stringWriter);
		writer.writeObject(json);
		writer.close();

		return stringWriter.toString();
	}

}
