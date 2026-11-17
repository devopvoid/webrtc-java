<!--
Thank you for contributing! Please read CONTRIBUTING.md before you open
a pull request. Do not report security vulnerabilities here, follow
SECURITY.md instead.

Title: use a Conventional Commit, it ends up in the changelog,
for example "fix: survive the removal of the default PulseAudio device".

Sections marked "if applicable" can be removed when they do not apply.
-->

## Description

<!-- What does this change do? -->

## Motivation and Context

<!--
Why is this change needed? What problem does it solve?
Link the related issue. For larger changes, please open an issue first.
-->

Fixes #

## Type of Change

- [ ] Bug fix (`fix`)
- [ ] New feature (`feat`)
- [ ] Performance improvement (`perf`)
- [ ] Refactoring without a change in behavior (`refactor`)
- [ ] Documentation (`docs`)
- [ ] Tests (`test`)
- [ ] Build, CI or dependencies (`chore`, `ci`)

## Affected Modules

- [ ] `webrtc` (Java API)
- [ ] `webrtc-jni` (native code)
- [ ] `webrtc-media`
- [ ] `webrtc-swing`
- [ ] `webrtc-javafx`
- [ ] `webrtc-examples`
- [ ] `docs`

## Breaking Changes (if applicable)

<!--
Does this change the public API or existing behavior in a way that breaks
existing code? Describe what breaks and how users can migrate.
-->

## Native Dependencies (if applicable)

<!--
Does this change the WebRTC branch, the FFmpeg or dav1d version, or the
WebRTC patches? Describe why and what changes for users.
-->

## How Has This Been Tested?

<!-- Describe the tests you ran and how reviewers can reproduce them. -->

Tested on:

- [ ] Windows (x64 / arm64)
- [ ] macOS (x64 / arm64)
- [ ] Linux (x64 / arm64 / arm32)
- [ ] GraalVM native image

Java version:

## Screenshots or Recordings (if applicable)

<!--
For changes to video views, screen capture or the examples, add screenshots
or a short recording.
-->

## Checklist

- [ ] I have read [CONTRIBUTING.md](https://github.com/devopvoid/webrtc-java/blob/main/CONTRIBUTING.md).
- [ ] The change is focused on one topic.
- [ ] My commits follow [Conventional Commits](https://www.conventionalcommits.org).
- [ ] I have reviewed my own code.
- [ ] My branch is up to date with `main`.
- [ ] I have added or updated tests that cover my change.
- [ ] All tests pass locally.
- [ ] I have run the tests with `-Pjni-check` if I changed JNI code.
- [ ] I have documented new public API with Javadoc.
- [ ] I have updated the documentation in `docs/guide` if behavior changed.
- [ ] New source files have the Apache License 2.0 header.
- [ ] I have not edited `CHANGELOG.md`, it is generated from the commits.
- [ ] I agree that my contribution is licensed under the
      [Apache License 2.0](https://github.com/devopvoid/webrtc-java/blob/main/LICENSE).

## Notes for Reviewers (if applicable)

<!-- Anything reviewers should look at in particular, or open questions. -->
