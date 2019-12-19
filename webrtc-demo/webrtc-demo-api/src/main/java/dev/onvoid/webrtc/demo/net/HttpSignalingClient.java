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

import static java.util.Objects.nonNull;

import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.demo.model.Contact;
import dev.onvoid.webrtc.demo.net.codec.JsonCodec;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import javax.inject.Inject;
import javax.inject.Named;

public class HttpSignalingClient implements SignalingClient {

	private static Logger LOGGER = System.getLogger(HttpSignalingClient.class.getName());

	private final String serverUrl;

	private final HttpClient client;

	private final Set<Contact> remotePeers;

	private final JsonCodec messageCodec;

	private long myId;

	private BiConsumer<Contact, Boolean> contactEventConsumer;

	private BiConsumer<Contact, RTCIceCandidate> iceCandidateConsumer;

	private BiConsumer<Contact, RTCSessionDescription> sessionDescConsumer;


	@Inject
	public HttpSignalingClient(
			@Named("Signaling Server") String server,
			@Named("Signaling Server Port") int port) {
		serverUrl = "http://" + server + ":" + port;
		client = HttpClient.newHttpClient();
		remotePeers = new HashSet<>();
		messageCodec = new JsonCodec();
	}

	@Override
	public Set<Contact> getRemotePeers() {
		return remotePeers;
	}

	@Override
	public void setContactEventConsumer(BiConsumer<Contact, Boolean> consumer) {
		this.contactEventConsumer = consumer;
	}

	@Override
	public void setIceCandidateConsumer(BiConsumer<Contact, RTCIceCandidate> consumer) {
		this.iceCandidateConsumer = consumer;
	}

	@Override
	public void setSessionDescriptionConsumer(BiConsumer<Contact, RTCSessionDescription> consumer) {
		this.sessionDescConsumer = consumer;
	}

	@Override
	public void login(Contact asContact) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(serverUrl + "/sign_in?" + asContact.getName()))
				.build();

		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

		if (response.statusCode() != 200) {
			return;
		}

		String peerList = response.body();
		String[] peers = peerList.split("\n");

		myId = Integer.parseInt(peers[0].split(",")[1]);

		LOGGER.log(Level.INFO, "Signed in as \"{0}\" with id \"{1}\"",
				asContact.getName(), myId);

		for (String peer : peers) {
			updatePeers(peer);
		}

		startHangingGet();
	}

	@Override
	public void logout() {
		if (myId < 1) {
			return;
		}

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(serverUrl + "/sign_out?peer_id=" + myId))
				.build();

		myId = -1;

		client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
	}

	@Override
	public void send(Contact contact, Object obj) throws Exception {
		send(contact, messageCodec.encode(obj));
	}

	private void send(Contact contact, String body) throws Exception {
		if (myId < 1) {
			throw new IllegalStateException("Not connected");
		}
		if (myId == Long.parseLong(contact.getId())) {
			throw new IllegalStateException("Can't send a message to oneself");
		}

		LOGGER.log(Level.INFO, "Sending: " + body);

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(serverUrl + "/message?peer_id=" + myId + "&to=" + contact.getId()))
				.header("Content-Type", "text/plain")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();

		try {
			client.send(request, HttpResponse.BodyHandlers.discarding());
		}
		catch (InterruptedException e) {
			LOGGER.log(Level.ERROR, "Send message failed", e);
		}
	}

	private void startHangingGet() {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(serverUrl + "/wait?peer_id=" + myId))
				.build();

		CompletableFuture<HttpResponse<String>> future = client.sendAsync(request,
				HttpResponse.BodyHandlers.ofString());

		future.thenApply(response -> {
			long remotePeerId = response.headers().firstValueAsLong("Pragma").orElse(0);

			if (remotePeerId == myId) {
				handleServerNotification(response.body());
			}
			else {
				handlePeerMessage(findContact(remotePeerId), response.body());
			}

			return response;
		})
		.thenRun(this::startHangingGet);
	}

	private Contact findContact(long id) {
		return remotePeers.stream()
				.filter(contact -> Long.parseLong(contact.getId()) == id)
				.findFirst()
				.orElseThrow();
	}

	private void handlePeerMessage(Contact contact, String body) {
		Object decoded;

		try {
			decoded = messageCodec.decode(body);
		}
		catch (IOException e) {
			LOGGER.log(Level.ERROR, "Decode message failed: " + body, e);
			return;
		}

		if (decoded instanceof RTCIceCandidate && nonNull(iceCandidateConsumer)) {
			iceCandidateConsumer.accept(contact, (RTCIceCandidate) decoded);
		}
		else if (decoded instanceof RTCSessionDescription && nonNull(sessionDescConsumer)) {
			sessionDescConsumer.accept(contact, (RTCSessionDescription) decoded);
		}
	}

	private void handleServerNotification(String body) {
		updatePeers(body);
	}

	private void updatePeers(String peer) {
		if (peer.isEmpty() || peer.isBlank()) {
			return;
		}

		String[] parsed = peer.split(",");
		Contact contact = new Contact(parsed[1], parsed[0]);
		int id = Integer.parseInt(contact.getId());
		boolean offline = Integer.parseInt(parsed[2].trim()) == 0;

		if (id == myId) {
			return;
		}
		if (offline) {
			remotePeers.remove(contact);
		}
		else {
			remotePeers.add(contact);
		}

		if (nonNull(contactEventConsumer)) {
			contactEventConsumer.accept(contact, !offline);
		}
	}
}
