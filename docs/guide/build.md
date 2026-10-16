# Build Notes

To build the native code, be sure to install the prerequisite software (follow the links):

**Note**: You don't have to install the Depot Tools, the build script will do that for you.

| OS        | Build Instructions                                                |
| --------- | ----------------------------------------------------------------- |
| Linux     | [Ubuntu][build-linux-ubuntu], [other distros][build-linux-other]  |
| macOS     | Xcode 9 or higher                                                 |
| Windows   | [Visual Studio][build-windows]                                    |

Assuming you have all the prerequisites installed for your OS, run:

```shell
mvn install
```

On the first run, the WebRTC source tree will be loaded into the `/<user home>/webrtc` directory. This will take a while and require about 20 GB of disk space.

## Build Parameters

| Parameter          | Description                                            | Default Value               |
| ------------------ | ------------------------------------------------------ |-----------------------------|
| webrtc.branch      | The WebRTC branch to checkout.                         | branch-heads/8059           |
| webrtc.src.dir     | The absolute checkout path for the WebRTC source tree. | /\<user_home\>/webrtc       |
| webrtc.install.dir | The install path for the compiled WebRTC library. Is also used to link against a pre-compiled WebRTC library to reduce build time. | /\<user_home\>/webrtc/build |

[build-linux-ubuntu]: https://chromium.googlesource.com/chromium/src/+/master/docs/linux/build_instructions.md#system-requirements
[build-linux-other]: https://chromium.googlesource.com/chromium/src/+/master/docs/linux/build_instructions.md#Notes-for-other-distros
[build-windows]: https://chromium.googlesource.com/chromium/src/+/master/docs/windows_build_instructions.md#visual-studio

## Release workflow

Maintainers start **Prepare release** (`release.yml`) from `main`, providing the
next development version, such as `0.16.0-SNAPSHOT`. Preparation commits the
changelog and version changes and atomically pushes `main` and the release tag.
It then dispatches **Publish release** (`release-publish.yml`) on that tag using
`GITHUB_TOKEN` with `actions: write`; no additional token is needed. A push made
with `GITHUB_TOKEN` does not trigger a tag push workflow on its own.

Publication requires a `vX.Y.Z` tag whose commit belongs to `main` and whose
version matches the root pom. All platform builds, native tests and publication
jobs check out the triggering commit SHA. After testing every platform, the
workflow publishes one Maven Central bundle and creates a draft GitHub release.

Selecting **dryRun** in Prepare release builds and tests the dispatched commit
with the release version, signs and assembles a deployment bundle, and uploads
it as `deployment-bundle-dry-run`. It pushes no commits or tags, dispatches no
publication workflow, and uses placeholder Central credentials with uploads
disabled.

If preparation pushed the tag but dispatch failed, start Publish release manually
and select that tag. Before retrying a failed publication, check whether Maven
Central already accepted the version; published releases cannot be overwritten.
Maintainer-pushed `v*` tags also start Publish release automatically.
