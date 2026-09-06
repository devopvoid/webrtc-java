/*
 * Copyright 2019 Alex Andres
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

#ifndef PULSE_AUDIO_LOADER_H
#define PULSE_AUDIO_LOADER_H

#include <pulse/pulseaudio.h>

#include <dlfcn.h>

#include <string>

#include "Exception.h"

namespace jni 
{
    namespace avdev 
    {
        typedef pa_threaded_mainloop* (*pa_threaded_mainloop_new_t)();
        typedef void (*pa_threaded_mainloop_free_t)(pa_threaded_mainloop* m);
        typedef int (*pa_threaded_mainloop_start_t)(pa_threaded_mainloop* m);
        typedef void (*pa_threaded_mainloop_stop_t)(pa_threaded_mainloop* m);
        typedef pa_mainloop_api* (*pa_threaded_mainloop_get_api_t)(pa_threaded_mainloop* m);
        typedef void (*pa_threaded_mainloop_lock_t)(pa_threaded_mainloop* m);
        typedef void (*pa_threaded_mainloop_unlock_t)(pa_threaded_mainloop* m);
        typedef void (*pa_threaded_mainloop_wait_t)(pa_threaded_mainloop* m);
        typedef void (*pa_threaded_mainloop_signal_t)(pa_threaded_mainloop* m, int wait_for_accept);
        typedef int (*pa_threaded_mainloop_in_thread_t)(pa_threaded_mainloop* m);

        typedef pa_context* (*pa_context_new_t)(pa_mainloop_api* mainloop, const char* name);
        typedef void (*pa_context_unref_t)(pa_context* c);
        typedef void (*pa_context_set_state_callback_t)(pa_context* c, pa_context_notify_cb_t cb, void* userdata);
        typedef void (*pa_context_set_subscribe_callback_t)(pa_context* c, pa_context_subscribe_cb_t cb, void* userdata);
        typedef int (*pa_context_connect_t)(pa_context* c, const char* server, pa_context_flags_t flags, const pa_spawn_api* api);
        typedef void (*pa_context_disconnect_t)(pa_context* c);
        typedef pa_context_state_t (*pa_context_get_state_t)(const pa_context* c);
        typedef pa_operation* (*pa_context_subscribe_t)(pa_context* c, pa_subscription_mask_t m, pa_context_success_cb_t cb, void* userdata);
        typedef pa_operation* (*pa_context_get_server_info_t)(pa_context* c, pa_server_info_cb_t cb, void* userdata);
        typedef pa_operation* (*pa_context_get_source_info_by_name_t)(pa_context* c, const char* name, pa_source_info_cb_t cb, void* userdata);
        typedef pa_operation* (*pa_context_get_source_info_list_t)(pa_context* c, pa_source_info_cb_t cb, void* userdata);
        typedef pa_operation* (*pa_context_get_source_info_by_index_t)(pa_context* c, uint32_t idx, pa_source_info_cb_t cb, void* userdata);
        typedef pa_operation* (*pa_context_get_sink_info_by_name_t)(pa_context* c, const char* name, pa_sink_info_cb_t cb, void* userdata);
        typedef pa_operation* (*pa_context_get_sink_info_list_t)(pa_context* c, pa_sink_info_cb_t cb, void* userdata);
        typedef pa_operation* (*pa_context_get_sink_info_by_index_t)(pa_context* c, uint32_t idx, pa_sink_info_cb_t cb, void* userdata);

        typedef void (*pa_operation_unref_t)(pa_operation* o);
        typedef pa_operation_state_t (*pa_operation_get_state_t)(const pa_operation* o);
        typedef const char* (*pa_proplist_gets_t)(const pa_proplist* p, const char* key);

        class PulseAudioLoader {
        public:
            static PulseAudioLoader& instance() {
                static PulseAudioLoader instance;
                return instance;
            }

            bool load() {
                if (loaded) return true;
                
                // Try to open the library
                lib_handle = dlopen("libpulse.so.0", RTLD_NOW);
                if (!lib_handle) return false;

                #define LOAD_SYM(name) \
                    name = (name##_t)dlsym(lib_handle, #name); \
                    if (!name) { close(); return false; }

                LOAD_SYM(pa_threaded_mainloop_new);
                LOAD_SYM(pa_threaded_mainloop_free);
                LOAD_SYM(pa_threaded_mainloop_start);
                LOAD_SYM(pa_threaded_mainloop_stop);
                LOAD_SYM(pa_threaded_mainloop_get_api);
                LOAD_SYM(pa_threaded_mainloop_lock);
                LOAD_SYM(pa_threaded_mainloop_unlock);
                LOAD_SYM(pa_threaded_mainloop_wait);
                LOAD_SYM(pa_threaded_mainloop_signal);
                LOAD_SYM(pa_threaded_mainloop_in_thread);
                
                LOAD_SYM(pa_context_new);
                LOAD_SYM(pa_context_unref);
                LOAD_SYM(pa_context_set_state_callback);
                LOAD_SYM(pa_context_set_subscribe_callback);
                LOAD_SYM(pa_context_connect);
                LOAD_SYM(pa_context_disconnect);
                LOAD_SYM(pa_context_get_state);
                LOAD_SYM(pa_context_subscribe);
                
                LOAD_SYM(pa_context_get_server_info);
                LOAD_SYM(pa_context_get_source_info_by_name);
                LOAD_SYM(pa_context_get_source_info_list);
                LOAD_SYM(pa_context_get_source_info_by_index);
                LOAD_SYM(pa_context_get_sink_info_by_name);
                LOAD_SYM(pa_context_get_sink_info_list);
                LOAD_SYM(pa_context_get_sink_info_by_index);
                
                LOAD_SYM(pa_operation_unref);
                LOAD_SYM(pa_operation_get_state);
                LOAD_SYM(pa_proplist_gets);

                loaded = true;
                return true;
            }

            void close() {
                if (lib_handle) {
                    dlclose(lib_handle);
                    lib_handle = nullptr;
                }
                loaded = false;
            }

            bool isLoaded() const { return loaded; }

            pa_threaded_mainloop_new_t pa_threaded_mainloop_new;
            pa_threaded_mainloop_free_t pa_threaded_mainloop_free;
            pa_threaded_mainloop_start_t pa_threaded_mainloop_start;
            pa_threaded_mainloop_stop_t pa_threaded_mainloop_stop;
            pa_threaded_mainloop_get_api_t pa_threaded_mainloop_get_api;
            pa_threaded_mainloop_lock_t pa_threaded_mainloop_lock;
            pa_threaded_mainloop_unlock_t pa_threaded_mainloop_unlock;
            pa_threaded_mainloop_wait_t pa_threaded_mainloop_wait;
            pa_threaded_mainloop_signal_t pa_threaded_mainloop_signal;
            pa_threaded_mainloop_in_thread_t pa_threaded_mainloop_in_thread;

            pa_context_new_t pa_context_new;
            pa_context_unref_t pa_context_unref;
            pa_context_set_state_callback_t pa_context_set_state_callback;
            pa_context_set_subscribe_callback_t pa_context_set_subscribe_callback;
            pa_context_connect_t pa_context_connect;
            pa_context_disconnect_t pa_context_disconnect;
            pa_context_get_state_t pa_context_get_state;
            pa_context_subscribe_t pa_context_subscribe;

            pa_context_get_server_info_t pa_context_get_server_info;
            pa_context_get_source_info_by_name_t pa_context_get_source_info_by_name;
            pa_context_get_source_info_list_t pa_context_get_source_info_list;
            pa_context_get_source_info_by_index_t pa_context_get_source_info_by_index;
            pa_context_get_sink_info_by_name_t pa_context_get_sink_info_by_name;
            pa_context_get_sink_info_list_t pa_context_get_sink_info_list;
            pa_context_get_sink_info_by_index_t pa_context_get_sink_info_by_index;

            pa_operation_unref_t pa_operation_unref;
            pa_operation_get_state_t pa_operation_get_state;
            pa_proplist_gets_t pa_proplist_gets;

        private:
            PulseAudioLoader() : loaded(false), lib_handle(nullptr) {}
            bool loaded;
            void* lib_handle;
        };
    } // namespace avdev
} // namespace jni

#endif