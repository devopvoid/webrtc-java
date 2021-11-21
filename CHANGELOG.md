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
