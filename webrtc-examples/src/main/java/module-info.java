module webrtc.java.examples {

    requires com.fasterxml.jackson.databind;
    requires java.logging;
    requires java.net.http;
    requires org.eclipse.jetty.server;
    requires org.eclipse.jetty.websocket.server;
    requires webrtc.java;

    exports dev.onvoid.webrtc.examples.web.client;
    exports dev.onvoid.webrtc.examples.web.server;
    exports dev.onvoid.webrtc.examples.web.model;

}