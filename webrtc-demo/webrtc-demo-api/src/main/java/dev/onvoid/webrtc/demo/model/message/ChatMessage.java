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

package dev.onvoid.webrtc.demo.model.message;

import java.time.LocalTime;

public abstract class ChatMessage {

	public enum Origin {

		LOCAL,

		REMOTE;

	}

	private transient Origin origin;

	private transient LocalTime time;


	public ChatMessage() {
		this(Origin.LOCAL, LocalTime.now());
	}

	public ChatMessage(Origin origin, LocalTime time) {
		this.origin = origin;
		this.time = time;
	}

	public Origin getOrigin() {
		return origin;
	}

	public void setOrigin(Origin origin) {
		this.origin = origin;
	}

	public LocalTime getTime() {
		return time;
	}

	public void setTime(LocalTime time) {
		this.time = time;
	}
}
