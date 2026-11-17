# Contributing to webrtc-java

Thank you for your interest in contributing! Bug reports, fixes, new features
and improvements to the documentation are all welcome.

## Reporting Bugs and Requesting Features

- Search the [existing issues](https://github.com/devopvoid/webrtc-java/issues)
  first. Your problem may already be known or solved.
- Open a new issue with the **Bug report** or **Feature request** template.
- For a bug, include the webrtc-java version, your operating system, CPU
  architecture and Java version, the steps to reproduce it and, if possible,
  a minimal code example and the log output.

**Do not report security vulnerabilities in public issues.** Follow the
[security policy](SECURITY.md) instead.

## Before You Start

For larger changes, such as a new feature or a change to the public API,
please open an issue first and describe what you plan to do. That way we can
agree on the approach before you put work into it.

Small fixes, such as typos, documentation improvements or obvious bugs, can go
straight to a pull request.

## Development Setup

webrtc-java builds three native libraries from source: Google's WebRTC, the
JNI library around it, and FFmpeg with dav1d for the `webrtc-java-media`
module. You need, among other things:

- JDK 17 or newer (the libraries target Java 11)
- Maven 3.9.6 or newer
- CMake, Git, Python 3, Meson and Ninja
- About 15 GB of disk space for the WebRTC checkout

The platform-specific prerequisites (Linux, macOS, Windows) are described in
the [build notes](docs/guide/build.md). The CI setup in
`.github/actions/prepare-<os>/action.yml` is the reference when in doubt.

Clone the repository with its submodules and build it:

```shell
git clone https://github.com/devopvoid/webrtc-java.git
cd webrtc-java
git submodule update --init --depth 1 webrtc-media/third-party/ffmpeg webrtc-media/third-party/dav1d
mvn install
```

The first build downloads and compiles WebRTC, which takes a long time. Later
builds reuse it as long as the configuration is unchanged. To build without
running the tests, use `mvn package -DskipTests`.

## Project Structure

| Module            | Content                                                        |
|-------------------|----------------------------------------------------------------|
| `webrtc`          | The Java API (`webrtc-java`)                                   |
| `webrtc-jni`      | The JNI layer in C++ and the native WebRTC build               |
| `webrtc-media`    | Media playback and recording with FFmpeg (`webrtc-java-media`) |
| `webrtc-swing`    | Video views for Swing (`webrtc-java-swing`)                    |
| `webrtc-javafx`   | Video views for JavaFX (`webrtc-java-javafx`)                  |
| `webrtc-examples` | Example applications                                           |
| `docs`            | The documentation website [jrtc.dev](https://jrtc.dev)         |

## Making Changes

1. Fork the repository and create a branch from `main`.
2. Make your change and keep it focused on one topic. Unrelated changes belong
   in a separate pull request.
3. Follow the style of the surrounding code:
   - Indent Java, C++ and XML with tabs.
   - Add the Apache License 2.0 header to new source files.
   - Document public Java API with Javadoc.
4. Add or update tests for your change.
5. Update the documentation in `docs/guide` if you change behavior or add a
   feature.

### Tests

Run the tests of the module you changed, for example:

```shell
mvn test -pl webrtc
```

If you change JNI code (`webrtc-jni/src/main/cpp`), also run the tests with
`-Xcheck:jni`, as CI does. It detects invalid JNI references and unchecked
exceptions:

```shell
mvn test -pl webrtc -Pjni-check
```

CI fails on a single JNI warning.

### Documentation

The documentation is built with [VitePress](https://vitepress.dev). To preview
it locally:

```shell
cd docs
npm install
npm run dev
```

## Commit Messages

Commit messages follow [Conventional Commits](https://www.conventionalcommits.org).
The changelog is generated from them, so the type decides where a change
appears in the release notes:

| Type       | Used for                                  |
|------------|-------------------------------------------|
| `feat`     | A new feature                             |
| `fix`      | A bug fix                                 |
| `docs`     | Documentation only                        |
| `perf`     | A performance improvement                 |
| `refactor` | A code change that adds no feature and fixes no bug |
| `test`     | Adding or correcting tests                |
| `chore`    | Build, dependencies and other maintenance |
| `ci`       | CI workflows                              |

Write the description in the imperative and lowercase, for example:

```text
fix: survive the removal of the default PulseAudio device
feat: add file transfer over data channels
```

## Pull Requests

- Open the pull request against `main`.
- Describe what the change does and why, and link the related issue
  (`Fixes #123`).
- Make sure the build passes. CI builds and tests every supported platform
  (Windows, macOS and Linux on x64 and arm64, Linux on arm32). Changes that
  only touch the documentation are checked by the docs build instead.
- Be prepared for review comments. We may ask for changes before merging.

## License

By contributing, you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE), the license of this project.
