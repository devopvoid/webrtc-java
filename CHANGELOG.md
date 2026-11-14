# Changelog

All notable changes to this project will be documented in this file.

## [1.0.1] - 2026-11-14

### Features
- Let the user choose a screen or window in the macOS system picker
- Capture macOS windows with ScreenCaptureKit, and close the screen capture gaps
- Hint video tracks as motion, detail or text
- Support GraalVM native image
- Check camera, microphone and network before a call
- Record into rotating segments and keep a replay buffer
- Reserve ratchet steps in SFrame key IDs
- Add end-to-end encryption in the SFrame format
- Add file transfer over data channels
- Add system audio capture for desktop sharing
- Send device-captured and pushed audio from one factory
- Feed device-captured audio to senders through its track
- Add peer diagnostics and sanitized support reports
- Add configurable ICE recovery for client peers and HTTP sessions

### Bug Fixes
- Load the native library only once when Logging is used first
- Report why macOS system audio capture stopped
- Set up GraalVM with an allowed action
- Survive the removal of the default PulseAudio device
- Test segments and replays on a clock of their own
- Stop untransformed receive paths and preserve executor context
- Contain decoder-thread exceptions and report rejected media output
- MediaPlayer decoder and source lifetimes
- Harden audio blocks, JNI boundaries and channel limits
- Release codec wrappers and distinguish device failures
- Skip camera integration tests without libudev
- Validate I420 planes before native conversion
- Improve video view buffering and message logging

### Documentation
- Write the guides in shorter sentences
- Split the client API guide and sort the navigation
- Update the client API design and check it in the docs build
- Share a screen with the microphone and system audio
- Redesign documentation and publish Client API design guide

### Testing
- Write the stale partial file before the offer is accepted
- Drive the single-file recorder tests with frames and a clock of their own
- Read the position after a fast seek once a frame has gone out

### Other
- Fix AirPods audio when macOS switches A2DP to HFP
- Prepare for next development iteration


## [1.0.0] - 2026-11-06

### Features
- Choose the release version when dispatching a release
- Release native objects with try-with-resources
- Report native objects that were never disposed
- Move the Swing video view into its own module
- Let audio sinks take one reused array instead of a new one per chunk
- Load the native libraries from a given directory or the library path
- Seek to the keyframe before a position with fastSeek
- Detect speech natively for local audio tracks
- Decode video with VA-API on Linux
- Give WHIP and WHEP errors their status and problem details
- Expose WHIP/WHEP extensions and detect WHEP endpoints by HEAD
- Ask for key frames in the client API
- Encoded frame transforms for WHIP and WHEP
- Authorize WHIP and WHEP by HTTP Basic
- Encoded frame transforms in the client API
- Feed the voice activity example from client API audio
- Describe WHIP and WHEP errors by their problem details
- Send WHIP video as simulcast
- Bound the queue of pushed audio
- Trickle WHIP/WHEP candidates, take ICE servers from Link, restart ICE
- Allow plain http for WHIP/WHEP only on loopback
- Answer WHEP counter-offers
- Retry WHIP/WHEP offers after Retry-After
- Limit and measure the client API's handler threads
- Add a JavaFX camera example
- Port the media file player example to the client API
- Add high-level client API

