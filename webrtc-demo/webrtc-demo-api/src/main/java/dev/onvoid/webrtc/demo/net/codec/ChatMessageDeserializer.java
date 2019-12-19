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

package dev.onvoid.webrtc.demo.net.codec;

import dev.onvoid.webrtc.demo.model.message.ChatMessage;

import java.lang.reflect.Type;

import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;
import javax.json.bind.serializer.DeserializationContext;
import javax.json.bind.serializer.JsonbDeserializer;
import javax.json.stream.JsonParser;

public class ChatMessageDeserializer implements JsonbDeserializer<ChatMessage> {

	/**
	 * Used to prevent recursive calls to the deserializer.
	 */
	private final Jsonb jsonb;


	ChatMessageDeserializer() {
		jsonb = JsonbBuilder.create();
	}

	@Override
	public ChatMessage deserialize(JsonParser parser, DeserializationContext ctx, Type type) {
		if (parser.hasNext()) {
			// Skip START_OBJECT.
			parser.next();
			// Get class name key.
			parser.next();

			String className = parser.getString();

			// Move to the concrete object.
			parser.next();

			String object = parser.getObject().toString();

			try {
				return jsonb.fromJson(object, Class.forName(className).asSubclass(ChatMessage.class));
			}
			catch (ClassNotFoundException e) {
				e.printStackTrace();
			}
		}

		return null;
	}

}
