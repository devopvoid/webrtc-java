## webrtc-java

[![](https://github.com/devopvoid/webrtc-java/workflows/Maven%20CI/badge.svg)](https://github.com/devopvoid/webrtc-java/actions)

Java native interface implementation based on the free, open [WebRTC](https://webrtc.org) project. The goal of this project is to enable development of RTC applications for desktop platforms running Java. This project wraps the WebRTC Native API and is similar to the [JS API](https://w3c.github.io/webrtc-pc).

```xml
<dependency>
    <groupId>dev.onvoid.webrtc</groupId>
    <artifactId>webrtc-java</artifactId>
    <version>0.1.0</version>
</dependency>
```

### Supported Platforms
Maven Central artifacts contain native libraries that can be loaded on the following platforms:

| Operating System | Architecture |
| ---------------- |--------------|
| Linux            | x86_64       |
| Mac OS X         | x86_64       |
| Windows          | x86_64       |

The native libraries were build with WebRTC branch M79.

### Demo

The client demo implements the [AppRTC](https://github.com/webrtc/apprtc) signaling protocol which allows the JavaFX demo application to communicate with the reference [AppRTC video chat app](https://appr.tc) in a browser.

### Build Notes

In order to build the native code, be sure to install the [prerequisite software](https://webrtc.googlesource.com/src/+/refs/heads/master/docs/native-code/development/prerequisite-sw/index.md).

Assuming you have all the prerequisites installed for your OS, run:

```
mvn install
```

On the first run, the WebRTC source tree will be loaded into the `/<user home>/webrtc` directory. This will take a while and require about 8 GB of disk space.

#### Build Parameters

| Parameter     | Description                                       | Default Value           |
| ------------- | ------------------------------------------------- | ----------------------- |
| webrtc.src    | Absolute checkout path for the WebRTC source tree | /\<user home\>/webrtc   |
| webrtc.branch | The WebRTC branch to checkout                     | branch-heads/3987 (M80) |
