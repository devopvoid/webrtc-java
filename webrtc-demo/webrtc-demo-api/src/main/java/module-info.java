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

module webrtc.java.demo.api {

	requires com.google.guice;

	requires java.json;
	requires java.json.bind;
	requires java.logging;
	requires java.net.http;
	requires javax.inject;

	requires webrtc.java;

	exports dev.onvoid.webrtc.demo.apprtc;
	exports dev.onvoid.webrtc.demo.beans;
	exports dev.onvoid.webrtc.demo.config;
	exports dev.onvoid.webrtc.demo.context;
	exports dev.onvoid.webrtc.demo.event;
	exports dev.onvoid.webrtc.demo.inject;
	exports dev.onvoid.webrtc.demo.model;
	exports dev.onvoid.webrtc.demo.model.message;
	exports dev.onvoid.webrtc.demo.net;
	exports dev.onvoid.webrtc.demo.net.codec;
	exports dev.onvoid.webrtc.demo.presenter;
	exports dev.onvoid.webrtc.demo.service;
	exports dev.onvoid.webrtc.demo.util;
	exports dev.onvoid.webrtc.demo.view;

}
