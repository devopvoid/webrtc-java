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

package dev.onvoid.webrtc.examples.web.server;

import java.io.InputStream;
import java.security.KeyStore;

import dev.onvoid.webrtc.examples.web.WebClientExample;

import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.PathMappingsHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A web server implementation using Jetty that provides both HTTPS and WebSocket functionality.
 * <p>
 * This server:
 * <ul>
 *   <li>Runs on port 8443 with HTTPS only (TLS 1.2/1.3)</li>
 *   <li>Provides WebSocket endpoints with the "ws-signaling" protocol</li>
 *   <li>Serves static resources from the classpath at "/resources/web/"</li>
 *   <li>Uses a self-signed certificate from a bundled keystore</li>
 * </ul>
 * </p>
 *
 * @author Alex Andres
 */
public class WebServer {

    static {
        WebClientExample.setupLogging();
    }

    public static final Logger LOG = LoggerFactory.getLogger(WebServer.class);

    private static final int PORT = 8443;


    public static void main(String[] args) throws Exception {
        Server server = new Server();

        // Configure HTTPS only.
        HttpConfiguration httpsConfig = new HttpConfiguration();
        httpsConfig.addCustomizer(new SecureRequestCustomizer());

        // Create the HTTP/1.1 ConnectionFactory.
        HttpConnectionFactory http = new HttpConnectionFactory(httpsConfig);

        // Create the TLS ConnectionFactory, setting HTTP/1.1 as the wrapped protocol.
        SslContextFactory.Server sslContextFactory = createSslContextFactory();
        SslConnectionFactory tls = new SslConnectionFactory(sslContextFactory, http.getProtocol());

        ServerConnector httpsConnector = new ServerConnector(server, tls, http);
        httpsConnector.setPort(PORT);

        // Add only HTTPS connector.
        server.addConnector(httpsConnector);

        // Create WebSocket upgrade handler.
        WebSocketUpgradeHandler wsHandler = WebSocketUpgradeHandler.from(server, container -> {
            container.addMapping("/ws", (upgradeRequest, upgradeResponse, callback) -> {
                if (upgradeRequest.getSubProtocols().contains("ws-signaling")) {
                    upgradeResponse.setAcceptedSubProtocol("ws-signaling");
                    return new WebSocketHandler();
                }
                return null;
            });
        });

        // Create a static resource handler.
        ResourceHandler resourceHandler = new ResourceHandler();
        ResourceFactory resourceFactory = ResourceFactory.of(resourceHandler);
        resourceHandler.setBaseResource(resourceFactory.newClassLoaderResource("/resources/web/"));

        // Create a path mappings handler to combine WebSocket and static content.
        PathMappingsHandler pathHandler = new PathMappingsHandler();
        pathHandler.addMapping(PathSpec.from("/ws/*"), wsHandler);
        pathHandler.addMapping(PathSpec.from("/"), resourceHandler);

        server.setHandler(pathHandler);
        server.start();

        LOG.info("HTTPS: https://localhost:{}", PORT);
        LOG.info("WebSocket: wss://localhost:{}/ws", PORT);
        LOG.info("Note: Using self-signed certificate - browsers will show security warnings");

        server.join();
    }

    private static SslContextFactory.Server createSslContextFactory() {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();

        try (InputStream keystoreStream = WebServer.class.getResourceAsStream("/resources/keystore.p12")) {
            if (keystoreStream == null) {
                throw new IllegalArgumentException("Keystore not found in resources.");
            }

            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(keystoreStream, "l0c4lh0s7".toCharArray());

            sslContextFactory.setKeyStore(keyStore);
            sslContextFactory.setKeyStorePassword("l0c4lh0s7");
        }
        catch (Exception e) {
            LOG.error("Load keystore failed", e);
        }

        // Security settings.
        sslContextFactory.setIncludeProtocols("TLSv1.3", "TLSv1.2");

        return sslContextFactory;
    }
}
