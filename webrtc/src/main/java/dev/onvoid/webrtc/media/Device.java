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

package dev.onvoid.webrtc.media;

import java.util.Objects;

public abstract class Device {

	private final int index;

	private final String guid;

	private final String name;


	protected Device(String name, String guid, int index) {
		this.name = name;
		this.guid = guid;
		this.index = index;
	}

	public String getGuid() {
		return guid;
	}

	public String getName() {
		return name;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}

		Device device = (Device) o;

		return index == device.index && Objects.equals(guid, device.guid) &&
				Objects.equals(name, device.name);
	}

	@Override
	public int hashCode() {
		return Objects.hash(index, guid, name);
	}

	@Override
	public String toString() {
		return String.format("%s [index=%s, guid=%s, name=%s]",
				Device.class.getSimpleName(), index, guid, name);
	}
}
