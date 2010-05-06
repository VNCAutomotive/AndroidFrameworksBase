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

import java.io.IOException;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.IBinder;
import android.os.IRemoteControlClient;
import android.os.MemoryFile;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ParcelFileDescriptor;
import android.os.ServiceManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * This class allows suitably authorised applications to read from the
 * device screen and to inject input events into the device. This can
 * be used for taking screenshots, or for implementing VNC servers or
 * other remote control applications.
 *
 * <p>To start using remote control, call {@link #getRemoteControl()}
 * to create a RemoteControl object.</p>
 *
 * <p>To use this class your application must be signed by an
 * authorised key. The {@link #getRemoteControl()} function will throw
 * a {@link SecurityException} if your application is not authorised
 * to use the service. The remote control service is considered to be
 * too dangerous to expose via normal Android permissions as it would
 * allow a piece of malware to gain complete control over the device
 * and do anything that the user can do.</p>
 */

/* Client-side implementation of the remote control service.
 *
 * This code is considered "untrusted" for the purposes of security
 * checks, which should happen in RemoteControlService.java */

public class RemoteControl
{
    private static final String TAG = "RemoteControl";

    private IRemoteControl mService;

    /* Exceptions that we can throw */

    /**
     * Base class for all exceptions thrown by this service.
     */
    public static class RemoteControlException extends Exception {}

    /**
     * Thrown if getFrameBuffer() was unable to register the frame buffer.
     */
    public static class FrameBufferUnavailableException extends RemoteControlException {}

    /**
     * Thrown by grabScreen() if releaseFrameBuffer() is called while
     * a thread is waiting for a screen grab.
     */
    public static class DisconnectedException extends RemoteControlException {}

    /**
     * Callbacks from the RemoteControl object to its listener.
     *
     */
    public interface ICallbacks
    {
        /**
         * Notify the listener that the frame buffer orientation, flip
         * mode, etc has changed.
         *
         * @param info {@link RemoteControl.DeviceInfo} object
         * describing the device's current configuration. */
        public void deviceInfoChanged(DeviceInfo info);
    }

    /* This class serves two purposes: it receives callbacks from the
     * service, and its binder interface acts as a token to identify
     * us to the service. We have to use a static inner class here,
     * rather than the RemoteControl object, so that the service
     * doesn't hold a reference to the outer class. This allows the
     * outer class to be GCed if the application leaks a reference. */
    private static class CallbackHandler extends IRemoteControlClient.Stub {
        ICallbacks mListener;

        /**
         * Callback from the system server indicating device info change
         *
         * {@hide}
         */
        public void deviceInfoChanged(DeviceInfo info) {
            if(mListener != null) {
                mListener.deviceInfoChanged(info);
            }
        }
    }

    private CallbackHandler mCallbackHandler;

    /**
     * {@hide}
     */
    public static final int TRANSACTION_getFrameBuffer = IBinder.FIRST_CALL_TRANSACTION + 0x1000;

    /**
     * {@hide}
     */
    private RemoteControl(IRemoteControl service) {
        mService = service;

        mCallbackHandler = new CallbackHandler();

        try {
            mService.registerRemoteController(mCallbackHandler);
        } catch(RemoteException e) {
            Log.println(Log.ERROR, TAG, "RemoteControl: exception");
            e.printStackTrace();
        }
    }

    /**
     * Class representing device information such as screen
     * resolution, flip state etc
     */
    public static class DeviceInfo implements Parcelable
    {
        /**
         * Width of the frame buffer in pixels. This never changes,
         * even if the device is rotated - in this case,
         * displayOrientation will change, but the width and height
         * will still reflect the native framebuffer orientation.
         */
        public int fbWidth;

        /**
         * Height of the frame buffer in pixels. Never changes - see
         * fbWidth.
         */
        public int fbHeight;

        /**
         * Current frame buffer pixel format. Never changes. This uses
         * the same pixel format constants as other code, such as
         * {@link android.graphics.PixelFormat}.
         */
        public int fbPixelFormat;

        /**
         * Current orientation of the frame buffer, in multiples of a
         * 90 degree rotation.
         */
        public int displayOrientation;

        public DeviceInfo() {
        }

        /*
         * Parcelable interface methods
         */

        public static final Parcelable.Creator<DeviceInfo> CREATOR = new Parcelable.Creator<DeviceInfo>() {
            public DeviceInfo createFromParcel(Parcel in) {
                DeviceInfo di = new DeviceInfo();
                di.readFromParcel(in);
                return di;
            }

            public DeviceInfo[] newArray(int size) {
                return new DeviceInfo[size];
            }
        };

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(fbWidth);
            out.writeInt(fbHeight);
            out.writeInt(fbPixelFormat);
            out.writeInt(displayOrientation);
        }

        public void readFromParcel(Parcel in) {
            fbWidth = in.readInt();
            fbHeight = in.readInt();
            fbPixelFormat = in.readInt();
            displayOrientation = in.readInt();
        }
    }

    /**
     * Register an update listener.
     *
     * @param listener Object implementing the {@link ICallbacks}
     * interface which will receive notification of device
     * configuration changes.
     */
    public void setListener(ICallbacks listener) {
        mCallbackHandler.mListener = listener;
    }

    private static IRemoteControl getRemoteControlService() {
        IBinder b = ServiceManager.getService(Context.REMOTE_CONTROL_SERVICE);
        return IRemoteControl.Stub.asInterface(b);
    }

    /**
     * Request use of the remote control service. This function will
     * throw a {@link java.lang.SecurityException} if the caller is
     * not allowed to use remote control.
     *
     * @return A {@link RemoteControl} object
     *
     * @throws SecurityException Application is not authorised for
     * remote control.
     */
    public static RemoteControl getRemoteControl() throws SecurityException {
        IRemoteControl service = getRemoteControlService();
        if(service == null) {
            throw new NullPointerException("Couldn't find RemoteControlService - wrong OS version?");
        } else {
            return new RemoteControl(service);
        }
    }

    /**
     * Stop using the remote control service. Call this method when
     * the {@link RemoteControl} object is no longer required.
     */
    public void release() {
        try {
            mService.unregisterRemoteController(mCallbackHandler);
            mService = null;
        } catch(RemoteException e) {
            Log.println(Log.ERROR, TAG, "RemoteControl: exception");
            e.printStackTrace();
        }
    }

    /**
     * If the application leaks a reference to a RemoteControl object,
     * shut down the binder interface cleanly.
     *
     * Note that there is no guarantee that this object will ever be
     * garbage collected, so it's not certain that this will run in
     * the case of a leak. To close down the remote control service
     * cleanly, call {@link #release()}. */
    protected void finalize() throws Throwable {
        try {
            if(mService != null)
                release();
        } finally {
            super.finalize();
        }
    }

    /**
     * Return the current device parameters such as screen resolution,
     * flip mode etc. This information is also available via the
     * {@link RemoteControl.ICallbacks#deviceInfoChanged(DeviceInfo)}
     * method of the object passed to {@link #getRemoteControl()}.
     *
     * @return a {@link RemoteControl.DeviceInfo} object.
     */
    public DeviceInfo getDeviceInfo() {
        DeviceInfo di;

        try {
            di = mService.getDeviceInfo(mCallbackHandler);
            return di;
        } catch(RemoteException e) {
            Log.println(Log.ERROR, TAG, "RemoteControl: exception in getDeviceInfo");
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Ask for access to the contents of the screen.
     *
     * <p>The returned {@link android.os.MemoryFile MemoryFile} is
     * shared with the graphics subsystem. The {@link
     * #grabScreen(boolean)} method causes it to be updated with the
     * current frame buffer contents as a bitmap. </p>
     *
     * <p>You can only have one shared frame buffer at a time.</p>
     *
     * @param persistent If false, this function returns a single
     * snapshot of the current contents of the screen. Use this for
     * taking screenshots. If true, the contents of the returned
     * object need to be updated as the screen changes, by calling
     * {@link #grabScreen(boolean)}. In this case you will need to
     * call {@link #releaseFrameBuffer()} when you are finished
     * reading the screen. This case is intended for VNC servers or
     * other remote control applications.
     *
     * @return A {@link android.os.MemoryFile} object containing the
     * contents of the frame buffer.
     *
     * @throws FrameBufferUnavailableException An error occurred while
     * attempting to create the shared frame buffer.
     */
    public MemoryFile getFrameBuffer(boolean persistent) throws FrameBufferUnavailableException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();

        try {
            data.writeInterfaceToken("android.os.IRemoteControl");
            data.writeStrongBinder(mCallbackHandler.asBinder());
            mService.asBinder().transact(TRANSACTION_getFrameBuffer, data, reply, 0);

            //reply.readException(); - doesn't quite do what it says on the tin?

            int rv = reply.readInt();
            if(rv == 0) {
                ParcelFileDescriptor pfd = reply.readFileDescriptor();
                MemoryFile buff = new MemoryFile(pfd.getFileDescriptor(),
                                                 reply.readInt(), "r");


                Region ret = new Region();
                mService.grabScreen(mCallbackHandler, false, ret);

                if(!persistent) {
                    mService.releaseFrameBuffer(mCallbackHandler);
                }

                return buff;
            } else {
                throw new FrameBufferUnavailableException();
            }
        } catch(RemoteException e) {
            Log.println(Log.ERROR, TAG, "RemoteControl: exception in getFrameBuffer");
            e.printStackTrace();
            throw new FrameBufferUnavailableException();
        } catch(IOException e) {
            Log.println(Log.ERROR, TAG, "RemoteControl: IOexception in getFrameBuffer");
            e.printStackTrace();
            throw new FrameBufferUnavailableException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * Release the frame buffer.
     *
     * If you have obtained access to the frame buffer using {@link
     * #getFrameBuffer(boolean)} with persistent set to true, you can
     * release your claim to the frame buffer using this function.
     *
     * If another thread is currently blocked in {@link
     * #grabScreen(boolean)} it will exit with a {@link
     * DisconnectedException}. */
    public void releaseFrameBuffer() {
        try {
            mService.releaseFrameBuffer(mCallbackHandler);
        } catch(RemoteException e) {
            Log.println(Log.ERROR, TAG, "RemoteControl: exception in releaseFrameBuffer");
            e.printStackTrace();
        }
    }

    /**
     * Grab a screen image into the shared frame buffer, if it was
     * registered with 'persistent' set to true.
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
     * @throws DisconnectedException Another thread called {@link
     * #releaseFrameBuffer} while this function was waiting for a
     * screen update. */
    public Region grabScreen(boolean incremental) throws DisconnectedException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        int rv = 0;
        Region ret = new Region();
        try {
            rv = mService.grabScreen(mCallbackHandler, incremental, ret);

        } catch(RemoteException e) {
            Log.println(Log.ERROR, TAG, "RemoteControl: exception in grabScreen");
            e.printStackTrace();
            rv = -1;
        }
        if(rv != 0) {
            Log.println(Log.ERROR, TAG, "RemoteControl: rv=" + rv);
            throw new DisconnectedException();
        }
        return ret;
    }

    /**
     * Inject a keyboard event into the system.
     *
     * @param ev Event to inject
     */
    public void injectKeyEvent(KeyEvent ev) {
        try {
            mService.injectKeyEvent(mCallbackHandler, ev);
        } catch(RemoteException e) {
            Log.println(Log.ERROR, TAG, "RemoteControl: exception in injectKeyEvent");
            e.printStackTrace();
        }
    }

    /**
     * Inject a pointer event into the system.
     *
     * @param ev Event to inject
     */
    public void injectMotionEvent(MotionEvent ev) {
        try {
            mService.injectMotionEvent(mCallbackHandler, ev);
        } catch(RemoteException e) {
            Log.println(Log.ERROR, TAG, "RemoteControl: exception in injectMotionEvent");
            e.printStackTrace();
        }
    }

    /* Functions for adding and removing authorised applications */

    /**
     * Add an application's signing key to the list of authorised keys.
     *
     * Requires the android.permission.AUTHORISE_REMOTE_CONTROL permission.
     *
     * @param appName Name of the application package whose key is to
     * be authorised, eg "com.realvnc.androidsampleserver". This is
     * the same as the package name used in AndroidManifest.xml.
     *
     * @throws SecurityException if the caller is not authorised to
     * add and remove keys to/from the list.
     *
     * {@hide} */
    public static void addAuthorisedApplication(String appName) throws SecurityException {
        IRemoteControl service = getRemoteControlService();
        try {
            service.addAuthorisedApplication(appName);
        } catch(RemoteException e) {
            Log.println(Log.ERROR, TAG, "RemoteControl: exception");
            e.printStackTrace();
        }
    }

    /**
     * Remove an application's signing key from the list of authorised keys.
     *
     * Requires the android.permission.AUTHORISE_REMOTE_CONTROL permission.
     *
     * @param appName Name of the application package whose key is to
     * be deauthorised, eg "com.realvnc.androidsampleserver". This is
     * the same as the package name used in AndroidManifest.xml.
     *
     * @throws SecurityException if the caller is not authorised to
     * add and remove keys to/from the list.
     *
     * {@hide} */
    public static void removeAuthorisedApplication(String appName) throws SecurityException {
        IRemoteControl service = getRemoteControlService();
        try {
            service.removeAuthorisedApplication(appName);
        } catch(RemoteException e) {
            Log.println(Log.ERROR, TAG, "RemoteControl: exception");
            e.printStackTrace();
        }
    }
}
