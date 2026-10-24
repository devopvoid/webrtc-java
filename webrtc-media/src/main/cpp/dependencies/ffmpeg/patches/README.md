# Patches to FFmpeg

Fixes that the pinned FFmpeg release does not have. `CMakeLists.txt` in the directory above applies every
`*.patch` here, in the order of their names, to the `third-party/ffmpeg` submodule for the length of the FFmpeg
build and takes them off again afterwards, so the submodule is clean once the build is done.

- A patch is made against the release the submodule is at: `git diff` in the submodule, with its header above the
  `diff --git` line saying what is wrong and why, which `git apply` ignores.
- The name and the contents of each patch are recorded in the `components.txt` of an install, and go into the key of
  the CI cache, so adding, changing or removing one builds FFmpeg again.
- A patch that no longer applies stops the build. When the submodule moves to a new release, check whether it has
  the fix, and remove the patch if it has.

| Patch | Fixes |
| --- | --- |
| `0001-d3d12va-reuse-the-reference-only-resource-of-a-texture.patch` | Direct3D 12 decoding failing after the first few pictures on drivers that need reference-only allocations (AMD, Intel) |
