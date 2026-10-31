/*
 * Copyright 2026 Alex Andres
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Stands in for libva and libva-drm when FFmpeg is linked, so that the FFmpeg
 * libraries do not need them to load: every function FFmpeg calls is defined
 * here, and loads the real libva with dlopen on its first call.
 *
 * Linked against the real libraries, libavutil and libavcodec would name them
 * as dependencies, and a machine without libva could not load them at all,
 * hardware decoding or not. This way such a machine finds no VA display
 * (vaGetDisplayDRM returns NULL), the device is not created, and the player
 * goes on to the next kind of device or to software, as it does where there
 * is no CUDA.
 *
 * The functions are the ones FFmpeg's VA-API device and decoders call, as
 * nm -u lists them for the objects of its build, and vaMapBuffer, which it
 * calls instead of vaMapBuffer2 with headers older than libva 2.21. A function
 * that is missing here leaves an undefined symbol in the FFmpeg libraries,
 * which the build checks for.
 *
 * The declarations come from the libva headers the build is given, and every
 * pointer here takes its type from them, so the compiler holds each function
 * to its prototype.
 */

#include <dlfcn.h>
#include <pthread.h>
#include <stddef.h>

#include <va/va.h>
#include <va/va_drm.h>

/* The libraries of libva 2.x, whose ABI the soname promises to keep. */
#define LIBVA_NAME "libva.so.2"
#define LIBVA_DRM_NAME "libva-drm.so.2"

#if VA_CHECK_VERSION(1, 21, 0)
#define LOADER_HAS_MAP_BUFFER2 1
#else
#define LOADER_HAS_MAP_BUFFER2 0
#endif

#define LIBVA_FUNCTIONS(X) \
	X(vaBeginPicture) \
	X(vaCreateBuffer) \
	X(vaCreateConfig) \
	X(vaCreateContext) \
	X(vaCreateImage) \
	X(vaCreateSurfaces) \
	X(vaDeriveImage) \
	X(vaDestroyBuffer) \
	X(vaDestroyConfig) \
	X(vaDestroyContext) \
	X(vaDestroyImage) \
	X(vaDestroySurfaces) \
	X(vaEndPicture) \
	X(vaErrorStr) \
	X(vaGetDisplayAttributes) \
	X(vaGetImage) \
	X(vaInitialize) \
	X(vaMapBuffer) \
	X(vaMaxNumImageFormats) \
	X(vaMaxNumProfiles) \
	X(vaPutImage) \
	X(vaQueryConfigProfiles) \
	X(vaQueryImageFormats) \
	X(vaQuerySurfaceAttributes) \
	X(vaQueryVendorString) \
	X(vaRenderPicture) \
	X(vaSetDriverName) \
	X(vaSetErrorCallback) \
	X(vaSetInfoCallback) \
	X(vaSyncSurface) \
	X(vaTerminate) \
	X(vaUnmapBuffer)

#define LIBVA_POINTER(name) static __typeof__(name) * real_##name;

LIBVA_FUNCTIONS(LIBVA_POINTER)

#if LOADER_HAS_MAP_BUFFER2
LIBVA_POINTER(vaMapBuffer2)
#endif

LIBVA_POINTER(vaGetDisplayDRM)

static pthread_once_t load_once = PTHREAD_ONCE_INIT;

static void load(void)
{
	/* Kept open for the life of the process: the display and everything made
	 * on it belong to the library. Local, so that its symbols do not stand in
	 * for these or for anyone else's. */
	void * libva = dlopen(LIBVA_NAME, RTLD_NOW | RTLD_LOCAL);

	if (libva == NULL) {
		return;
	}

	void * libva_drm = dlopen(LIBVA_DRM_NAME, RTLD_NOW | RTLD_LOCAL);

	if (libva_drm == NULL) {
		/* No display to be had without it, so none of libva is used. */
		dlclose(libva);

		return;
	}

#define LIBVA_RESOLVE(name) real_##name = (__typeof__(name) *) dlsym(libva, #name);

	LIBVA_FUNCTIONS(LIBVA_RESOLVE)

#if LOADER_HAS_MAP_BUFFER2
	/* libva 2.21 and later; an older one has vaMapBuffer only, which the
	 * stand-in below falls back to. */
	LIBVA_RESOLVE(vaMapBuffer2)
#endif

	real_vaGetDisplayDRM = (__typeof__(vaGetDisplayDRM) *) dlsym(libva_drm, "vaGetDisplayDRM");
}

