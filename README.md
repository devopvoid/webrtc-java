[![](https://github.com/devopvoid/webrtc-java/workflows/Maven%20CI/badge.svg)](https://github.com/devopvoid/webrtc-java/actions)
[![](https://img.shields.io/maven-central/v/dev.onvoid.webrtc/webrtc-java.svg?label=Maven%20Central&logo=apache-maven)](https://search.maven.org/search?q=g:%22dev.onvoid.webrtc%22%20AND%20a:%22webrtc-java%22)

## webrtc-java

Java native interface implementation based on the free, open [WebRTC](https://webrtc.org) project. The goal of this project is to enable development of RTC applications for desktop platforms running Java. This project wraps the WebRTC Native API and is similar to the [JS API](https://w3c.github.io/webrtc-pc).

```xml
<dependency>
    <groupId>dev.onvoid.webrtc</groupId>
    <artifactId>webrtc-java</artifactId>
    <version>0.2.0</version>
</dependency>
```

### Supported Platforms
Maven Central artifacts contain native libraries that can be loaded on the following platforms:

<table>
  <tr>
    <td>Linux</td>
    <td>x86_64</td>
    <td><a href="https://github.com/devopvoid/webrtc-java/releases/download/v0.2.0/webrtc-javafx-demo-0.2.0-linux-x86_64.jar">demo app</a></td>
  </tr>
  <tr>
    <td>macOS</td>
    <td>x86_64</td>
    <td><a href="https://github.com/devopvoid/webrtc-java/releases/download/v0.2.0/webrtc-javafx-demo-0.2.0-macos-x86_64.jar">demo app</a></td>
  </tr>
  <tr>
    <td>Windows</td>
    <td>x86_64</td>
    <td><a href="https://github.com/devopvoid/webrtc-java/releases/download/v0.2.0/webrtc-javafx-demo-0.2.0-windows-x86_64.jar">demo app</a></td>
  </tr>
</table>

The native libraries were build with WebRTC branch M80.

### Demo

The client demo implements the [AppRTC](https://github.com/webrtc/apprtc) signaling protocol which allows the JavaFX demo application to communicate with the reference [AppRTC video chat app](https://appr.tc) in a browser.

### Build Notes

In order to build the native code, be sure to install the prerequisite software (follow the links):

<table>
  <tr>
    <td>Linux</td>
    <td><a href="https://webrtc.googlesource.com/src/+/refs/heads/master/docs/native-code/development/prerequisite-sw/index.md#linux-ubuntu_debian">Debian & Ubuntu</a>, <a href="https://chromium.googlesource.com/chromium/src/+/master/docs/linux/build_instructions.md#notes">other distros</a></td>
  </tr>
  <tr>
    <td>macOS</td>
    <td>Xcode 9 or higher</td>
  </tr>
  <tr>
    <td>Windows</td>
    <td><a href="https://chromium.googlesource.com/chromium/src/+/master/docs/windows_build_instructions.md#visual-studio">Visual Studio</a></td>
  </tr>
</table>

Assuming you have all the prerequisites installed for your OS, run:

```
mvn install
```

On the first run, the WebRTC source tree will be loaded into the `/<user home>/webrtc` directory. This will take a while and require about 8 GB of disk space.

#### Build Parameters

| Parameter          | Description                                            | Default Value               |
| ------------------ | ------------------------------------------------------ | --------------------------- |
| webrtc.branch      | The WebRTC branch to checkout.                         | branch-heads/3987           |
| webrtc.src.dir     | The absolute checkout path for the WebRTC source tree. | /\<user_home\>/webrtc       |
| webrtc.install.dir | The install path for the compiled WebRTC library. Is also used to link against a pre-compiled WebRTC library to reduce build time. | /\<user_home\>/webrtc/build |
