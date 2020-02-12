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
	exports dev.onvoid.webrtc.demo.presenter;
	exports dev.onvoid.webrtc.demo.service;
	exports dev.onvoid.webrtc.demo.util;
	exports dev.onvoid.webrtc.demo.view;

}