### Bug Fixes
- Keep serial executors running when error reporting fails
- Honor video rotation metadata and non-square pixels in playback
- Preserve color metadata during FFmpeg playback
- Query contributing sources and report linear audio levels
- Keep retained desktop frames intact and end sources without a backend
- Bound desktop capture settings and remove a processed frame deadlock
- Keep codec hashes and MIME types locale independent and avoid camera score overflow
- Require the core module transitively in webrtc-media
- Keep TURN credentials intact and settle DTMF tones reliably
- Release D-Bus references and bound the window frame crop
- Reject a second desktop capture start and normalize encoded rotation
- Keep sleep prevented until the last request is released
- Reject custom audio that WebRTC cannot send
- Keep secrets of WHIP and WHEP URLs out of failures
- Close races in stats sampling and the JavaFX view
- Make media player seeking, position and queue memory reliable
- Harden track listeners and the media recorder
- Let a candidate held for its description free the operation chain
- Turn every C++ exception of a JNI entry point into a Java one
- Free observers and factories safely while they are still in use
- Make the Java jars reproducible and check the build's Maven and Java versions
- Close a peer connection at once while another call still uses it
- Check every JNI call for exceptions and test under -Xcheck:jni in CI
- Dispose native objects once and keep them while a call still uses them
- Release VideoCapture natively, keep its sink and report start errors
- Make track sinks safe to change from any thread and callback
- Keep native references until their Java wrapper is created
- Make decoder QP parsing flag atomic and give up codec calls without a JNI frame
- Return valid track arrays from MediaStream getters
- Report certificate expiration and fail library load on init errors
- Keep strings intact across JNI and bind both AudioResampler initializers
- Release native references and session descriptions in JNI glue
- Keep device changes during startup and surface failed device queries
- Detach closed peers from signaling and report stats per transport
- Release resources on failed setup, rejected tasks and oversized sends
- Report end of stream only after the last delivery has returned
- Deliver player listener calls in order on one event thread
- Voice activity hangover overflow and rounding
- Discard expired negotiations and serialize ICE restart cleanup
- Restart PulseAudio processing threads after reinitialization
- Bound HTTP response reads and handle overflowing retry dates
- Seek further back where the reader lands past the keyframe
- Close a peer only once the control tasks queued before have run
- Wait for a key frame after a recorder packet fails to allocate
- Start playback after a seek at the position asked for
- End a recording cleanly when its writer thread throws
- Build and test macOS on macos-15 runners
- Let WHIP and WHEP pick codecs, offer video for audio-only WHIP
- Share a SyncClock with the custom sources constructed with it
- Keep DTMF senders alive and observers of other wrappers registered
- Send WHIP offers sendonly
- Complete WHEP ICE restart support and harden HTTP sessions
- Follow the WebRTC constraints of WHIP and WHEP
- Skip the VP9 interpolation filter test where no hardware decodes VP9
- Register a handler added from a handler at once, so it sees queued events
- Send WHIP/WHEP requests with HttpClient, bounded and apart from peer control
- Stamp custom media pushed without a timestamp on WebRTC's clock
- Normalize VideoFrame rotation to 0, 90, 180 or 270
- Native observer and object lifecycles around closing a connection
- Accept custom frame buffers in VideoFrame.copy() and pushFrame()
- Check strides and plane sizes of custom I420 buffers before copying them
- Return no remote certificates before the DTLS handshake
- Send pushed audio from a thread apart from the timeouts
- Report Java exceptions from native callbacks in one piece
- Compute bitrates over report times, report stats handler errors
- Close an HTTP session's peer and resource independently, and fail on errors
- Register a camera switch with the runtime and reject it on a closed track
- Compare the recorded unicode file as a file, not a path, for macOS
- Report a recording that fails to close, and tell how it ended
- Pass media paths to FFmpeg as UTF-8
- Keep local track releases independent of handlers and cancelled futures
- Cover the remaining native failure paths of the media module
- Keep C++ exceptions and string pins inside the media JNI layer
- Round timeouts up and saturate them, so only zero waits without limit
- Validate signaling JSON and ICE indexes strictly
- Close a media player from a sink of a track it feeds
- Reject I420 buffer sizes WebRTC cannot allocate
- Run queued peer operations after the runtime stopped its timer
- Keep what a seek decoded at the new position
- End a WHIP or WHEP start whose future was cancelled
- Keep WHIP/WHEP session credentials and bound signaling JSON
- Wait for device switches on close and keep cleared or oversized audio from being sent
- Wide planar audio, audio timestamp gaps and vanished audio devices
- Close the peer of a WHIP or WHEP session that fails to start
- Keep camera ownership consistent and fail switches peers did not take
- Register a sender once when replaceTrack is given its current track
- Count AudioConverter sizes over all channels and bound them natively
- Size YUY2, UYVY and NV12 frames of odd width in whole pixel pairs
- Settle and release recorder sources of the client API
- Bound received data channel messages before they are queued
- Load the native library for classes used on their own
- Use the 3-parameter QueryCodecSupport overloads
- Convert only the window between position and limit of a ByteBuffer
- Bound AudioResampler buffers and keep cropped I420 buffers alive
- Guard track creation and report failed track releases
- Harden JNI error handling
- Harden data channel sends and runtime close
- Complete futures whose tasks a SerialExecutor drops
- Harden WHIP/WHEP session setup and teardown
- Close races in MediaReader.getInfo and WebRtc.createPeer
- Update logging defaults and smoke test output

