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

package dev.onvoid.webrtc.media;

import dev.onvoid.webrtc.internal.NativeObject;

/**
 * Base class for sources. A MediaStreamTrack has an underlying source that
 * provides media. A source can be shared by multiple tracks.
 *
 * @author Alex Andres
 */
public abstract class MediaSource extends NativeObject {

    public native State getState();

    public enum State {

        INITIALIZING,

        LIVE,

        ENDED,

        MUTED;

    }

}
