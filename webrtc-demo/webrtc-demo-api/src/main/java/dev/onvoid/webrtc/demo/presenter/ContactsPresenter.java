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

package dev.onvoid.webrtc.demo.presenter;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import dev.onvoid.webrtc.demo.context.ViewContextFactory;
import dev.onvoid.webrtc.demo.event.ShowViewEvent;
import dev.onvoid.webrtc.demo.model.ChatHistory;
import dev.onvoid.webrtc.demo.model.Chats;
import dev.onvoid.webrtc.demo.model.Contact;
import dev.onvoid.webrtc.demo.model.message.ChatMessage;
import dev.onvoid.webrtc.demo.model.message.ChatTextMessage;
import dev.onvoid.webrtc.demo.service.PeerConnectionService;
import dev.onvoid.webrtc.demo.util.ListChangeListener;
import dev.onvoid.webrtc.demo.util.ObservableList;
import dev.onvoid.webrtc.demo.view.ChatMessageView;
import dev.onvoid.webrtc.demo.view.ChatTextMessageView;
import dev.onvoid.webrtc.demo.view.ContactsView;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.LocalTime;

import javax.inject.Inject;

public class ContactsPresenter extends Presenter<ContactsView> {

	private static Logger LOGGER = System.getLogger(ContactsPresenter.class.getName());

	private final ViewContextFactory viewFactory;

	private final PeerConnectionService peerConnectionService;

	private final Chats chats;

	private final MessagesListener messagesListener;

	private Contact selectedContact;


	@Inject
	ContactsPresenter(ContactsView view, ViewContextFactory viewFactory, PeerConnectionService peerConnectionService) {
		super(view);

		this.viewFactory = viewFactory;
		this.peerConnectionService = peerConnectionService;
		this.chats = new Chats();
		this.messagesListener = new MessagesListener();
	}

	@Override
	public void initialize() {
		view.setOnSettings(this::onSettings);
		view.setOnShareDesktop(this::onShareDesktop);
		view.setOnAudioCall(this::initAudioCall);
		view.setOnVideoCall(this::initVideoCall);
		view.setOnSendMessage(this::onSendMessage);
		view.setOnContactSelection(this::setSelectedContact);

		peerConnectionService.setContactEventConsumer(this::onContactEvent);
		peerConnectionService.setOnMessage(this::onRemoteMessage);
		peerConnectionService.getContacts()
				.thenAccept(view::setContacts)
				.exceptionally(this::getContactsError);
	}

	@Override
	public void destroy() {
		peerConnectionService.setContactEventConsumer(null);
		peerConnectionService.setOnMessage(null);

		super.destroy();
	}

	private void onSettings() {
		publishEvent(new ShowViewEvent(SettingsPresenter.class));
	}

	private void onShareDesktop() {

	}

	private void initAudioCall() {
		peerConnectionService.call(selectedContact, false);
	}

	private void initVideoCall() {
		peerConnectionService.call(selectedContact, true);
	}

	private void setSelectedContact(Contact contact) {
		if (nonNull(selectedContact)) {
			ChatHistory chatHistory = getChatHistory(selectedContact);
			chatHistory.getMessages().removeListener(messagesListener);
		}

		selectedContact = contact;
		selectedContact.setUnreadMessages(0);

//		peerConnectionService.connect(contact);

		ChatHistory chatHistory = getChatHistory(contact);
		chatHistory.getMessages().addListener(messagesListener);

		view.setContact(contact);

		for (ChatMessage message : chatHistory.getMessages()) {
			view.addChatMessage(createChatMessageView(message));
		}
	}

	private void onContactEvent(Contact contact, boolean active) {
		if (active) {
			view.addContact(contact);
		}
		else {
			view.removeContact(contact);
		}
	}

	private void onSendMessage(String text) {
		final ChatTextMessage textMessage = new ChatTextMessage(text);
		textMessage.setOrigin(ChatMessage.Origin.LOCAL);
		textMessage.setTime(LocalTime.now());

		peerConnectionService.sendMessage(textMessage, selectedContact)
				.exceptionally(throwable -> {
					LOGGER.log(Level.ERROR, "Send message failed", throwable);
					//view.setError(error.getCause().getMessage());
					return null;
				})
				.thenRun(() -> {
					ChatHistory history = getChatHistory(selectedContact);
					history.getMessages().add(textMessage);
				});
	}

	private void onRemoteMessage(Contact contact, ChatMessage message) {
		if (!contact.equals(selectedContact)) {
			contact.setUnreadMessages(contact.getUnreadMessages() + 1);
		}

		ChatHistory history = getChatHistory(contact);
		history.getMessages().add(message);
	}

	private Void getContactsError(Throwable throwable) {
		LOGGER.log(Level.ERROR, "Get contacts failed", throwable);

		//view.setError(error.getCause().getMessage());
		return null;
	}

	private ChatHistory getChatHistory(Contact contact) {
		ChatHistory chatHistory = chats.getChatHistory(contact);

		if (isNull(chatHistory)) {
			chatHistory = new ChatHistory();

			chats.setChatHistory(contact, chatHistory);
		}

		return chatHistory;
	}

	private ChatMessageView createChatMessageView(ChatMessage message) {
		if (message instanceof ChatTextMessage) {
			var view = viewFactory.getInstance(ChatTextMessageView.class);
			view.setTextMessage((ChatTextMessage) message);

			return view;
		}

		return null;
	}



	private class MessagesListener implements ListChangeListener<ObservableList<ChatMessage>> {

		@Override
		public void listItemsInserted(ObservableList<ChatMessage> list,
				int startIndex, int itemCount) {
			for (int i = startIndex; i < startIndex + itemCount; i++) {
				ChatMessage message = list.get(i);
				view.addChatMessage(createChatMessageView(message));
			}
		}
	}
}