### Refactor
- Split PeerImpl along its responsibilities
- Split operation chain and perfect negotiation out of PeerImpl
- Rename webrtc-java-media and webrtc-java-javafx directories

### Documentation
- A microphone track hands no audio to sinks
- List the tools and libraries needed to build from source
- Group the README features and ship LICENSE and NOTICE in every jar
- Fix two get-started examples that did not compile

### Testing
- Send the VP9 stream a frame at a time
- Make three timing-dependent tests deterministic
- Check the capture time of audio pushed without a timestamp
- Start recording a client API call once its remote video decodes
- Make the client concurrency tests deterministic

### Miscellaneous Tasks
- Update CHANGELOG.md for v1.0.0
- Disable the jni-check job
- Ignore Linux sysroots downloaded for the WebRTC build

### Other
- Prepare release v1.0.0
- Limit media source URLs in errors to their network origin
- Require Java 11
- Port the peer connection, desktop video and media file examples to the client API
- Add a JavaFX VideoView in a webrtc-java-javafx module
- Bump vite from 7.3.6 to 7.3.7 in /docs
- Bump com.fasterxml.jackson.core:jackson-databind
- Add standalone WebRTC VAD and harden detector lifecycle
- Split release preparation from tag-scoped publication
- Prepare for next development iteration


## [0.20.0] - 2026-10-16

### Features
- Decode VP9 spatial layers with VA-API
- Encode H.264 and H.265 temporal layers with VA-API
- Encode VP9 temporal layers with VA-API
- Encode AV1 temporal layers with VA-API
- Encode AV1 with VA-API on Linux
- Decode AV1 with VA-API on Linux
- Decode H.264 with VA-API on Linux
- Encode VP9 with VA-API on Linux without turning it on
- Decode H.265 with VA-API on Linux
- Let the audio codecs of a factory be limited and ordered
- Report receiver source changes and audio packet infos
- Send encoded frames through RTP sender injectors
- List the hardware codecs of the hardware video codec factories
- Decode H.265 and AV1 with NVDEC on Linux
- Encode H.265 and VP9 and decode VP9 with VA-API on Linux, when turned on
- Encode VP9 in hardware on Windows, when turned on
- Encode and decode H.265 in hardware on Windows
- Build WebRTC with H.265
- Decode with Vulkan Video on Linux in the media player
- Decode HEVC and AV1 in hardware in the media player
- Let a video processor change camera and desktop frames
- Add first-packet observers, sender and receiver IDs, and track volume
- Add the missing peer connection signaling and control calls
- Decode H.264 and VP9 with NVDEC on NVIDIA GPUs on Linux
- Decode video in hardware in the media player
- Decode VP9 on the GPU with Media Foundation on Windows
- Decode VP9 with VideoToolbox on macOS
- Add a hardware video decoder factory, with Media Foundation on Windows
- Encode AV1 on the GPU with Media Foundation and NVENC
- Encode H.264 with VA-API on Intel and AMD GPUs on Linux
- Encode H.264 with NVENC on NVIDIA GPUs, on Windows and Linux
- Add a hardware video encoder factory, with Media Foundation H.264
- Add custom video codec factories
- Map the remaining peer connection configuration fields
- Map the remaining sender encoding parameters

### Bug Fixes
- Restore version and changelog before 0.20.0 release
- Map the VP9 interpolation filter to VA-API's order
- Write the H.264 and H.265 headers for VA-API drivers that need them
- Call network thread observers on a callback thread
- Play and record on Windows devices that mix in more than two channels
- Capture screens with ScreenCaptureKit on macOS
- Mark encoded frames as the end of their temporal unit
- Dispose a MediaStream from onAddTrack as a view, and return its ID
- Keep camera hotplug and track views from crashing or releasing shared tracks
- Stop exceptions from callbacks unwinding through WebRTC
- Keep audio device change handling from aborting or corrupting state
- Keep exceptions inside the audio device change callbacks
- Fall back from a failed hardware video decoder without losing or damaging pictures
- Keep a seek near the end of the source and the position after seeking back
- Keep hardware encoders for sources whose first frame has an odd size

