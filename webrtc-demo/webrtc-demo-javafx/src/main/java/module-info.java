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
	exports dev.onvoid.webrtc.demo.javafx.view;
	exports dev.onvoid.webrtc.demo.javafx.view.builder;

	opens dev.onvoid.webrtc.demo.javafx to javafx.graphics;
	opens dev.onvoid.webrtc.demo.javafx.view to javafx.fxml;

}