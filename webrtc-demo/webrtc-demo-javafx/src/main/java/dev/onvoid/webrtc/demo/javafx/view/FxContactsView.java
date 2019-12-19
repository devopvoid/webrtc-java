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

package dev.onvoid.webrtc.demo.javafx.view;

import dev.onvoid.webrtc.demo.javafx.control.skin.ExtListViewSkin;
import dev.onvoid.webrtc.demo.javafx.util.FxUtils;
import dev.onvoid.webrtc.demo.model.Contact;
import dev.onvoid.webrtc.demo.model.Contacts;
import dev.onvoid.webrtc.demo.view.Action;
import dev.onvoid.webrtc.demo.view.ChatMessageView;
import dev.onvoid.webrtc.demo.view.ConsumerAction;
import dev.onvoid.webrtc.demo.view.ContactsView;

import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionModel;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;

@FxmlView(name = "contacts")
public class FxContactsView extends BorderPane implements ContactsView {

	@FXML
	private Button settingsButton;

	@FXML
	private ListView<Contact> contactList;

	@FXML
	private Pane chatControls;

	@FXML
	private Label contactNameLabel;

	@FXML
	private Button desktopShareButton;

	@FXML
	private Button audioCallButton;

	@FXML
	private Button videoCallButton;

	@FXML
	private ScrollPane messageScrollPane;

	@FXML
	private Pane messageContainer;

	@FXML
	private TextArea textArea;

	@FXML
	private Button sendButton;

	private ConsumerAction<Contact> onContactSelection;


	@Override
	public void addChatMessage(ChatMessageView messageView) {
		FxUtils.checkNodeView(messageView);

		FxUtils.invoke(() -> {
			messageContainer.getChildren().add((Node) messageView);
		});
	}

	@Override
	public void setContact(Contact contact) {
		FxUtils.invoke(() -> {
			contactNameLabel.setText(contact.getName());

			messageContainer.getChildren().clear();

			setCenter(chatControls);
		});
	}

	@Override
	public void addContact(Contact contact) {
		FxUtils.invoke(() -> contactList.getItems().add(contact));
	}

	@Override
	public void removeContact(Contact contact) {
		FxUtils.invoke(() -> contactList.getItems().remove(contact));
	}

	@Override
	public void setContacts(Contacts contacts) {
		FxUtils.invoke(() -> contactList.getItems().addAll(contacts));
	}

	@Override
	public void setOnSettings(Action action) {
		FxUtils.bindAction(settingsButton, action);
	}

	@Override
	public void setOnShareDesktop(Action action) {
		FxUtils.bindAction(desktopShareButton, action);
	}

	@Override
	public void setOnAudioCall(Action action) {
		FxUtils.bindAction(audioCallButton, action);
	}

	@Override
	public void setOnVideoCall(Action action) {
		FxUtils.bindAction(videoCallButton, action);
	}

	@Override
	public void setOnSendMessage(ConsumerAction<String> action) {
		FxUtils.bindAction(sendButton, () -> {
			action.execute(textArea.getText());

			textArea.setText("");
		});
	}

	@Override
	public void setOnContactSelection(ConsumerAction<Contact> action) {
		onContactSelection = action;
	}

	@FXML
	private void initialize() {
		sceneProperty().addListener(new InvalidationListener() {

			@Override
			public void invalidated(Observable observable) {
				sceneProperty().removeListener(this);

				contactList.requestFocus();
			}
		});

		SelectionModel<Contact> model = contactList.getSelectionModel();
		model.selectedItemProperty().addListener((observable, oldContact, newContact) -> {
			executeAction(onContactSelection, newContact);
		});

		contactList.setSkin(new ExtListViewSkin<>(contactList));

		textArea.prefHeightProperty().bind(sendButton.heightProperty().divide(2));

		messageScrollPane.vvalueProperty().bind(messageContainer.heightProperty());
	}

}