### Refactor
- Let the PeerConnectionFactory own its worker thread

### Documentation
- Link the unreleased docs from the README and release notes
- Note that VP9 cannot be injected
- Add icons and links to the home page features
- List the hardware codecs in tables
- Open the other channel's docs in the same tab
- List hardware codecs, video processing and frame injection as features

### Performance
- Cut the processor time of hardware decoding on Windows

### Testing
- Play real sizes and formats the GPU refuses in the media player
- Cover the lifecycle of hardware decoding in the media player
- Check that hardware decoders crop padded frames to the picture

### Miscellaneous Tasks
- Update CHANGELOG.md for v0.20.0
- Bump @vue/server-renderer and vue in /docs
- Bump source-map-js from 1.2.1 to 1.2.2 in /docs
- Publish the docs of the latest release, and main's under /next/
- Send Central credentials again under Maven 3.10
- Bump the github-actions group across 3 directories with 3 updates
- Share one platform matrix and deploy snapshots once
- Add Dependabot and trim caches and artifacts
- Harden the release workflow and keep native caches warm

### Other
- Prepare release v0.20.0
- Fix Central release bundling and dry runs
- Fix badge link text formatting in README.md
- Update documentation badge for next version
- Bump the maven-dependencies group with 3 updates
- Update WebRTC branch from 7977 to 8059 (M155)
- Build WebRTC with autoninja
- Patch FFmpeg so that Direct3D 12 decoding works on AMD and Intel
- Bump the maven-dependencies group with 7 updates
- Bump the maven-plugins group with 9 updates
- Bump com.fasterxml.jackson.core:jackson-databind
- Prepare for next development iteration


## [0.19.0] - 2026-09-27

### Features
- Add encoded frame transforms and a media recorder
- Add a native extension API and an FFmpeg-based media module

### Bug Fixes
- Repair the release pipeline and publish the media module
- Clear the CI build annotations
- Take Map method IDs from the interface instead of from HashMap
- Keep device-captured audio out of sink-fed audio senders
- Give AudioTrackSource a dispose() to release its native reference

### Miscellaneous Tasks
- Update CHANGELOG.md for v0.19.0
- Cache the FFmpeg build of the macOS x86_64 target

### Other
- Prepare release v0.19.0
- Sync the WebRTC checkout without git history
- Cross compile the macOS x86_64 target natively on Apple Silicon
- Build FFmpeg on every platform and make the media module part of the normal build
- Prepare for next development iteration


## [0.18.0] - 2026-09-15

### Features
- Allow setting custom WebRTC field trials on PeerConnectionFactory

### Bug Fixes
- Make CustomVideoSource's frame_id_ counter thread-safe
- Synchronize CustomAudioSource's sinks_ against AddSink/RemoveSink
- JNI memory-safety and thread-safety bugs in the native bridge

### Miscellaneous Tasks
- Update CHANGELOG.md for v0.18.0

### Other
- Prepare release v0.18.0
- Prepare for next development iteration


## [0.17.0] - 2026-09-13

### Features
- Expose asynchronous data channel send results
- Add windows arm64 platform support
- Offer both screens and windows in the Wayland portal picker

### Bug Fixes
- Report rejected synchronous data channel sends
- Throw instead of aborting when the default audio device module fails to initialize
- Keep the desktop capture frame rate at the configured value
- Keep libwebrtc quiet by default and make logToDebug work again
- Delete the local references created for received data channel messages
- Load libudev and libdbus at runtime instead of linking them

### Documentation
- Correct changelog v0.16.0 and supported os matrix
- Correct data channel buffered amount semantics
- List the Linux runtime dependencies of the native library

### Performance
- Avoid redundant data channel buffer copies

### Testing
- Give the DTMF completion wait more headroom

### Miscellaneous Tasks
- Update CHANGELOG.md for v0.17.0
- Test the windows arm64 natives on a native runner
- Test cross compiled natives on native runners

### Other
- Prepare release v0.17.0
- Bootstrap depot_tools before fetching the WebRTC source
- Suppress stdout for failed to attach threads on jvm shutdown
- Fix/windows desktop capture
- Prepare for next development iteration


## [0.16.0] - 2026-08-24

### Miscellaneous Tasks
- Update CHANGELOG.md for v0.15.1

