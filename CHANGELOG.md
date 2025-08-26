# Changelog

All notable changes to this project will be documented in this file.

## [0.12.0] - 2025-08-26

### Features
- Add changelog generation and release publishing workflow by @hoary
- Restrict ICE port ranges and control candidate gathering behavior by @devopvoid
- Push or pull audio frames with a headless audio device module by @devopvoid
- Add guide for configuring send-only and receive-only media by @devopvoid
- Add the platform classifier to the native library filename by @SnirDecartAI
- Add VideoBufferConverter.convertToI420 by @SnirDecartAI
- Add SyncClock for audio and video synchronization support by @devopvoid
- Add CustomVideoSource and related JNI bindings for video frame handling by @devopvoid
- Added a new web-based example by @devopvoid
- Custom audio source that can be provided to audio tracks by @devopvoid
- Add H.264 support and improve linking options for Apple platforms by @devopvoid
- Add RTCDtmfSender implementation for sending DTMF tones #115 by @devopvoid
- Enable H.264 support for Linux and Windows builds #155 #168 #169 by @devopvoid
- Add copy method for NativeI420Buffer and VideoFrame by @devopvoid
- Added the missing macOS camera capture functionality by @devopvoid

### Bug Fixes
- Update actions/checkout to v5 and set ref to main by @hoary
- Update actions/checkout version to v5 and ensure fetch-depth is set by @hoary
- Update changelog action version and template syntax by @hoary
- Correct template variable syntax in changelog configuration by @hoary
- RTCDtmfSenderTests timeouts by @devopvoid
- Update error handling to stop capturing on permanent errors #186 by @devopvoid
- Ensure JNIEnv is properly attached before deleting global reference 146 by @devopvoid
- Return a valid NtpTime from SyncClock::GetNtpTime by @SnirDecartAI
- Release video frame after processing in WHEP example #191 by @devopvoid
- Made RTCRtpCodecCapability's int fields optional by @SnirDecartAI
- Update references from master to main in build configuration and documentation by @devopvoid
- Enhance documentation for audio related topics by @devopvoid
- Validate COM initialization in audio device management #181 by @devopvoid
- MacOS screen capturing with iOSurface and platform thread management by @devopvoid
- Add Maven release action for macOS platforms by @devopvoid

### Refactor
- Simplify changelog generation workflow and update action usage by @hoary
- Updated native interface to m138 by @devopvoid

### Documentation
- Update changelog configuration to include fromTag by @hoary
- Update changelog configuration to include detailed categories by @hoary
- Add changelog workflow configuration by @hoary
- Add AudioRecorder and AudioPlayer documentation by @devopvoid
- Documentation for VideoBufferConverter by @devopvoid
- Documentation for MediaStreamTrack, VideoBufferConverter, and VideoCapture by @devopvoid
- Add Video Capture guide by @devopvoid
- Add Screen Capturer and Window Capturer guides by @devopvoid
- Add Power Management utility by @devopvoid
- Add Audio Converter guide by @devopvoid
- Add Voice Activity Detector guide by @devopvoid
- Reorganize media guides into categories by @devopvoid
- Add section on disabling audio processing with dummy audio layer by @devopvoid
- Add Custom Video Source guide and update documentation by @devopvoid
- Update example descriptions and add WebClientExample by @devopvoid
- Add Custom Audio Source guide by @devopvoid
- Update asset paths and add a privacy policy by @devopvoid
- Add camera capture guide by @devopvoid
- Update CHANGELOG.md by @devopvoid
- Update README with logo by @devopvoid
- Improve responsive design for the cover by @devopvoid
- Enhance coverpage and sidebar styles with new logo and theme colors by @devopvoid
- Update homepage and quickstart guide links in documentation by @devopvoid
- Comprehensive guide and examples by @devopvoid
- Update badge links in README for build status and Maven Central by @devopvoid
- Update README for version 0.11.0 and clarify build notes by @devopvoid

### Miscellaneous Tasks
- Update CHANGELOG.md for v0.12.0 by @github-actions[bot]
- Add custom video source and generator to the web example by @devopvoid
- Update readme to 0.12.0 and update changelog by @devopvoid

