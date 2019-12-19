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

import javax.json.bind.serializer.JsonbSerializer;
import javax.json.bind.serializer.SerializationContext;
import javax.json.stream.JsonGenerator;

public class ChatMessageSerializer implements JsonbSerializer<ChatMessage> {

	@Override
	public void serialize(ChatMessage message, JsonGenerator generator, SerializationContext ctx) {
		if (message != null) {
			generator.writeStartObject(message.getClass().getName());
			ctx.serialize(message, generator);
			generator.writeEnd();
		}
		else {
			ctx.serialize(null, generator);
		}
	}
}
