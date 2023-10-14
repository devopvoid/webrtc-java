## [0.8.0]() (2023-10-14)

### Enhancement

- Link libm from sysroot to increase GLIBC (<= 2.18) compatibility #108
- Initialized configs to be used with GraalVM
- Added setFocusSelectedSource to DesktopCapturer and capture with DesktopAndCursorComposer

### Demo
- Dropped demo code. New demo will be in a new repository.

## [0.7.0]() (2022-11-14)

### API changes

- Set native branch to 4844 (Milestone 99)

### Enhancement

- Compile release with backward compatibility for Java 8
- Added setFocusSelectedSource to VideoTrackDesktopSource
- Set maximum width and height for captured screen frames
- DesktopCaptureCallback converts DesktopFrame to I420
- VideoTrackDesktopSource crops black window borders on Windows
- Added MediaStreamTrack "ended" and "muted" event observation

### Fixed bugs

- Fixed desktop capturing
- Fixed setting codec preferences
- Fixed setting sender parameters
- Fixed memory leak in AudioTransportSink
- Fixed memory leak with audio & video frames related to JNI local references
- Fixed audio device names on mac with Cyrillic and other characters

### Demo
- Removed demos since AppRTC services have been turned down
- A new demo app will follow soon

## [0.6.0]() (2022-01-17)

### API changes