### Other
- Update all GitHub Actions setup-java to v5 by @maxkratz
- Prepare for next development iteration by @devopvoid
- Prepare release v0.13.0 by @devopvoid
- Update release workflow to update versions.js in docs by @devopvoid
- Create CNAME by @devopvoid
- Prepare for next development iteration by @devopvoid
- Prepare release v0.12.0 by @devopvoid
- Fix release workflow to support matrix.platform.name by @devopvoid
- Update package installation commands and enhance sysroot configuration by @devopvoid
- Prepare for next development iteration by @devopvoid

## New Contributors
* @hoary made their first contribution
* @github-actions[bot] made their first contribution
* @maxkratz made their first contribution
* @SnirDecartAI made their first contribution

## [0.11.0] - 2025-07-02

### Bug Fixes
- Windows clang profile activation by @devopvoid

### Refactor
- VideoTrackDesktopSource extends AdaptedVideoTrackSource by @devopvoid
- Rtc and cricket to webrtc namespace by @devopvoid

### Other
- Prepare release v0.11.0 by @devopvoid
- Add project name to pom.xml by @devopvoid
- Update release workflow to include code checkout and linux arm support by @devopvoid
- Link with libc++ and libc++abi on Linux and Windows by @devopvoid
- Do not build with clang-cl on main by @devopvoid
- Fix screen-share memory leak by @devopvoid
- Revert "refactor: rtc and cricket to webrtc namespace" by @devopvoid
- Revert "build: updated webrtc version to m137 in GitHub workflows" by @devopvoid
- Updated webrtc version to m137 in GitHub workflows by @devopvoid
- Updated project to the stable WebRTC branch 6998 by @devopvoid
- Build.yml pipewire activated by @devopvoid
- Update README.md by @devopvoid
- Prepare for next development iteration by @devopvoid


## [0.10.0] - 2025-03-18

### Bug Fixes
- Added missing libx libraries to be installed in linux release workflow by @devopvoid
- Added missing libx libraries to be installed in GH actions by @devopvoid
- Crashes caused by video frame conversion by @devopvoid
- Linux crashes caused by unlinked libraries by @devopvoid

### Other
- Prepare release v0.10.0 by @devopvoid
- Update README.md by @devopvoid
- Prepare for next development iteration by @devopvoid


## [0.9.0] - 2025-01-02

### Other
- Prepare release v0.9.0 by @devopvoid
- Crash after java callback if exception occurred by @arosov
- Workflow build and release fixes by @devopvoid
- Removed gpg from action workflow by @devopvoid
- Fixed action workflow deployment by @devopvoid
- Update action.yml by @devopvoid
- Updated build badge in README.md by @devopvoid
- Linux pm rtc logging by @devopvoid
- Fixed build workflow by @devopvoid
- Fixed and improved GitHub build workflow by @devopvoid
- Screen inhibition by @devopvoid
- Fixed crash with jdk version > 17 by @devopvoid
- Revert version of cmake-maven-plugin for compatibility with older maven by @devopvoid
- Updated maven plugin versions. by @devopvoid
- Fixed NPE in MediaDevices when no audio device is connected. by @devopvoid
- Update README.md with Linux ARM architectures by @devopvoid
- Added Linux ARM architectures for cross compilation by @devopvoid
- Fixed Apple Silicon build by @devopvoid
- Update README.md macOS arm64 release by @devopvoid
- Updated CHANGELOG and README by @devopvoid
- Prepare for next development iteration by @devopvoid

## New Contributors
* @arosov made their first contribution

## [0.8.0] - 2023-10-14

### Other
- Prepare release v0.8.0 by @devopvoid
- Updated action runners by @devopvoid
- Dropped old demo code. New demo will be in a new repository by @devopvoid
- Added setFocusSelectedSource to DesktopCapturer and capture with DesktopAndCursorComposer by @devopvoid
- Add a build support for mac/aarch64 by @stevebriskin
- Merge remote-tracking branch 'origin/master' by @devopvoid
- Update README.md by @doctorpangloss
- Initialized configs to be used with GraalVM by @devopvoid
- Set linux version to ubuntu-20.04 due to audio system tests by @devopvoid
- Link libm from sysroot to increase GLIBC (<= 2.18) compatibility #108 by @devopvoid
- Updated CHANGELOG and README by @devopvoid
- Added workflow_dispatch to the release workflow by @devopvoid
- Removed demo tasks from the release workflow by @devopvoid
- Prepare for next development iteration by @devopvoid

