## webrtc-java

Java native interface implementation based on the free, open [WebRTC](https://webrtc.org) project. The goal of this project is to enable development of RTC applications for desktop platforms running Java.

```xml
<dependency>
    <groupId>dev.onvoid.webrtc</groupId>
    <artifactId>webrtc-java</artifactId>
    <version>0.1.0</version>
</dependency>
```

### Supported Platforms
Maven Central artifacts contain native libraries that can be loaded on the following platforms:

| Operating System | Architecture | Artifact ID                |
| ---------------- |--------------|----------------------------|
| Linux            | x86_64       | webrtc-java-linux-x86_64   |
| Mac OS X         | x86_64       | webrtc-java-macos-x86_64   |
| Windows          | x86_64       | webrtc-java-windows-x86_64 |

### Build Notes

In order to build the native code, be sure to install the [prerequisite software](https://webrtc.org/native-code/development/prerequisite-sw).

Assuming you have all the prerequisites installed for your OS, run:

```
mvn install
```

On the first run, the WebRTC source tree will be loaded into the `webrtc-jni/dependencies/webrtc` directory. This will take a while and require about 8 GB of disk space.
