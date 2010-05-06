/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.view;

import android.graphics.Region;
import java.io.FileDescriptor;

/**
 * An instance of this class represents a connection to the surface
 * flinger, in which you can create one or more Surface instances that will
 * be composited to the screen.
 * {@hide}
 */
public class SurfaceSession {
    /** Create a new connection with the surface flinger. */
    public SurfaceSession() {
        init();
    }

    /** Forcibly detach native resources associated with this object.
     *  Unlike destroy(), after this call any surfaces that were created
     *  from the session will no longer work. The session itself is destroyed.
     */
    public native void kill();

    /**
     * Register a screen-grab buffer.
     *
     * This buffer is used when requesting screenshots from the SurfaceFlinger.
     *
     * @param display Display number to grab screenshots from
     * @param fd An mmap()able file (typically an ashmem region) which
     * will hold images of the screen
     * @return Error code or zero
     * {@hide}
     */
    public native int registerGrabBuffer(int display, FileDescriptor fd);

    /**
     * Unregister screen-grab buffer.
     *
     * This disconnects the SurfaceFlinger from a buffer previously
     * registered using registerGrabBuffer().
     *
     * @param display Display number whose buffer is to be released
     * {@hide}
     */
    public native int unregisterGrabBuffer(int display);

    /**
     * Grab a screen image into the grab buffer.
     *
     * This function can either grab the whole screen at once, or it
     * can wait for a region to be updated and only grab the changed
     * areas.
     *
     * In both cases, the returned {@link android.graphics.Region}
     * describes which areas of the frame buffer have been updated.
     *
     * @param incremental If false, this call captures the entire
     * screen and returns immediately. If true, the function only
     * updates any areas of the screen which have changed since the
     * previous call, blocking if necessary until a change occurs.
     *
     * @param updatedRegion Pass an empty region. On successful
     * return, contains the region of the buffer which got updated by
     * this call. Can be null if not required.
     *
     * @return Error code or zero
     * {@hide}
     */
    public native int grabScreen(boolean incremental, Region updatedRegion);


    /* no user serviceable parts here ... */
    @Override
    protected void finalize() throws Throwable {
        destroy();
    }
    
    private native void init();
    private native void destroy();
    
    private int mClient;
}