## New Contributors
* @stevebriskin made their first contribution
* @doctorpangloss made their first contribution

## [0.7.0] - 2022-11-14

### Bug Fixes
- Fix memory leak by @kalgecin

### Other
- Prepare release v0.7.0 by @devopvoid
- Fixed audio device names on mac #28 by @devopvoid
- Compile with backward compatibility for Java 8 by @devopvoid
- Terminate (end) VideoTrackDesktopSource if source cannot be selected by @devopvoid
- Fixed RTCRtpCodecParameters conversion when setting sender params by @devopvoid
- Fixed RTCRtpCodecParameters conversion by @devopvoid
- Notify the track to end on permanent error when capturing a desktop frame by @devopvoid
- Improved MediaStreamTrack "ended" and "muted" event observation by @devopvoid
- Added MediaStreamTrack "ended" and "muted" event observation by @devopvoid
- Removed Xcode version selection from the build workflow by @devopvoid
- VideoTrackDesktopSource crop black window borders by @devopvoid
- DesktopCaptureCallback convert DesktopFrame to I420 by @devopvoid
- Added setMaxFrameSize to VideoDesktopSource by @devopvoid
- Enable set min,max bitrate and max framerate in RTCRtpSender by @devopvoid
- Fixed crashes caused by DesktopCaptureCallback by @devopvoid
- MacOS build with Xcode 11.7 by @devopvoid
- Use new RemoveTrackOrError API call in RTCPeerConnection to remove a sender by @devopvoid
- Removed deprecated Windows wstring_convert calls by @devopvoid
- Build demo code with Maven 'demo' profile by @devopvoid
- Fixed RTCDataChannelTests by @devopvoid
- Fixed JavaEnums returning JavaLocalRef by @devopvoid
- Fixed JavaFactories returning jobjectArray by @devopvoid
- Fixed JavaLocalRefs for enums and factories by @devopvoid
- CI build with windows-2019 by @devopvoid
- Set maximum width and height for captured screen frames by @devopvoid
- Fixed null pointer handling with RtpSender by @devopvoid
- Added setFocusSelectedSource to VideoTrackDesktopSource by @devopvoid
- Compile with CXX_STANDARD 17 by @devopvoid
- Minor option changes for DesktopCapturer by @devopvoid
- Bump cmake-maven-plugin version to 3.22.1-b1 by @devopvoid
- Fixed memory leak in AudioTransportSink by @devopvoid
- Updated RTC_LOG macros by @devopvoid
- Removed deprecated code by @devopvoid
- Bump native branch to 4844 by @devopvoid
- Removed demos since AppRTC services have been turned down by @devopvoid
- Fixed setting codec preferences #57 by @devopvoid
- Removed setDesktopCapturer call on VideoDesktopSource by @devopvoid
- Fixed desktop capturing by @devopvoid
- Updated CHANGELOG and README by @devopvoid
- Prepare for next development iteration by @devopvoid

## New Contributors
* @kalgecin made their first contribution

## [0.6.0] - 2022-01-17

### Other
- Prepare release v0.6.0 by @devopvoid
- Fixed camera device selection for a video track on Unix systems #30 by @devopvoid
- Get default audio devices by @devopvoid
- Lazy-load audio/video managers #44 by @devopvoid
- Fixed loading classes from native threads with detached class-loaders #49 by @devopvoid
- Moved to WebRTC branch 4692 (M97) by @devopvoid
- Updated workflow actions to WebRTC branch 4664 (M96) by @devopvoid
- Moved to WebRTC branch 4664 (M96) by @devopvoid
- Fixed AudioProcessing native ProcessReverseStream function call #46 by @devopvoid
- Updated CHANGELOG and README by @devopvoid
- Prepare for next development iteration by @devopvoid


## [0.5.0] - 2021-11-25

