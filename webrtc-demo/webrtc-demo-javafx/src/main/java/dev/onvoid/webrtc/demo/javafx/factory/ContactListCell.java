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
import static java.util.Objects.nonNull;

import dev.onvoid.webrtc.demo.javafx.beans.IntegerPropertyAdapter;
import dev.onvoid.webrtc.demo.javafx.beans.StringPropertyAdapter;
import dev.onvoid.webrtc.demo.javafx.control.Badge;
import dev.onvoid.webrtc.demo.model.Contact;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

public class ContactListCell extends ListCell<Contact> {

	private final Label contactLabel;

	private final Badge badge;

	private final HBox container;

	private StringPropertyAdapter name;

	private IntegerPropertyAdapter unreadMessages;


	ContactListCell() {
		contactLabel = new Label();
		contactLabel.setMaxWidth(Double.MAX_VALUE);
		contactLabel.textFillProperty().bind(textFillProperty());

		badge = new Badge();

		container = new HBox();
		container.setAlignment(Pos.CENTER_LEFT);
		container.getChildren().addAll(contactLabel, badge);

		HBox.setHgrow(contactLabel, Priority.ALWAYS);

		setText(null);
	}

	@Override
	protected void updateItem(Contact item, boolean empty) {
		super.updateItem(item, empty);

		if (nonNull(name)) {
			name.unbind();
		}
		if (nonNull(unreadMessages)) {
			unreadMessages.unbind();
		}

		if (isNull(item) || empty) {
			setGraphic(null);
		}
		else {
			name = new StringPropertyAdapter(item.nameProperty());
			unreadMessages = new IntegerPropertyAdapter(item.unreadMessagesProperty());

			contactLabel.textProperty().bind(name);

			badge.textProperty().bind(unreadMessages.asString());
			badge.visibleProperty().bind(unreadMessages.greaterThan(0));

			setGraphic(container);
		}
	}

}
