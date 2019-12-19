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

package dev.onvoid.webrtc.demo.model;

import java.util.HashMap;
import java.util.Map;

public class Chats {

	private final Map<Contact, ChatHistory> historyMap;


	public Chats() {
		historyMap = new HashMap<>();
	}

	public ChatHistory getChatHistory(Contact contact) {
		return historyMap.get(contact);
	}

	public void setChatHistory(Contact contact, ChatHistory history) {
		historyMap.put(contact, history);
	}
}
