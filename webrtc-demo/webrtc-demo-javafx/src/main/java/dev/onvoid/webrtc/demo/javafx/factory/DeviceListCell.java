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

package dev.onvoid.webrtc.demo.javafx.factory;

import static java.util.Objects.isNull;

import dev.onvoid.webrtc.media.Device;

import javafx.scene.control.ListCell;

public class DeviceListCell extends ListCell<Device> {

	@Override
	protected void updateItem(Device item, boolean empty) {
		super.updateItem(item, empty);

		setGraphic(null);

		if (isNull(item) || empty) {
			setText(null);
		}
		else {
			setText(item.getName());
		}
	}

}
