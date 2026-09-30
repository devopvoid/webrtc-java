# libva headers

`include/va/` holds the public headers of libva 2.17.0 (VA-API 1.17), taken
unchanged from https://github.com/intel/libva/tree/2.17.0/va, except:

- `va_drm.h` comes from `va/drm/va_drm.h`;
- `va_version.h` is generated from `va/va_version.h.in` with version 1.17.0,
  as the libva build does.

The library loads `libva.so.2` and `libva-drm.so.2` at run time, so the
headers are all the build needs, including for the cross-compiled targets.
libva is licensed under the MIT license in `COPYING`.