### Other
- Prepare release v0.16.0
- Add Wayland/PipeWire desktop capture support
- Improve release workflow reliability and artifact management
- Refactor release workflows and setup for multi-platform deployment
- Prepare for next development iteration


## [0.15.0] - 2026-08-16

### Features
- Bridge OnIceSelectedCandidatePairChanged to Java observers
- Add asynchronous sendAsync to RTCDataChannel
- Device transport and form factor types
- Add funding information to support development
- Add legacy AGC1 and update AGC2 in the AudioProcessingConfig

### Bug Fixes
- Honor the buffer window in every send path
- Add OnBufferedAmountChange callback to RTCDataChannelObserver

### Documentation
- Add sponsorship link to index page
- Recreated using VitePress
- Add notes on native library classifiers for webrtc-java
- Reorganize guide links

### Testing
- Buffer trickled candidates until both descriptions are applied

### Miscellaneous Tasks
- Update CHANGELOG.md for v0.15.0

### Other
- Prepare release v0.15.0
- Update versions of dependencies and plugins
- Fix/linux desktop capturer x11 abi
- Update WebRTC branch version from 7339 to 7977 (m152)
- Derive the WebRTC cache branch from the pom and guard deploys
- Fix headless ADM playout drift below 100 Hz
- Bump org.eclipse.jetty:jetty-server in /webrtc-examples
- Bump com.fasterxml.jackson.core:jackson-databind
- Bump picomatch from 4.0.3 to 4.0.5 in /docs
- Merge pull request #241
- Bump rollup from 4.50.0 to 4.59.0 in /docs
- Bump vite from 7.1.11 to 7.3.6 in /docs
- Merge pull request #231
- Bump postcss from 8.5.6 to 8.5.26 in /docs
- Bump org.eclipse.jetty:jetty-util in /webrtc-examples
- Updates all checkout GitHub Actions to v6
- Bump mdast-util-to-hast from 13.2.0 to 13.2.1 in /docs
- Bump vite from 7.1.5 to 7.1.11 in /docs
- Update WebRTC branch version from 7204 to 7339 (m140)
- Bump vite from 7.1.4 to 7.1.5 in /docs
- Prepare for next development iteration


## [0.14.0] - 2025-08-28

### Features
- Restrict ICE port ranges and control candidate gathering behavior
- Push or pull audio frames with a headless audio device module
- Add guide for configuring send-only and receive-only media
- Add the platform classifier to the native library filename
- Add VideoBufferConverter.convertToI420
- Add SyncClock for audio and video synchronization support
- Add CustomVideoSource and related JNI bindings for video frame handling
- Added a new web-based example
- Custom audio source that can be provided to audio tracks

### Bug Fixes
- RTCDtmfSenderTests timeouts
- Update error handling to stop capturing on permanent errors #186
- Ensure JNIEnv is properly attached before deleting global reference 146
- Return a valid NtpTime from SyncClock::GetNtpTime
- Release video frame after processing in WHEP example #191
- Made RTCRtpCodecCapability's int fields optional

### Documentation
- Add AudioRecorder and AudioPlayer documentation
- Documentation for VideoBufferConverter
- Documentation for MediaStreamTrack, VideoBufferConverter, and VideoCapture
- Add Video Capture guide
- Add Screen Capturer and Window Capturer guides
- Add Power Management utility
- Add Audio Converter guide
- Add Voice Activity Detector guide
- Reorganize media guides into categories
- Add section on disabling audio processing with dummy audio layer
- Add Custom Video Source guide and update documentation
- Update example descriptions and add WebClientExample
- Add Custom Audio Source guide
- Update asset paths and add a privacy policy
- Add camera capture guide
- Update CHANGELOG.md

### Miscellaneous Tasks
- Update CHANGELOG.md for v0.14.0
- Add custom video source and generator to the web example

### Other
- Prepare release v0.14.0
- Update release workflow to use main branch
- Update GitHub Actions workflows to use changelog generation
- Update all GitHub Actions setup-java to v5
- Prepare for next development iteration


## [0.13.0] - 2025-07-25

### Features
- Add H.264 support and improve linking options for Apple platforms
- Add RTCDtmfSender implementation for sending DTMF tones #115
- Enable H.264 support for Linux and Windows builds #155 #168 #169

