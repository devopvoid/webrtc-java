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

	private final String descriptor;

	private final String name;

	private DeviceTransport deviceTransport = DeviceTransport.UNKNOWN;

	private DeviceFormFactor deviceFormFactor = DeviceFormFactor.UNKNOWN;

	protected Device(String name, String descriptor) {
		this.name = name;
		this.descriptor = descriptor;
	}

	public String getDescriptor() {
		return descriptor;
	}

	public String getName() {
		return name;
	}

	public DeviceTransport getDeviceTransport() {
		return deviceTransport;
	}

	public DeviceFormFactor getDeviceFormFactor() {
		return deviceFormFactor;
	}

	public void setDeviceFormFactor(DeviceFormFactor deviceFormFactor) {
		this.deviceFormFactor = deviceFormFactor;
	}

	public void setDeviceTransport(DeviceTransport deviceTransport) {
		this.deviceTransport = deviceTransport;
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

		return Objects.equals(descriptor, device.descriptor) &&
				Objects.equals(name, device.name);
	}

	@Override
	public int hashCode() {
		return Objects.hash(descriptor, name);
	}

	@Override
	public String toString() {
		return String.format("%s [name=%s, descriptor=%s, transport=%s, formFactor=%s]",
				Device.class.getSimpleName(), name, descriptor, deviceTransport.toString(), deviceFormFactor.toString());
	}
}
