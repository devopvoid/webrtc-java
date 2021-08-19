
## [0.3.0]() (2021-08-19)

### API changes

- Switch to WebRTC branch M92/4515 ([bb3bbfe](https://github.com/devopvoid/webrtc-java/commit/bb3bbfe))

### Enhancement

- Audio/video device (Windows) handling consistent with the internal API ([702e58c](https://github.com/devopvoid/webrtc-java/commit/702e58c))
- Add usage of audio layers and with it the possibility to use dummy audio for testing purposes ([daec7d8](https://github.com/devopvoid/webrtc-java/commit/daec7d8)) **Thanks to Rafael Berne (@rmberne)**
- Add VideoCapture class to capture frames without a running track ([eec4cfd](https://github.com/devopvoid/webrtc-java/commit/eec4cfd))
- MediaDevice change listener implementation ([1029fe4](https://github.com/devopvoid/webrtc-java/commit/1029fe4))

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