### Other
- Prepare release v0.5.0 by @devopvoid
- Use new api calls which support RTCError by @devopvoid
- Fixed AudioTransportSource.cpp buffer passing by @devopvoid
- Updated RTCStats.cpp to Java mapping by @devopvoid
- Got rid of warnings in the audio section by @devopvoid
- Fixed NPE on RTCPeerConnection.getConnectionState when peer-connection has been closed #35 by @devopvoid
- AudioConverter implementation for remixing and resampling by @devopvoid
- Minor audio procession improvements by @devopvoid
- Fixed basic channel up-mixing in AudioProcessing by @devopvoid
- Improved audio processing by basic channel up-mixing to avoid additional audio converter by @devopvoid
- Fixed AudioTransportSource buffer reading by @devopvoid
- Fixed NPE in AudioProcessingConfig when no NS level was set by @devopvoid
- Fixed CoInitialize has not been called #43 by @devopvoid
- Updated CHANGELOG and README by @devopvoid
- Prepare for next development iteration by @devopvoid


## [0.4.0] - 2021-11-21

### Other
- Prepare release v0.4.0 by @devopvoid
- Add settings-path for JDK setup to the release workflow by @devopvoid
- Split AudioProcessingConfig into individual config classes. Added get/set of stream-delay to AudioProcessing. by @devopvoid
- Add option to provide AudioProcessing to the PeerConnectionFactory by @devopvoid
- AudioResampler fixes for more than one channel by @devopvoid
- AudioResampler utility class which can be used for sampling rate conversion by @devopvoid
- AudioProcessing to use only 16 Bit audio samples by @devopvoid
- AudioPlayer and AudioRecorder utility classes which utilize the AudioDeviceModule by @devopvoid
- Introduced AudioSink and AudioSource interfaces which can be passed to the AudioDeviceModule by @devopvoid
- Audio/VideoSource renamed to Audio/VideoTrackSource by @devopvoid
- Fixed temporary MTA<>STA threading issue #39 by @devopvoid
- Static linking libgcc and libstdc++ #27 by @devopvoid
- Fixed action workflow cache saving and loading by @devopvoid
- AudioProcessing implementation by @devopvoid
- Native JavaObject field setters by @devopvoid
- Init version of Java implementation of AudioProcessing by @devopvoid
- Workflow 'apt-get update' for linux action by @devopvoid
- Added more audio processing options by @devopvoid
- Moved to WebRTC branch 4638 (M95) by @devopvoid
- Added stop/start methods for playout/recording by @devopvoid
- Updated CHANGELOG and README by @devopvoid
- Updated CHANGELOG and README by @devopvoid
- Prepare for next development iteration by @devopvoid


## [0.3.0] - 2021-08-19

### Other
- Prepare release v0.3.0 by @devopvoid
- Fixed local video is sometimes empty #9 by @devopvoid
- Fixed WebRTC cmake build by @devopvoid
- Fixed NPE in JNI_PeerConnectionFactory by @devopvoid
- Switch to a more stable branch (4515) by @devopvoid
- Update pom.xml by @devopvoid
- Update CMakeLists.txt by @devopvoid
- Update pom.xml by @devopvoid
- Merge remote-tracking branch 'origin/master' by @devopvoid
- Update README.md by @devopvoid
- Audio/video device (Windows) handling consistent with the internal API by @devopvoid
- Audio device retrieval with the AudioDeviceModule by @devopvoid
- Removed Azure Pipelines by @devopvoid
- Merge pull request #20 from rmberne/dummy-audio-for-testing by @devopvoid
- Use dummy audio in the unit test by @rmberne
- Add possibility to use dummy audio for testing purposes by @rmberne
- Fixed NativeI420Buffer allocate by @devopvoid
- Add VideoCapture to capture frames without a running track by @devopvoid
- Removed usage of webrtc::VideoType::kARGB1555 by @devopvoid
- Updated pipelines by @devopvoid
- Removed usage of webrtc::VideoType::kNV12 by @devopvoid
- Updated WebRTC branch to 4472 (M91) and updated dependency versions by @devopvoid
- Updated Sonatype provision URL by @devopvoid
- Fixed desktop capture by @devopvoid
- Bind transceiver direction to the configuration by @devopvoid
- Fixed demo audio/video config settings by @devopvoid
- Revert "Create codeql-analysis.yml" by @devopvoid
- Create codeql-analysis.yml by @devopvoid
- Updated GitHub Actions workflows by @devopvoid
- Update README.md by @devopvoid
- Merge remote-tracking branch 'origin/master' by @devopvoid
- Update README.md by @devopvoid
- Fixed possible arithmetic overflow by @devopvoid
- Linux M88 patch. Updated pipelines. by @devopvoid
- Bump WebRTC branch to 4324 (M88) by @devopvoid
- Merge pull request #6 from averyzhong/master by @devopvoid
- Update AVFVideoDeviceManager.cpp by @averyzhong
- Change GitHub Actions env names for Maven credentials by @devopvoid
- Fixed MF init scope by @devopvoid
- Fixed linux build dependencies in CI builds by @devopvoid
- Fixed macOS CoreAudioDeviceManager by @devopvoid
- Advance WebRTC branch to 4044 (m81) by @devopvoid
- MediaDevice change listener implementation by @devopvoid
- Refactored JNI helper classes by @devopvoid
- Show settings link in the start view by @devopvoid
- Add Maven Central badge to README by @devopvoid
- Reference javafx demo app name to 'webrtc-javafx-demo-(platform)' by @devopvoid
- Prepare for next development iteration by @devopvoid

