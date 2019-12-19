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

import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.demo.model.Contact;

import java.util.Set;
import java.util.function.BiConsumer;

public interface SignalingClient {

	Set<Contact> getRemotePeers() throws Exception;

	void login(Contact asContact) throws Exception;

	void logout();

	void setContactEventConsumer(BiConsumer<Contact, Boolean> consumer);

	void setIceCandidateConsumer(BiConsumer<Contact, RTCIceCandidate> consumer);

	void setSessionDescriptionConsumer(BiConsumer<Contact, RTCSessionDescription> consumer);

	void send(Contact contact, Object obj) throws Exception;

}
