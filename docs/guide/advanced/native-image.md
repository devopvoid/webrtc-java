# GraalVM Native Image

webrtc-java can be compiled into a [GraalVM native image](https://www.graalvm.org/latest/reference-manual/native-image/): one executable that starts in milliseconds and runs without an installed Java runtime. The jars bring the metadata the image needs, so an application needs no configuration of its own for webrtc-java.

## What the Jars Bring

A native image knows only the classes, members and resources it was told about at build time. `webrtc-java` and `webrtc-java-media` carry that metadata under `META-INF/native-image/dev.onvoid.webrtc/`, where `native-image` finds it on its own:

- **JNI.** The native library calls into Java from WebRTC's own threads: observers, sinks, the log sink, the classes it builds results from. Every class it uses is registered with all its members, the JDK classes with the members it calls.
- **Reflection.** The native library finds its classes through a class loader, and the Java code asks a few questions by reflection, such as whether an audio sink takes RTP packets.
- **Resources.** The native libraries of the native JARs, so that the image carries them.

The metadata is in the format GraalVM 21 and later read. It is tested with GraalVM 25 on Linux x86_64, also with `--exact-reachability-metadata`, under which anything missing fails at once.

## Building an Image

Add the [Native Build Tools](https://graalvm.github.io/native-build-tools/latest/maven-plugin.html) plugin, and run `mvn -Pnative package` with a GraalVM as `JAVA_HOME`:

```xml
<profile>
    <id>native</id>
    <build>
        <plugins>
            <plugin>
                <groupId>org.graalvm.buildtools</groupId>
                <artifactId>native-maven-plugin</artifactId>
                <version>1.1.17</version>
                <extensions>true</extensions>
                <executions>
                    <execution>
                        <id>build-native</id>
                        <goals>
                            <goal>compile-no-fork</goal>
                        </goals>
                        <phase>package</phase>
                    </execution>
                </executions>
                <configuration>
                    <mainClass>com.example.App</mainClass>
                    <buildArgs>
                        <buildArg>--enable-native-access=ALL-UNNAMED</buildArg>
                    </buildArgs>
                </configuration>
            </plugin>
        </plugins>
    </build>
</profile>
```

`System.load` is a restricted method from JDK 24 on. Without `--enable-native-access`, an image built with GraalVM 24 or later prints a warning each time it loads the library. The option is the application's to give, and GraalVM 21 does not know it.

With Gradle, the [Gradle plugin](https://graalvm.github.io/native-build-tools/latest/gradle-plugin.html) of the Native Build Tools does the same.

## Where the Native Library Is Loaded From

The [loading order](/guide/get-started#where-the-native-library-is-loaded-from) is the same as on the JVM, which gives an image two ways to get the library:

**Inside the image.** With the native JAR on the class path at build time, as Maven puts it there, the image carries the library. The image writes the library to the temporary directory when it starts, and loads it from there, as the JVM does from the JAR.

The executable is then all there is to ship. Set `webrtc.java.extract.dir` when the temporary directory does not allow running code:

```shell
./app -Dwebrtc.java.extract.dir=/var/lib/app/natives
```

**Next to the image.** Leave the native JAR off the class path at build time, and put the library next to the executable under its name in the native JAR, `libwebrtc-java-linux-x86_64.so` for example. With Maven, exclude the native JAR that `webrtc-java` brings along:

```xml
<dependency>
    <groupId>dev.onvoid.webrtc</groupId>
    <artifactId>webrtc-java</artifactId>
    <version>{{ VERSION }}</version>
    <exclusions>
        <!-- The native JAR, which has the same coordinates and a platform classifier. -->
        <exclusion>
            <groupId>dev.onvoid.webrtc</groupId>
            <artifactId>webrtc-java</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```
 
In a native image `System.loadLibrary` looks in the directory of the executable, so nothing has to be set. The executable gets smaller by the size of the library, and nothing is written to disk at start. A different directory is named with `webrtc.java.library.dir`, as on the JVM.

The media module works the same way: its library and the FFmpeg libraries are carried inside the image, or go next to it under their names in its native JAR, `libavcodec.so.62` and so on.

An image is built for one platform, so it carries the library of the platform it was built on. Build it on each platform it is meant for.

## Audio Sinks with RTP Packets

An `AudioTrackSink` may implement the `onData` method that also receives [the RTP packets](/guide/audio/headless-audio#who-sent-the-received-audio) the audio was assembled from. Whether a sink does is found out by reflection, which a native image answers only for registered classes.

For any other sink, an anonymous class or a lambda among them, the packets are collected. A sink that implements the method gets them, and one that does not gets its audio without them, as on the JVM. To spare the collecting, register the sink class for the query:

```json
[
  { "name": "com.example.LevelMeter", "queryAllPublicMethods": true }
]
```

in `src/main/resources/META-INF/native-image/<group>/<artifact>/reflect-config.json` of the application.

## Not Covered

- **Swing and JavaFX.** The video views of `webrtc-java-swing` and `webrtc-java-javafx` have no metadata of their own. AWT and Swing need GraalVM's own AWT support, JavaFX the [Gluon](https://gluonhq.com/products/substrate/) toolchain.
- **Classes of the application** that it hands to webrtc-java are covered as far as webrtc-java uses them: a callback is called through its interface, which is registered. What the application itself does by reflection is the application's to register, for example with the [tracing agent](https://www.graalvm.org/latest/reference-manual/native-image/metadata/AutomaticMetadataCollection/).

## Example

The [`NativeImageExample`](https://github.com/devopvoid/webrtc-java/blob/main/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/NativeImageExample.java) connects two peers in one process and checks that video, audio, data channel messages, statistics and log messages arrive. It exits with status 0 only if they all did, and builds with `--exact-reachability-metadata`, which needs GraalVM 24 or later:

```shell
mvn -pl webrtc-examples -Pnative package
webrtc-examples/target/webrtc-native-image-example
```
