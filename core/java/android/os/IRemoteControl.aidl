/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.os;

import android.graphics.Region;
import android.os.IRemoteControlClient;
import android.os.RemoteControl;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * {@hide}
 */
interface IRemoteControl
{
    void registerRemoteController(IRemoteControlClient obj);
    void unregisterRemoteController(IRemoteControlClient obj);

    RemoteControl.DeviceInfo getDeviceInfo(IRemoteControlClient obj);

    void injectKeyEvent(IRemoteControlClient obj, in KeyEvent event);
    void injectMotionEvent(IRemoteControlClient obj, in MotionEvent event);

    /* Note that getFrameBuffer() is not defined here as
     * RemoteControl.java performs the related binder transaction
     * manually. If it was defined here it would look more or less
     * like this:
     *
     * import android.os.MemoryFile;
     *
     * MemoryFile getFrameBuffer(IRemoteControlClient obj); */

    void releaseFrameBuffer(IRemoteControlClient obj);

    int grabScreen(IRemoteControlClient obj, boolean incremental, out Region changed);

    void addAuthorisedApplication(String appName);
    void removeAuthorisedApplication(String appName);
}