#define LOAD_OR_RETURN(name, fallback) \
	pthread_once(&load_once, load); \
	if (real_##name == NULL) { \
		return fallback; \
	}

VADisplay vaGetDisplayDRM(int fd)
{
	/* NULL is how libva says that there is no display on the device, which is
	 * what FFmpeg makes of a machine without libva as well. */
	LOAD_OR_RETURN(vaGetDisplayDRM, NULL)

	return real_vaGetDisplayDRM(fd);
}

VAStatus vaInitialize(VADisplay dpy, int * major_version, int * minor_version)
{
	LOAD_OR_RETURN(vaInitialize, VA_STATUS_ERROR_UNIMPLEMENTED)

	return real_vaInitialize(dpy, major_version, minor_version);
}

VAStatus vaTerminate(VADisplay dpy)
{
	LOAD_OR_RETURN(vaTerminate, VA_STATUS_ERROR_UNIMPLEMENTED)

	return real_vaTerminate(dpy);
}

const char * vaErrorStr(VAStatus error_status)
{
	LOAD_OR_RETURN(vaErrorStr, "libva is not available")

	return real_vaErrorStr(error_status);
}

const char * vaQueryVendorString(VADisplay dpy)
{
	LOAD_OR_RETURN(vaQueryVendorString, NULL)

	return real_vaQueryVendorString(dpy);
}

VAStatus vaSetDriverName(VADisplay dpy, char * driver_name)
{
	LOAD_OR_RETURN(vaSetDriverName, VA_STATUS_ERROR_UNIMPLEMENTED)

	return real_vaSetDriverName(dpy, driver_name);
}

VAMessageCallback vaSetErrorCallback(VADisplay dpy, VAMessageCallback callback, void * user_context)
{
	LOAD_OR_RETURN(vaSetErrorCallback, NULL)

	return real_vaSetErrorCallback(dpy, callback, user_context);
}

VAMessageCallback vaSetInfoCallback(VADisplay dpy, VAMessageCallback callback, void * user_context)
{
	LOAD_OR_RETURN(vaSetInfoCallback, NULL)

	return real_vaSetInfoCallback(dpy, callback, user_context);
}

VAStatus vaGetDisplayAttributes(VADisplay dpy, VADisplayAttribute * attr_list, int num_attributes)
{
	LOAD_OR_RETURN(vaGetDisplayAttributes, VA_STATUS_ERROR_UNIMPLEMENTED)

	return real_vaGetDisplayAttributes(dpy, attr_list, num_attributes);
}

int vaMaxNumProfiles(VADisplay dpy)
{
	LOAD_OR_RETURN(vaMaxNumProfiles, 0)

	return real_vaMaxNumProfiles(dpy);
}

int vaMaxNumImageFormats(VADisplay dpy)
{
	LOAD_OR_RETURN(vaMaxNumImageFormats, 0)

	return real_vaMaxNumImageFormats(dpy);
}

VAStatus vaQueryConfigProfiles(VADisplay dpy, VAProfile * profile_list, int * num_profiles)
{
	LOAD_OR_RETURN(vaQueryConfigProfiles, VA_STATUS_ERROR_UNIMPLEMENTED)

	return real_vaQueryConfigProfiles(dpy, profile_list, num_profiles);
}

