/*
 * Copyright 2020 Alex Andres
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

module webrtc.java.demo.javafx {

	requires com.google.guice;

	requires java.desktop;
	requires javax.inject;
	requires javafx.controls;
	requires javafx.fxml;
	requires javafx.graphics;

	requires webrtc.java;
	requires webrtc.java.demo.api;

	exports dev.onvoid.webrtc.demo.javafx;
	exports dev.onvoid.webrtc.demo.javafx.control;
	exports dev.onvoid.webrtc.demo.javafx.inject;
	exports dev.onvoid.webrtc.demo.javafx.factory;
	exports dev.onvoid.webrtc.demo.javafx.view;
	exports dev.onvoid.webrtc.demo.javafx.view.builder;

	opens dev.onvoid.webrtc.demo.javafx to javafx.graphics;
	opens dev.onvoid.webrtc.demo.javafx.view to javafx.fxml;

}