### Bug Fixes
- Update references from master to main in build configuration and documentation
- Enhance documentation for audio related topics
- Validate COM initialization in audio device management #181
- MacOS screen capturing with iOSurface and platform thread management

### Documentation
- Update README with logo
- Improve responsive design for the cover
- Enhance coverpage and sidebar styles with new logo and theme colors
- Update homepage and quickstart guide links in documentation
- Comprehensive guide and examples

### Miscellaneous Tasks
- Update readme to 0.12.0 and update changelog

### Other
- Prepare release v0.13.0
- Update release workflow to update versions.js in docs
- Create CNAME
- Prepare for next development iteration


## [0.12.0] - 2025-07-18

### Features
- Add copy method for NativeI420Buffer and VideoFrame
- Added the missing macOS camera capture functionality

### Bug Fixes
- Add Maven release action for macOS platforms

### Refactor
- Updated native interface to m138

### Documentation
- Update badge links in README for build status and Maven Central
- Update README for version 0.11.0 and clarify build notes

### Other
- Prepare release v0.12.0
- Fix release workflow to support matrix.platform.name
- Update package installation commands and enhance sysroot configuration
- Prepare for next development iteration


## [0.11.0] - 2025-07-02

### Bug Fixes
- Windows clang profile activation

### Refactor
- VideoTrackDesktopSource extends AdaptedVideoTrackSource
- Rtc and cricket to webrtc namespace

### Other
- Prepare release v0.11.0
- Add project name to pom.xml
- Update release workflow to include code checkout and linux arm support
- Link with libc++ and libc++abi on Linux and Windows
- Do not build with clang-cl on main
- Fix screen-share memory leak
- Revert "refactor: rtc and cricket to webrtc namespace"
- Revert "build: updated webrtc version to m137 in GitHub workflows"
- Updated webrtc version to m137 in GitHub workflows
- Updated project to the stable WebRTC branch 6998
- Build.yml pipewire activated
- Update README.md
- Prepare for next development iteration


## [0.10.0] - 2025-03-18

### Bug Fixes
- Added missing libx libraries to be installed in linux release workflow
- Added missing libx libraries to be installed in GH actions
- Crashes caused by video frame conversion
- Linux crashes caused by unlinked libraries

### Other
- Prepare release v0.10.0
- Update README.md
- Prepare for next development iteration


## [0.9.0] - 2025-01-02

### Other
- Prepare release v0.9.0
- Crash after java callback if exception occurred
- Workflow build and release fixes
- Removed gpg from action workflow
- Fixed action workflow deployment
- Update action.yml
- Updated build badge in README.md
- Linux pm rtc logging
- Fixed build workflow
- Fixed and improved GitHub build workflow
- Screen inhibition
- Fixed crash with jdk version > 17
- Revert version of cmake-maven-plugin for compatibility with older maven
- Updated maven plugin versions.
- Fixed NPE in MediaDevices when no audio device is connected.
- Update README.md with Linux ARM architectures
- Added Linux ARM architectures for cross compilation
- Fixed Apple Silicon build
- Update README.md macOS arm64 release
- Updated CHANGELOG and README
- Prepare for next development iteration


## [0.8.0] - 2023-10-14

### Other
- Prepare release v0.8.0
- Updated action runners
- Dropped old demo code. New demo will be in a new repository
- Added setFocusSelectedSource to DesktopCapturer and capture with DesktopAndCursorComposer
- Add a build support for mac/aarch64
- Merge remote-tracking branch 'origin/master'
- Update README.md
- Initialized configs to be used with GraalVM
- Set linux version to ubuntu-20.04 due to audio system tests
- Link libm from sysroot to increase GLIBC (<= 2.18) compatibility #108
- Updated CHANGELOG and README
- Added workflow_dispatch to the release workflow
- Removed demo tasks from the release workflow
- Prepare for next development iteration


## [0.7.0] - 2022-11-14

### Bug Fixes
- Fix memory leak