VAStatus vaQueryImageFormats(VADisplay dpy, VAImageFormat * format_list, int * num_formats)
{
	LOAD_OR_RETURN(vaQueryImageFormats, VA_STATUS_ERROR_UNIMPLEMENTED)

	return real_vaQueryImageFormats(dpy, format_list, num_formats);
}

VAStatus vaQuerySurfaceAttributes(VADisplay dpy, VAConfigID config, VASurfaceAttrib * attrib_list,
		unsigned int * num_attribs)
{
	LOAD_OR_RETURN(vaQuerySurfaceAttributes, VA_STATUS_ERROR_UNIMPLEMENTED)

	return real_vaQuerySurfaceAttributes(dpy, config, attrib_list, num_attribs);
}

VAStatus vaCreateConfig(VADisplay dpy, VAProfile profile, VAEntrypoint entrypoint,
		VAConfigAttrib * attrib_list, int num_attribs, VAConfigID * config_id)
{
	LOAD_OR_RETURN(vaCreateConfig, VA_STATUS_ERROR_UNIMPLEMENTED)

	return real_vaCreateConfig(dpy, profile, entrypoint, attrib_list, num_attribs, config_id);
}

VAStatus vaDestroyConfig(VADisplay dpy, VAConfigID config_id)
{
	LOAD_OR_RETURN(vaDestroyConfig, VA_STATUS_ERROR_UNIMPLEMENTED)

	return real_vaDestroyConfig(dpy, config_id);
}

VAStatus vaCreateSurfaces(VADisplay dpy, unsigned int format, unsigned int width, unsigned int height,
		VASurfaceID * surfaces, unsigned int num_surfaces, VASurfaceAttrib * attrib_list,
		unsigned int num_attribs)
{
	LOAD_OR_RETURN(vaCreateSurfaces, VA_STATUS_ERROR_UNIMPLEMENTED)

	return real_vaCreateSurfaces(dpy, format, width, height, surfaces, num_surfaces, attrib_list,
			num_attribs);
}

VAStatus vaDestroySurfaces(VADisplay dpy, VASurfaceID * surfaces, int num_surfaces)
{
	LOAD_OR_RETURN(vaDestroySurfaces, VA_STATUS_ERROR_UNIMPLEMENTED)

	return real_vaDestroySurfaces(dpy, surfaces, num_surfaces);
}

VAStatus vaCreateContext(VADisplay dpy, VAConfigID config_id, int picture_width, int picture_height,
		int flag, VASurfaceID * render_targets, int num_render_targets, VAContextID * context)
{
	LOAD_OR_RETURN(vaCreateContext, VA_STATUS_ERROR_UNIMPLEMENTED)

	return real_vaCreateContext(dpy, config_id, picture_width, picture_height, flag, render_targets,
			num_render_targets, context);
}

VAStatus vaDestroyContext(VADisplay dpy, VAContextID context)
{
	LOAD_OR_RETURN(vaDestroyContext, VA_STATUS_ERROR_UNIMPLEMENTED)

	return real_vaDestroyContext(dpy, context);
}

VAStatus vaCreateBuffer(VADisplay dpy, VAContextID context, VABufferType type, unsigned int size,
		unsigned int num_elements, void * data, VABufferID * buf_id)
{
	LOAD_OR_RETURN(vaCreateBuffer, VA_STATUS_ERROR_UNIMPLEMENTED)

	return real_vaCreateBuffer(dpy, context, type, size, num_elements, data, buf_id);
}

VAStatus vaDestroyBuffer(VADisplay dpy, VABufferID buffer_id)
{
	LOAD_OR_RETURN(vaDestroyBuffer, VA_STATUS_ERROR_UNIMPLEMENTED)

	return real_vaDestroyBuffer(dpy, buffer_id);
}

VAStatus vaMapBuffer(VADisplay dpy, VABufferID buf_id, void ** pbuf)
{
	LOAD_OR_RETURN(vaMapBuffer, VA_STATUS_ERROR_UNIMPLEMENTED)

	return real_vaMapBuffer(dpy, buf_id, pbuf);
}

