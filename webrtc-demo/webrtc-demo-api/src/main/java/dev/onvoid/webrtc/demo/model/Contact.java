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

import dev.onvoid.webrtc.demo.beans.IntegerProperty;
import dev.onvoid.webrtc.demo.beans.StringProperty;

import java.util.Objects;

public class Contact {

	private StringProperty id;

	private StringProperty name;

	private IntegerProperty unreadMessages;


	public Contact() {
		this(null, null);
	}

	public Contact(String id, String name) {
		this.id = new StringProperty(id);
		this.name = new StringProperty(name);
		this.unreadMessages = new IntegerProperty(0);
	}

	public String getId() {
		return id.get();
	}

	public String getName() {
		return name.get();
	}

	public void setName(String name) {
		this.name.set(name);
	}

	public StringProperty nameProperty() {
		return name;
	}

	public int getUnreadMessages() {
		return unreadMessages.get();
	}

	public void setUnreadMessages(int count) {
		this.unreadMessages.set(count);
	}

	public IntegerProperty unreadMessagesProperty() {
		return unreadMessages;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}

		Contact contact = (Contact) o;

		return Objects.equals(getId(), contact.getId()) &&
				Objects.equals(getName(), contact.getName());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getId(), getName());
	}

	@Override
	public String toString() {
		return String.format("%s@%d [id=%s, name=%s, unreadMessages=%s]",
				Contact.class.getSimpleName(), hashCode(),
				getId(), getName(), getUnreadMessages());
	}
}