### Other
- Prepare release v0.7.0
- Fixed audio device names on mac #28
- Compile with backward compatibility for Java 8
- Terminate (end) VideoTrackDesktopSource if source cannot be selected
- Fixed RTCRtpCodecParameters conversion when setting sender params
- Fixed RTCRtpCodecParameters conversion
- Notify the track to end on permanent error when capturing a desktop frame
- Improved MediaStreamTrack "ended" and "muted" event observation
- Added MediaStreamTrack "ended" and "muted" event observation
- Removed Xcode version selection from the build workflow
- VideoTrackDesktopSource crop black window borders
- DesktopCaptureCallback convert DesktopFrame to I420
- Added setMaxFrameSize to VideoDesktopSource
- Enable set min,max bitrate and max framerate in RTCRtpSender
- Fixed crashes caused by DesktopCaptureCallback
- MacOS build with Xcode 11.7
- Use new RemoveTrackOrError API call in RTCPeerConnection to remove a sender
- Removed deprecated Windows wstring_convert calls
- Build demo code with Maven 'demo' profile
- Fixed RTCDataChannelTests
- Fixed JavaEnums returning JavaLocalRef
- Fixed JavaFactories returning jobjectArray
- Fixed JavaLocalRefs for enums and factories
- CI build with windows-2019
- Set maximum width and height for captured screen frames
- Fixed null pointer handling with RtpSender
- Added setFocusSelectedSource to VideoTrackDesktopSource
- Compile with CXX_STANDARD 17
- Minor option changes for DesktopCapturer
- Bump cmake-maven-plugin version to 3.22.1-b1
- Fixed memory leak in AudioTransportSink
- Updated RTC_LOG macros
- Removed deprecated code
- Bump native branch to 4844
- Removed demos since AppRTC services have been turned down
- Fixed setting codec preferences #57
- Removed setDesktopCapturer call on VideoDesktopSource
- Fixed desktop capturing
- Updated CHANGELOG and README
- Prepare for next development iteration


## [0.6.0] - 2022-01-17

### Other
- Prepare release v0.6.0
- Fixed camera device selection for a video track on Unix systems #30
- Get default audio devices
- Lazy-load audio/video managers #44
- Fixed loading classes from native threads with detached class-loaders #49
- Moved to WebRTC branch 4692 (M97)
- Updated workflow actions to WebRTC branch 4664 (M96)
- Moved to WebRTC branch 4664 (M96)
- Fixed AudioProcessing native ProcessReverseStream function call #46
- Updated CHANGELOG and README
- Prepare for next development iteration


## [0.5.0] - 2021-11-25

### Other
- Prepare release v0.5.0
- Use new api calls which support RTCError
- Fixed AudioTransportSource.cpp buffer passing
- Updated RTCStats.cpp to Java mapping
- Got rid of warnings in the audio section
- Fixed NPE on RTCPeerConnection.getConnectionState when peer-connection has been closed #35
- AudioConverter implementation for remixing and resampling
- Minor audio procession improvements
- Fixed basic channel up-mixing in AudioProcessing
- Improved audio processing by basic channel up-mixing to avoid additional audio converter
- Fixed AudioTransportSource buffer reading
- Fixed NPE in AudioProcessingConfig when no NS level was set
- Fixed CoInitialize has not been called #43
- Updated CHANGELOG and README
- Prepare for next development iteration


## [0.4.0] - 2021-11-21

### Other
- Prepare release v0.4.0
- Add settings-path for JDK setup to the release workflow
- Split AudioProcessingConfig into individual config classes. Added get/set of stream-delay to AudioProcessing.
- Add option to provide AudioProcessing to the PeerConnectionFactory
- AudioResampler fixes for more than one channel
- AudioResampler utility class which can be used for sampling rate conversion
- AudioProcessing to use only 16 Bit audio samples
- AudioPlayer and AudioRecorder utility classes which utilize the AudioDeviceModule
- Introduced AudioSink and AudioSource interfaces which can be passed to the AudioDeviceModule
- Audio/VideoSource renamed to Audio/VideoTrackSource
- Fixed temporary MTA<>STA threading issue #39
- Static linking libgcc and libstdc++ #27
- Fixed action workflow cache saving and loading
- AudioProcessing implementation
- Native JavaObject field setters
- Init version of Java implementation of AudioProcessing
- Workflow 'apt-get update' for linux action
- Added more audio processing options
- Moved to WebRTC branch 4638 (M95)
- Added stop/start methods for playout/recording
- Updated CHANGELOG and README
- Updated CHANGELOG and README
- Prepare for next development iteration


## [0.3.0] - 2021-08-19