#if LOADER_HAS_MAP_BUFFER2
VAStatus vaMapBuffer2(VADisplay dpy, VABufferID buf_id, void ** pbuf, uint32_t flags)
{
	pthread_once(&load_once, load);

	if (real_vaMapBuffer2 == NULL) {
		/* The flags only say how the mapping is going to be used, which an
		 * older libva does not ask about. */
		(void) flags;

		return vaMapBuffer(dpy, buf_id, pbuf);
	}

	return real_vaMapBuffer2(dpy, buf_id, pbuf, flags);
}
#endif

VAStatus vaUnmapBuffer(VADisplay dpy, VABufferID buf_id)
{
	LOAD_OR_RETURN(vaUnmapBuffer, VA_STATUS_ERROR_UNIMPLEMENTED)

	return real_vaUnmapBuffer(dpy, buf_id);
}

VAStatus vaBeginPicture(VADisplay dpy, VAContextID context, VASurfaceID render_target)
{
	LOAD_OR_RETURN(vaBeginPicture, VA_STATUS_ERROR_UNIMPLEMENTED)

	return real_vaBeginPicture(dpy, context, render_target);
}

VAStatus vaRenderPicture(VADisplay dpy, VAContextID context, VABufferID * buffers, int num_buffers)
{
	LOAD_OR_RETURN(vaRenderPicture, VA_STATUS_ERROR_UNIMPLEMENTED)

	return real_vaRenderPicture(dpy, context, buffers, num_buffers);
}

VAStatus vaEndPicture(VADisplay dpy, VAContextID context)
{
	LOAD_OR_RETURN(vaEndPicture, VA_STATUS_ERROR_UNIMPLEMENTED)

	return real_vaEndPicture(dpy, context);
}

VAStatus vaSyncSurface(VADisplay dpy, VASurfaceID render_target)
{
	LOAD_OR_RETURN(vaSyncSurface, VA_STATUS_ERROR_UNIMPLEMENTED)

	return real_vaSyncSurface(dpy, render_target);
}

VAStatus vaCreateImage(VADisplay dpy, VAImageFormat * format, int width, int height, VAImage * image)
{
	LOAD_OR_RETURN(vaCreateImage, VA_STATUS_ERROR_UNIMPLEMENTED)

	return real_vaCreateImage(dpy, format, width, height, image);
}

VAStatus vaDestroyImage(VADisplay dpy, VAImageID image)
{
	LOAD_OR_RETURN(vaDestroyImage, VA_STATUS_ERROR_UNIMPLEMENTED)

	return real_vaDestroyImage(dpy, image);
}

VAStatus vaDeriveImage(VADisplay dpy, VASurfaceID surface, VAImage * image)
{
	LOAD_OR_RETURN(vaDeriveImage, VA_STATUS_ERROR_UNIMPLEMENTED)

	return real_vaDeriveImage(dpy, surface, image);
}

VAStatus vaGetImage(VADisplay dpy, VASurfaceID surface, int x, int y, unsigned int width,
		unsigned int height, VAImageID image)
{
	LOAD_OR_RETURN(vaGetImage, VA_STATUS_ERROR_UNIMPLEMENTED)

	return real_vaGetImage(dpy, surface, x, y, width, height, image);
}

VAStatus vaPutImage(VADisplay dpy, VASurfaceID surface, VAImageID image, int src_x, int src_y,
		unsigned int src_width, unsigned int src_height, int dest_x, int dest_y,
		unsigned int dest_width, unsigned int dest_height)
{
	LOAD_OR_RETURN(vaPutImage, VA_STATUS_ERROR_UNIMPLEMENTED)

	return real_vaPutImage(dpy, surface, image, src_x, src_y, src_width, src_height, dest_x, dest_y,
			dest_width, dest_height);
}
