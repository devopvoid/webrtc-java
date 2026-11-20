/*
 * Copyright 2026 Alex Andres
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

package dev.onvoid.webrtc.livekit;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import dev.onvoid.webrtc.client.LocalTrack;
import dev.onvoid.webrtc.client.RemoteTrack;
import dev.onvoid.webrtc.client.Subscription;

/**
 * A joined LiveKit room. It holds two peers of the runtime, one that publishes
 * and one that subscribes, and the signaling connection that negotiates them.
 * <p>
 * Handlers run on the room's own thread, one at a time and in the order the
 * server sent what they report. A handler that blocks holds up the room, so
 * long work belongs on another thread. The futures complete on that thread
 * too.
 */
public interface Room extends AutoCloseable {

	/**
	 * @return the name of the room.
	 */
	String name();

	/**
	 * @return the connection state.
	 */
	RoomState state();

	/**
	 * @return this client's participant.
	 */
	Participant localParticipant();

	/**
	 * @return the other participants, as of the last update of the server.
	 */
	List<Participant> remoteParticipants();

	/**
	 * Publishes a local track: the server accepts it, then the publisher peer
	 * negotiates it.
	 *
	 * @param track  the track, which stays the application's to close.
	 * @param source where the track comes from.
	 *
	 * @return a future with the publication, which fails if the server
	 * refuses the track or does not answer in time.
	 */
	CompletableFuture<Publication> publish(LocalTrack track, TrackSource source);

	/**
	 * Publishes a local track as the options say, such as a video track in
	 * simulcast layers. With simulcast the server pauses the layers no
	 * subscriber wants (LiveKit's dynacast), and resumes them once one does.
	 *
	 * @param track   the track, which stays the application's to close.
	 * @param options how to publish it.
	 *
	 * @return a future with the publication, which fails if the server
	 * refuses the track or does not answer in time.
	 */
	CompletableFuture<Publication> publish(LocalTrack track, PublishOptions options);

	/**
	 * Asks the server to forward a remote video track in another simulcast
	 * layer. The server forwards a smaller one while the connection takes
	 * no more.
	 *
	 * @param trackSid the server's id of the remote track, its {@link
	 *                 RemoteTrack#id() id}.
	 * @param quality  the layer.
	 */
	void requestVideoQuality(String trackSid, VideoQuality quality);

	/**
	 * Sends data to every other participant of the room.
	 *
	 * @param payload  the data.
	 * @param topic    a topic the receivers can tell messages apart by, or
	 *                 null.
	 * @param reliable true to send it ordered and retransmitted, false to
	 *                 send it once, for data that is stale soon, such as a
	 *                 pointer position.
	 *
	 * @return a future that completes once the data is handed to the channel.
	 */
	CompletableFuture<Void> publishData(byte[] payload, String topic, boolean reliable);

	/**
	 * @param handler gets each participant that joins after this client.
	 *
	 * @return removes the handler.
	 */
	Subscription onParticipantJoined(Consumer<Participant> handler);

	/**
	 * @param handler gets each participant that leaves.
	 *
	 * @return removes the handler.
	 */
	Subscription onParticipantLeft(Consumer<Participant> handler);

	/**
	 * @param handler gets each remote track this client receives, with the
	 *                participant that publishes it. Tracks that arrived
	 *                before the handler, and have not ended, are handed to it
	 *                too.
	 *
	 * @return removes the handler.
	 */
	Subscription onTrackSubscribed(BiConsumer<Participant, RemoteTrack> handler);

	/**
	 * @param handler gets the data other participants publish.
	 *
	 * @return removes the handler.
	 */
	Subscription onData(Consumer<DataMessage> handler);

	/**
	 * @param handler gets the reason each time the connection is lost and
	 *                the room starts to get it back.
	 *
	 * @return removes the handler.
	 */
	Subscription onReconnecting(Consumer<String> handler);

	/**
	 * @param handler runs each time the connection is back. If the session
	 *                could not be resumed, the room joined anew: the local
	 *                participant then has a new sid, the participants were
	 *                reported as left and joined again, and the publications
	 *                have new sids.
	 *
	 * @return removes the handler.
	 */
	Subscription onReconnected(Runnable handler);

	/**
	 * @param handler gets the reason once the room is left, for whatever
	 *                reason, the application's close included.
	 *
	 * @return removes the handler.
	 */
	Subscription onDisconnected(Consumer<String> handler);

	/**
	 * Leaves the room and closes its peers. The tracks stay the
	 * application's.
	 *
	 * @return a future that completes once the peers are closed.
	 */
	CompletableFuture<Void> closeAsync();

	/**
	 * Leaves the room and waits for its peers to close.
	 */
	@Override
	void close();

}