### Other
- Prepare release v0.3.0
- Fixed local video is sometimes empty #9
- Fixed WebRTC cmake build
- Fixed NPE in JNI_PeerConnectionFactory
- Switch to a more stable branch (4515)
- Update pom.xml
- Update CMakeLists.txt
- Update pom.xml
- Merge remote-tracking branch 'origin/master'
- Update README.md
- Audio/video device (Windows) handling consistent with the internal API
- Audio device retrieval with the AudioDeviceModule
- Removed Azure Pipelines
- Merge pull request #20 from rmberne/dummy-audio-for-testing
- Use dummy audio in the unit test
- Add possibility to use dummy audio for testing purposes
- Fixed NativeI420Buffer allocate
- Add VideoCapture to capture frames without a running track
- Removed usage of webrtc::VideoType::kARGB1555
- Updated pipelines
- Removed usage of webrtc::VideoType::kNV12
- Updated WebRTC branch to 4472 (M91) and updated dependency versions
- Updated Sonatype provision URL
- Fixed desktop capture
- Bind transceiver direction to the configuration
- Fixed demo audio/video config settings
- Revert "Create codeql-analysis.yml"
- Create codeql-analysis.yml
- Updated GitHub Actions workflows
- Update README.md
- Merge remote-tracking branch 'origin/master'
- Update README.md
- Fixed possible arithmetic overflow
- Linux M88 patch. Updated pipelines.
- Bump WebRTC branch to 4324 (M88)
- Merge pull request #6 from averyzhong/master
- Update AVFVideoDeviceManager.cpp
- Change GitHub Actions env names for Maven credentials
- Fixed MF init scope
- Fixed linux build dependencies in CI builds
- Fixed macOS CoreAudioDeviceManager
- Advance WebRTC branch to 4044 (m81)
- MediaDevice change listener implementation
- Refactored JNI helper classes
- Show settings link in the start view
- Add Maven Central badge to README
- Reference javafx demo app name to 'webrtc-javafx-demo-(platform)'
- Prepare for next development iteration


## [0.2.0] - 2020-02-25

### Other
- Prepare release v0.2.0
- Added CHANGELOG
- Update README
- Cleanup Azure build pipeline
- GitHub Actions CI/CD configurations
- Merge branch 'master' of github.com:devopvoid/webrtc-java
- Cleanup GitHub Actions workflow
- Provide Azure Pipelines CI/CD configurations
- Switched to maven-release-plugin
- Improved platform classifier handling
- Improved native CMake builds
- Update maven build procedure
- Update maven build procedure
- Update README.md
- Maven CI credentials
- JavaFX demo code cleanup
- Refactor bloated project structure
- Java module fixes
- Updated JavaFX version
- Patch DEPS to reduce time running gclient
- Update issue templates
- RTP header extension refactoring
- Fetch WebRTC with no history
- Added Windows to CI builds
- Refactored cmake builds for WebRTC
- Linux and macOS CI builds
- Removed travis config due to GitHub Actions
- Create maven.yml
- Fixed travis install-build-deps.sh URL
- Travis config before_install
- Travis config
- Device and capabilities retrieval
- ICE candidate error api implementation
- Merge remote-tracking branch 'origin/master'
- Update README.md
- ICE candidate removal
- Updated WebRTC license URL
- AppRTC demo integration
- Fixed fork crash when test is finished
- Removed debug output and updated JUnit version to 5.6.0
- Fixed module tests
- Fixed JavaFX maven-shade-plugin main class
- Signaling room JavaFX api update
- AppRTC signaling implementation
- Native peer connection demo signaling client
- Signaling room api
- Change default demo logging
- Switch to WebRTC branch M80/3987
- Update README.md
- Added module-infos and changed JSON api dependencies
- Add dynamic libraries to .gitignore
- Removed unnecessary assert
- Fixed Unix builds
- Update README.md
- Update README.md
- Mac visibility compiler flags
- Removed unnecessary includes
- Fixed and improved CMake builds
- Updated JUnit version to 5.5.2
- Improved test cases
- Fixed non-Windows build
- Prepare for next development iteration


## [0.1.0] - 2019-12-22

### Other
- Prepare release webrtc-java-0.1.0
- Minor changes to the NOTICE and parent POM
- Initial commit