- Moved to WebRTC branch 4692 (M97) ([29a6e3b](https://github.com/devopvoid/webrtc-java/commit/29a6e3b))

### Enhancement

- Lazy-load audio/video managers #44 ([674b6af](https://github.com/devopvoid/webrtc-java/commit/674b6af))
- Get default audio devices #50 ([a630f28](https://github.com/devopvoid/webrtc-java/commit/a630f28))

### Fixed bugs

- Fixed AudioProcessing native ProcessReverseStream function call #46 ([e912543](https://github.com/devopvoid/webrtc-java/commit/e912543))
- Fixed loading classes from native threads with detached class-loaders #49 ([e50249b](https://github.com/devopvoid/webrtc-java/commit/e50249b))
-  Fixed camera device selection for a video track on Unix systems #30 ([8b0fb4f](https://github.com/devopvoid/webrtc-java/commit/8b0fb4f))

## [0.5.0]() (2021-11-25)

### Enhancement

- Improved audio processing by basic channel up-mixing to avoid additional audio converter ([e665a4a](https://github.com/devopvoid/webrtc-java/commit/e665a4a))
- AudioConverter implementation for remixing and resampling ([3182d06](https://github.com/devopvoid/webrtc-java/commit/3182d06))
- Updated RTCStats.cpp to Java mapping ([76769e2](https://github.com/devopvoid/webrtc-java/commit/76769e2))
- Use new api calls which support RTCError ([e665a4a](https://github.com/devopvoid/webrtc-java/commit/e665a4a))

### Fixed bugs

- Fixed CoInitialize has not been called #43 ([73df466](https://github.com/devopvoid/webrtc-java/commit/73df466))
- Fixed NPE on RTCPeerConnection.getConnectionState when peer-connectio n has been closed #35 ([e075d60](https://github.com/devopvoid/webrtc-java/commit/e075d60))
- Fixed NPE in AudioProcessingConfig when no NS level was set ([81164ef](https://github.com/devopvoid/webrtc-java/commit/81164ef))
- Fixed AudioTransportSource buffer reading ([071cbe9](https://github.com/devopvoid/webrtc-java/commit/071cbe9))
- Fixed basic channel up-mixing in AudioProcessing ([4717db1](https://github.com/devopvoid/webrtc-java/commit/4717db1))

## [0.4.0]() (2021-11-21)

### API changes

- Switch to WebRTC branch M95/4638 ([9173836](https://github.com/devopvoid/webrtc-java/commit/9173836))
- Audio/VideoSource renamed to Audio/VideoTrackSource ([68a61b9](https://github.com/devopvoid/webrtc-java/commit/68a61b9))

### Enhancement

- AudioDeviceModule: added stop/start methods for playout/recording ([7070622](https://github.com/devopvoid/webrtc-java/commit/7070622))
- AudioOptions: added more audio processing options ([3faa4a8](https://github.com/devopvoid/webrtc-java/commit/3faa4a8))
- AudioProcessing implementation ([6383a11](https://github.com/devopvoid/webrtc-java/commit/6383a11))
-  Introduced AudioSink and AudioSource interfaces which can be passed to the AudioDeviceModule ([8b33c7c](https://github.com/devopvoid/webrtc-java/commit/8b33c7c))
-  AudioPlayer and AudioRecorder utility classes which utilize the AudioDeviceModule ([caca82c](https://github.com/devopvoid/webrtc-java/commit/caca82c))
-  AudioResampler utility class which can be used for sampling rate conversion ([9e31c27](https://github.com/devopvoid/webrtc-java/commit/9e31c27))
- Add option to provide AudioProcessing to the PeerConnectionFactory ([0c51a2d](https://github.com/devopvoid/webrtc-java/commit/0c51a2d))

### Fixed bugs

- Fixed workflow on Linux machines ([ac2aa58](https://github.com/devopvoid/webrtc-java/commit/ac2aa58))
- Fixed action workflow saving and loading cache ([9528c2d](https://github.com/devopvoid/webrtc-java/commit/9528c2d))
- Fixed GLIBCXX_3.4.26 not found ([da18d11](https://github.com/devopvoid/webrtc-java/commit/da18d11))
- Fixed JavaFX demo crash ([89896fd](https://github.com/devopvoid/webrtc-java/commit/89896fd))

## [0.3.0]() (2021-08-19)

### API changes

- Switch to WebRTC branch M92/4515 ([bb3bbfe](https://github.com/devopvoid/webrtc-java/commit/bb3bbfe))

### Enhancement

- Audio/video device (Windows) handling consistent with the internal API ([702e58c](https://github.com/devopvoid/webrtc-java/commit/702e58c))
- Add usage of audio layers and with it the possibility to use dummy audio for testing purposes ([daec7d8](https://github.com/devopvoid/webrtc-java/commit/daec7d8)) **Thanks to Rafael Berne (@rmberne)**
- Add VideoCapture class to capture frames without a running track ([eec4cfd](https://github.com/devopvoid/webrtc-java/commit/eec4cfd))
- MediaDevice change listener implementation ([1029fe4](https://github.com/devopvoid/webrtc-java/commit/1029fe4))
- Build with H264 codec ([f09aba0](https://github.com/devopvoid/webrtc-java/commit/f09aba0))

### Fixed bugs

- Fixed AudioModule NPE in PeerConnectionFactory ([c9fc55e](https://github.com/devopvoid/webrtc-java/commit/c9fc55e))
- Fixed NativeI420Buffer allocate ([9bf8169](https://github.com/devopvoid/webrtc-java/commit/9bf8169))
- Fixed desktop capture ([0314dec](https://github.com/devopvoid/webrtc-java/commit/0314dec))
- Fixed demo audio/video config settings ([86bd776](https://github.com/devopvoid/webrtc-java/commit/86bd776))
- Fixed possible arithmetic overflow ([b6d4aec](https://github.com/devopvoid/webrtc-java/commit/b6d4aec))

### Project

- Removed Azure Pipelines CI/CD configurations ([824aaf1](https://github.com/devopvoid/webrtc-java/commit/824aaf1))

### Refactoring

- Removed usage of webrtc::VideoType::kNV12 ([32a8ff9](https://github.com/devopvoid/webrtc-java/commit/32a8ff9))

## [0.2.0]() (2020-02-25)

### API changes

- Switch to WebRTC branch M80/3987 ([5a7bba5](https://github.com/devopvoid/webrtc-java/commit/5a7bba5))

### Enhancement

- MediaDevices: device and capabilities retrieval ([2f92e11](https://github.com/devopvoid/webrtc-java/commit/2f92e11))

### Features

- AppRTC signaling implementation ([4bda5bc](https://github.com/devopvoid/webrtc-java/commit/4bda5bc))
- AppRTC demo integration ([f4f2636](https://github.com/devopvoid/webrtc-java/commit/f4f2636))

### Fixed bugs

- Java module fixes ([52b9a44](https://github.com/devopvoid/webrtc-java/commit/52b9a44))
- Fixed module tests ([483d0f8](https://github.com/devopvoid/webrtc-java/commit/483d0f8))

### Project

- Update issue templates ([ecc3eb5](https://github.com/devopvoid/webrtc-java/commit/ecc3eb5))
- GitHub Actions CI/CD configurations ([967b6d9](https://github.com/devopvoid/webrtc-java/commit/967b6d9))
- Azure Pipelines CI/CD configurations ([5e8f90c](https://github.com/devopvoid/webrtc-java/commit/5e8f90c))

### Refactoring

- RTP header extension refactoring ([be2c4c9](https://github.com/devopvoid/webrtc-java/commit/be2c4c9))
- Refactor bloated project structure ([ff4da53](https://github.com/devopvoid/webrtc-java/commit/ff4da53))

## [0.1.0](https://github.com/devopvoid/webrtc-java/tree/v0.1.0) (2019-12-22)

### Project

- Initial commit ([178b458](https://github.com/devopvoid/webrtc-java/commit/178b458))