## New Contributors
* @rmberne made their first contribution
* @averyzhong made their first contribution

## [0.2.0] - 2020-02-25

### Other
- Prepare release v0.2.0 by @devopvoid
- Added CHANGELOG by @devopvoid
- Update README by @devopvoid
- Cleanup Azure build pipeline by @devopvoid
- GitHub Actions CI/CD configurations by @devopvoid
- Merge branch 'master' of github.com:devopvoid/webrtc-java by @devopvoid
- Cleanup GitHub Actions workflow by @devopvoid
- Provide Azure Pipelines CI/CD configurations by @devopvoid
- Switched to maven-release-plugin by @devopvoid
- Improved platform classifier handling by @devopvoid
- Improved native CMake builds by @devopvoid
- Update maven build procedure by @devopvoid
- Update maven build procedure by @devopvoid
- Update README.md by @devopvoid
- Maven CI credentials by @devopvoid
- JavaFX demo code cleanup by @devopvoid
- Refactor bloated project structure by @devopvoid
- Java module fixes by @devopvoid
- Updated JavaFX version by @devopvoid
- Patch DEPS to reduce time running gclient by @devopvoid
- Update issue templates by @devopvoid
- RTP header extension refactoring by @devopvoid
- Fetch WebRTC with no history by @devopvoid
- Added Windows to CI builds by @devopvoid
- Refactored cmake builds for WebRTC by @devopvoid
- Linux and macOS CI builds by @devopvoid
- Removed travis config due to GitHub Actions by @devopvoid
- Create maven.yml by @devopvoid
- Fixed travis install-build-deps.sh URL by @devopvoid
- Travis config before_install by @devopvoid
- Travis config by @devopvoid
- Device and capabilities retrieval by @devopvoid
- ICE candidate error api implementation by @devopvoid
- Merge remote-tracking branch 'origin/master' by @devopvoid
- Update README.md by @devopvoid
- ICE candidate removal by @devopvoid
- Updated WebRTC license URL by @devopvoid
- AppRTC demo integration by @devopvoid
- Fixed fork crash when test is finished by @devopvoid
- Removed debug output and updated JUnit version to 5.6.0 by @devopvoid
- Fixed module tests by @devopvoid
- Fixed JavaFX maven-shade-plugin main class by @devopvoid
- Signaling room JavaFX api update by @devopvoid
- AppRTC signaling implementation by @devopvoid
- Native peer connection demo signaling client by @devopvoid
- Signaling room api by @devopvoid
- Change default demo logging by @devopvoid
- Switch to WebRTC branch M80/3987 by @devopvoid
- Update README.md by @devopvoid
- Added module-infos and changed JSON api dependencies by @devopvoid
- Add dynamic libraries to .gitignore by @devopvoid
- Removed unnecessary assert by @devopvoid
- Fixed Unix builds by @devopvoid
- Update README.md by @devopvoid
- Update README.md by @devopvoid
- Mac visibility compiler flags by @devopvoid
- Removed unnecessary includes
- Fixed and improved CMake builds by @devopvoid
- Updated JUnit version to 5.5.2 by @devopvoid
- Improved test cases by @devopvoid
- Fixed non-Windows build
- Prepare for next development iteration


## [0.1.0] - 2019-12-22

### Other
- Prepare release webrtc-java-0.1.0
- Minor changes to the NOTICE and parent POM
- Initial commit by @devopvoid

## New Contributors
* @ made their first contribution
* @devopvoid made their first contribution


