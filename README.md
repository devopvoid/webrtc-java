[![Build Status](https://img.shields.io/github/actions/workflow/status/devopvoid/webrtc-java/build.yml?label=Build&logo=github)](https://github.com/devopvoid/webrtc-java/actions)
[![Maven Central](https://img.shields.io/maven-central/v/dev.onvoid.webrtc/webrtc-java?label=Maven%20Central&logo=apache-maven)](https://search.maven.org/artifact/dev.onvoid.webrtc/webrtc-java)

## webrtc-java

Java native interface implementation based on the free, open [WebRTC](https://webrtc.org) project. The goal of this project is to enable development of RTC applications for desktop platforms running Java. This project wraps the [WebRTC Native API](https://webrtc.github.io/webrtc-org/native-code/native-apis) and is similar to the [JS API](https://w3c.github.io/webrtc-pc).

### Maven

```xml
<dependency>
	<groupId>dev.onvoid.webrtc</groupId>
	<artifactId>webrtc-java</artifactId>
	<version>0.11.0</version>
</dependency>
```

### Gradle

```groovy
implementation "dev.onvoid.webrtc:webrtc-java:0.11.0"
implementation group: "dev.onvoid.webrtc", name: "webrtc-java", version: "0.11.0", classifier: "windows-x86_64"
implementation group: "dev.onvoid.webrtc", name: "webrtc-java", version: "0.11.0", classifier: "macos-x86_64"
implementation group: "dev.onvoid.webrtc", name: "webrtc-java", version: "0.11.0", classifier: "macos-aarch64"
implementation group: "dev.onvoid.webrtc", name: "webrtc-java", version: "0.11.0", classifier: "linux-x86_64"
implementation group: "dev.onvoid.webrtc", name: "webrtc-java", version: "0.11.0", classifier: "linux-aarch64"
implementation group: "dev.onvoid.webrtc", name: "webrtc-java", version: "0.11.0", classifier: "linux-aarch32"
```

### Supported Platforms
Maven Central artifacts contain native libraries that can be loaded on the following platforms:

<table>
  <tr>
    <td></td>
    <td>x64</td>
    <td>arm</td>
    <td>arm64</td>
  </tr>
  <tr align="center">
    <th>Linux</th>
    <td>✔</td>
    <td>✔ armeabi-v7a</td>
    <td>✔ arm64-v8a</td>
  </tr>
  <tr align="center">
    <th>macOS</th>
    <td>✔</td>
    <td>-</td>
    <td>✔</td>
  </tr>
  <tr align="center">
    <th>Windows</th>
    <td>✔</td>
    <td>-</td>
    <td>-</td>
  </tr>
</table>

The native libraries were built using the m134 (6998) WebRTC branch as the stable release, dated Tuesday, March 4, 2025.
### Build Notes

To build the native code, be sure to install the prerequisite software (follow the links):

**Note**: You don't have to install the Depot Tools, the build script will do that for you.

<table>
  <tr>
    <td>Linux</td>
    <td><a href="https://chromium.googlesource.com/chromium/src/+/master/docs/linux/build_instructions.md#system-requirements">Ubuntu</a>, <a href="https://chromium.googlesource.com/chromium/src/+/master/docs/linux/build_instructions.md#Notes-for-other-distros">other distros</a></td>
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

On the first run, the WebRTC source tree will be loaded into the `/<user home>/webrtc` directory. This will take a while and require about 20 GB of disk space.

#### Build Parameters

| Parameter          | Description                                            | Default Value               |
| ------------------ | ------------------------------------------------------ |-----------------------------|
| webrtc.branch      | The WebRTC branch to checkout.                         | branch-heads/6998           |
| webrtc.src.dir     | The absolute checkout path for the WebRTC source tree. | /\<user_home\>/webrtc       |
| webrtc.install.dir | The install path for the compiled WebRTC library. Is also used to link against a pre-compiled WebRTC library to reduce build time. | /\<user_home\>/webrtc/build |
