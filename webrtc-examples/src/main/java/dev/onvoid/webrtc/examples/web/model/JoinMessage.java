/*
 * Copyright 2025 Alex Andres
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

package dev.onvoid.webrtc.examples.web.model;

/**
 * Represents a join message used in WebRTC signaling.
 * This class is used when a peer wants to join a room or session.
 *
 * @author Alex Andres
 */
public class JoinMessage extends SignalingMessage {

    private JoinData data;


    /**
     * Default constructor required for JSON deserialization.
     */
    public JoinMessage() {
        super(MessageType.JOIN.getValue());
    }

    /**
     * Creates a new JoinMessage with the specified sender, room, and user information.
     *
     * @param from     the sender ID.
     * @param room     the room to join.
     * @param userName the user's name.
     */
    public JoinMessage(String from, String room, String userName) {
        super(MessageType.JOIN.getValue(), from, null);

        data = new JoinData(room, new UserInfo(userName, from));
    }

    /**
     * Gets the join data.
     *
     * @return the join data.
     */
    public JoinData getData() {
        return data;
    }

    /**
     * Sets the join data.
     *
     * @param data the join data.
     */
    public void setData(JoinData data) {
        this.data = data;
    }



    /**
     * Inner class representing the data payload of a join message.
     */
    public static class JoinData {

        private String room;
        private UserInfo userInfo;


        /**
         * Default constructor required for JSON deserialization.
         */
        public JoinData() {
        }

        /**
         * Creates a new JoinData with the specified room.
         *
         * @param room the room to join
         */
        public JoinData(String room) {
            this.room = room;
        }

        /**
         * Creates a new JoinData with the specified room and user information.
         *
         * @param room     the room to join
         * @param userInfo the user information
         */
        public JoinData(String room, UserInfo userInfo) {
            this.room = room;
            this.userInfo = userInfo;
        }

        /**
         * Gets the room.
         *
         * @return the room
         */
        public String getRoom() {
            return room;
        }

        /**
         * Sets the room.
         *
         * @param room the room
         */
        public void setRoom(String room) {
            this.room = room;
        }

        /**
         * Gets the user information.
         *
         * @return the user information
         */
        public UserInfo getUserInfo() {
            return userInfo;
        }

        /**
         * Sets the user information.
         *
         * @param userInfo the user information
         */
        public void setUserInfo(UserInfo userInfo) {
            this.userInfo = userInfo;
        }
    }



    /**
     * Inner class representing user information.
     */
    public static class UserInfo {

        private String userId;

        private String name;


        /**
         * Default constructor required for JSON deserialization.
         */
        public UserInfo() {
        }

        /**
         * Creates a new UserInfo with the specified name and avatar.
         *
         * @param name   the user's name.
         * @param userId the user's ID.
         */
        public UserInfo(String name, String userId) {
            this.name = name;
            this.userId = userId;
        }

        /**
         * Gets the user's name.
         *
         * @return the user's name.
         */
        public String getName() {
            return name;
        }

        /**
         * Sets the user's name.
         *
         * @param name the user's name.
         */
        public void setName(String name) {
            this.name = name;
        }

        /**
         * Gets the user's ID.
         *
         * @return the user's ID.
         */
        public String getUserId() {
            return userId;
        }

        /**
         * Sets the user's ID.
         *
         * @param userId the user's ID.
         */
        public void setUserId(String userId) {
            this.userId = userId;
        }
    }
}