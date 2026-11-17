# Security Policy

## Supported Versions

Security fixes are made on the latest release line and published as a new
patch release. Older versions do not receive fixes, so please upgrade to the
latest release before reporting an issue.

| Version | Supported          |
|---------|:------------------:|
| 1.x.x   | :white_check_mark: |
| < 1.0   | :x:                |

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues,
discussions or pull requests.**

Report them privately through GitHub's
[private vulnerability reporting](https://github.com/devopvoid/webrtc-java/security/advisories/new)
("Security" tab → "Report a vulnerability"). Only the maintainers can see the
report.

Please include as much of the following as you can:

- The affected version of webrtc-java and the modules involved
  (`webrtc-java`, `webrtc-java-media`, `webrtc-java-swing`, `webrtc-java-javafx`)
- Operating system, CPU architecture and Java version
  (or whether you run a GraalVM native image)
- The type of issue, for example memory corruption in the native layer,
  a crash triggered by remote input, a flaw in the SFrame end-to-end
  encryption, or information leaking into logs or support reports
- Step-by-step instructions to reproduce the issue, ideally with a minimal
  proof of concept
- The impact, including how an attacker could exploit the issue

## What to Expect

- We will acknowledge your report within **7 days**.
- We will keep you informed while we investigate and work on a fix, and may
  ask you for more details.
- Once a fix is released, we will publish a security advisory on GitHub and
  credit you for the discovery, unless you prefer to remain anonymous.

Please give us a reasonable amount of time to release a fix before you
disclose the issue publicly. We aim to resolve confirmed vulnerabilities
within **90 days**.

## Scope

webrtc-java wraps native libraries that it builds from upstream sources:

- **WebRTC** (libwebrtc), built from the branch set in `webrtc-jni/pom.xml`
- **FFmpeg**, used by the optional `webrtc-java-media` module

If a vulnerability is in one of these projects and not in the way
webrtc-java uses them, please report it to the upstream project as well:

- WebRTC: [Chromium security](https://www.chromium.org/Home/chromium-security/reporting-security-bugs/)
- FFmpeg: [FFmpeg security](https://ffmpeg.org/security.html)

You are still welcome to tell us about it privately, so that we can update the
bundled version in a new release.

The examples in `webrtc-examples` are meant for demonstration and are not
hardened for production use. Issues that only affect them are generally not
treated as vulnerabilities.
