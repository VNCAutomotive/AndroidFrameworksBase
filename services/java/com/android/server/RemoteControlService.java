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

package com.android.server;

import com.android.internal.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.lang.IllegalStateException;
import java.util.HashMap;

import android.app.ActivityThread;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteControl;
import android.os.IRemoteControlClient;
import android.os.MemoryFile;
import android.os.Parcel;
import android.os.RemoteControl;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.IRotationWatcher;
import android.view.IWindowManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceSession;
import android.view.WindowManager;
import android.view.WindowManagerImpl;

/* Server-side implementation of the remote control service.
 *
 * This code runs in the system server rather than the client process,
 * so it is responsible for enforcing all security restrictions. */

class RemoteControlService
    extends IRemoteControl.Stub
    implements IRotationWatcher, IBinder.DeathRecipient, DialogInterface.OnClickListener
{
    private static final String TAG = "RemoteControlService";

    private static final String DATABASE_NAME = "remote-control.db";
    private static final int DATABASE_VERSION = 1;

    private static final String TABLE_ALLOWED_KEYS = "allowedkeys";
    private static final String ALLOWED_KEYS_ID = "_id";
    private static final String ALLOWED_KEYS_KEY = "key";

    private static final String FACTORY_KEYS = "/system/etc/authorised_remote_control_keys";

    private Display mDisplay;
    private PixelFormat mFormat;

    private IWindowManager mWindowManager;

    private Context mContext;

    private AlertDialog mDlg;

    private static class DatabaseHelper extends SQLiteOpenHelper {

        public DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            Log.i(TAG, "created database " + DATABASE_NAME);
            db.execSQL("CREATE TABLE " + TABLE_ALLOWED_KEYS + " ( "
                       + ALLOWED_KEYS_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                       + ALLOWED_KEYS_KEY + " TEXT NOT NULL "
                       + ")");

            BufferedReader keyReader = null;

            try {
                keyReader = new BufferedReader(new FileReader(FACTORY_KEYS));
                String line;

                while((line = keyReader.readLine()) != null) {
                    Log.i(TAG, "Preload key: [" + line.substring(0, Math.min(20, line.length())) + "...]");

                    ContentValues newRow = new ContentValues();

                    newRow.put(ALLOWED_KEYS_KEY, line);

                    long row = db.insert(TABLE_ALLOWED_KEYS, null, newRow);
                    if(row < 0) {
                        Log.e(TAG, "insert() returned " + row);
                    }
                }

            } catch(FileNotFoundException e) {

                /* The file wasn't found - we don't really mind if
                 * this happens, it just means there aren't any
                 * pre-authorised keys. */

            } catch(IOException e) {
                Log.println(Log.ERROR, TAG, "onCreate(): exception");
                e.printStackTrace();
            } finally {
                try {
                    if(keyReader != null)
                        keyReader.close();
                } catch(IOException e) {
                    Log.println(Log.ERROR, TAG, "onCreate(): exception");
                    e.printStackTrace();
                }
            }

        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.e(TAG, "upgrade database from version " + oldVersion + " to version " + newVersion);

            /* nothing here now, maybe there will be in the future */
        }

        @Override
        public void onOpen(SQLiteDatabase db) {
            Log.i(TAG, "opened database " + DATABASE_NAME);
        }
    }

    private final DatabaseHelper mDbHelper;

    /* Class representing a client connected to the remote control
     * service. */
    private static class RemoteControlClient
    {
        public SurfaceSession mSurfaceSession;
        public MemoryFile mFrameBuffer;

        private RemoteControlClient() {
            mFrameBuffer = null;
            mSurfaceSession = new SurfaceSession();
        }

        private void unregister() {
            if(mFrameBuffer != null) {
                Log.println(Log.ERROR, TAG, "Client didn't release frame buffer");
            }
            Log.println(Log.INFO, TAG, "Releasing client");
            mFrameBuffer = null;

            mSurfaceSession.kill();
            mSurfaceSession = null;
        }
    };

    private HashMap<IBinder, RemoteControlClient> mClients;

    public RemoteControlService(Context ctx) {

        mContext = ctx;

        mDbHelper = new DatabaseHelper(mContext);

        mClients = new HashMap<IBinder, RemoteControlClient>();

        mDisplay = WindowManagerImpl.getDefault().getDefaultDisplay();
        mFormat = new PixelFormat();
        PixelFormat.getPixelFormatInfo(mDisplay.getPixelFormat(), mFormat);

        IBinder wmbinder = ServiceManager.getService("window");
        mWindowManager = IWindowManager.Stub.asInterface(wmbinder);

        try {
            mWindowManager.watchRotation(this);
        } catch(Exception e) {
            Log.println(Log.ERROR, TAG, "RemoteControl: exception");
            e.printStackTrace();
        }
    }

    private RemoteControl.DeviceInfo buildDeviceInfo() {
        RemoteControl.DeviceInfo di = new RemoteControl.DeviceInfo();

        di.fbPixelFormat = mDisplay.getPixelFormat();
        di.displayOrientation = mDisplay.getOrientation();

        if((di.displayOrientation & 1) == 0) {
            di.fbWidth = mDisplay.getWidth();
            di.fbHeight = mDisplay.getHeight();
        } else {
            di.fbHeight = mDisplay.getWidth();
            di.fbWidth = mDisplay.getHeight();
        }

        return di;
    }

    /* Callback from the window manager service when the screen rotation changes */
    public void onRotationChanged(int rotation) {
        RemoteControl.DeviceInfo di = buildDeviceInfo();

        for(IBinder clientId : mClients.keySet()) {
            IRemoteControlClient obj;

            obj = IRemoteControlClient.Stub.asInterface(clientId);

            try {
                obj.deviceInfoChanged(di);
            } catch(Exception e) {
                Log.println(Log.ERROR, TAG, "RemoteControl: exception");
                e.printStackTrace();
            }
        }
    }

    /* Check that we're dealing with a correctly registered client.
     *
     * This is important for security. Every public entry point should
     * call this function to ensure that the client passed the
     * security check in registerRemoteController(). */
    private RemoteControlClient checkClient(IRemoteControlClient obj) {
        IBinder clientId = obj.asBinder();
        RemoteControlClient client = mClients.get(clientId);

        if(client == null) {
            /* client is not registered */
            Log.println(Log.ERROR, TAG, "Client " + clientId + " is not registered");
            throw new IllegalStateException("Client " + clientId + " is not registered");
        }

        return client;
    }

    public void binderDied() {
        /* Unfortunately the framework doesn't tell us _which_ binder
         * died. So we have to check all of them. */

        for(IBinder clientId : mClients.keySet()) {
            if(!clientId.isBinderAlive()) {
                Log.println(Log.INFO, TAG, "binderDied: nuking " + clientId);
                unregisterRemoteController(IRemoteControlClient.Stub.asInterface(clientId));
                mClients.remove(clientId);
            }
        }
    }

    /* Add 's' to the database of authorised signing keys. This
     * function assumes that security checks have already been carried
     * out and the caller is authorised to do this. */
    private void addAuthorisedSignature(Signature s) {
        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        ContentValues newRow = new ContentValues();

        newRow.put(ALLOWED_KEYS_KEY, s.toCharsString());

        long row = db.insert(TABLE_ALLOWED_KEYS, null, newRow);
        if(row < 0) {
            Log.e(TAG, "insert() returned " + row);
        }
    }

    /* Remove 's' from the database of authorised signing keys. This
     * function assumes that security checks have already been carried
     * out and the caller is authorised to do this. */
    private void removeAuthorisedSignature(Signature s) {
        SQLiteDatabase db = mDbHelper.getWritableDatabase();

        db.delete(TABLE_ALLOWED_KEYS, ALLOWED_KEYS_KEY + " = ?", new String[] {s.toCharsString()});
    }

    /* Return true if this signing key is authorised to use the remote
     * control service. */

    private boolean isAuthorisedSignature(Signature s) {
        SQLiteDatabase db = mDbHelper.getReadableDatabase();
        boolean result;

        Cursor cursor = db.query(TABLE_ALLOWED_KEYS,
                                 new String[]{ ALLOWED_KEYS_KEY },
                                 ALLOWED_KEYS_KEY + "=?",
                                 new String[]{ s.toCharsString() },
                                 null, null, null);
        result = cursor.getCount() > 0;
        cursor.close();
        return result;
    }

    /* Verify that the caller is allowed to use the remote control
     * service. Throw a SecurityException if not. */
    private void enforcePermission() throws SecurityException {

        if(Settings.Secure.getInt(mContext.getContentResolver(),
                                  Settings.Secure.ALLOW_UNCHECKED_REMOTE_CONTROL, 0) == 1)
            return;

        int uid = getCallingUid();
        PackageManager pm = mContext.getPackageManager();
        String name = pm.getNameForUid(uid);
        String[] pkgs = pm.getPackagesForUid(uid);

        for(String p : pkgs) {
            try {
                PackageInfo info = pm.getPackageInfo(p, PackageManager.GET_SIGNATURES);
                for(Signature s : info.signatures) {
                    if(isAuthorisedSignature(s))
                        return;
                }
            } catch(PackageManager.NameNotFoundException e) {
                /* This shouldn't happen, because we're asking the
                 * package manager about a name it gave us */
                Log.println(Log.ERROR, TAG, "name " + p + " not found");
            }
        }

        /* We reach this point if the calling app doesn't have
         * permission to use the RemoteControl service. Log the
         * failure, display a dialog, and raise a SecurityException.
         *
         * It's important that the OS displays a dialog here, rather
         * than relying on the app to do so.
         *
         * Opportunistic malware writers might take a gamble, since a
         * few phones might have the "Enable remote control by any
         * app" option turned on. A "Trojan horse" package containing
         * hidden malware could try to access the remote control
         * service and continue as normal, without doing the malicious
         * stuff, if it gets a SecurityException. If it gets installed
         * on enough phones, the author would have a chance of ending
         * up in control of some of them.
         *
         * Displaying a prominent warning here prevents this attack,
         * since if an author tries to do this it'll be immediately
         * obvious to everyone who installs the app. */

        showDialog(name);

        Log.println(Log.ERROR, TAG,
                    name + " not authorised for remote control");

        throw new SecurityException("Not authorised for remote control");
    }

    /* Binder API */

    public synchronized void registerRemoteController(IRemoteControlClient obj) throws SecurityException {
        IBinder clientId = obj.asBinder();

        /* Perform security checks here */
        enforcePermission();

        if(mClients.containsKey(clientId)) {
            Log.println(Log.ERROR, TAG, "Already registered: " + clientId);
            throw new IllegalStateException("Already registered: " + clientId);
        } else {
            RemoteControlClient client = new RemoteControlClient();
            mClients.put(clientId, client);

            try {
                clientId.linkToDeath(this, 0);
            } catch(Exception e) {
                Log.println(Log.ERROR, TAG, "RemoteControl: exception");
                e.printStackTrace();
            }
        }
    }

    public synchronized void unregisterRemoteController(IRemoteControlClient obj) {
        IBinder clientId = obj.asBinder();
        RemoteControlClient client = checkClient(obj);

        client.unregister();
        mClients.remove(clientId);
    }

    public RemoteControl.DeviceInfo getDeviceInfo(IRemoteControlClient obj) {
        RemoteControlClient client = checkClient(obj);
        return buildDeviceInfo();
    }

    public MemoryFile getFrameBuffer(IRemoteControlClient obj) throws IOException {
        RemoteControlClient client = checkClient(obj);

        int size = mDisplay.getWidth() * mDisplay.getHeight() * mFormat.bytesPerPixel;
        MemoryFile fb = new MemoryFile(null, size);
        client.mFrameBuffer = fb;

        try {
            long previousIdentity = clearCallingIdentity();
            int rv = client.mSurfaceSession.registerGrabBuffer(0, fb.getFileDescriptor());
            restoreCallingIdentity(previousIdentity);

            if(rv != 0) {
                Log.println(Log.ERROR, TAG, "RemoteControl: registerGrabBuffer: " + rv);
                client.mFrameBuffer = null;
                fb = null;
            }
        } catch(Exception e) {
            Log.println(Log.ERROR, TAG, "RemoteControl: exception");
            e.printStackTrace();
        }

        return fb;
    }

    public void releaseFrameBuffer(IRemoteControlClient obj) {
        RemoteControlClient client = checkClient(obj);

        try {
            long previousIdentity = clearCallingIdentity();
            int rv = client.mSurfaceSession.unregisterGrabBuffer(0);
            restoreCallingIdentity(previousIdentity);

            client.mFrameBuffer = null;

            if(rv != 0) {
                Log.println(Log.ERROR, TAG, "RemoteControl: unregisterGrabBuffer: " + rv);
            }

        } catch(Exception e) {
            Log.println(Log.ERROR, TAG, "RemoteControl: exception");
            e.printStackTrace();
        }
    }

    public int grabScreen(IRemoteControlClient obj, boolean incremental, Region updatedRegion) {
        RemoteControlClient client = checkClient(obj);
        int rv = 0;

        try {
            long previousIdentity = clearCallingIdentity();
            rv = client.mSurfaceSession.grabScreen(incremental, updatedRegion);
            restoreCallingIdentity(previousIdentity);

        } catch(Exception e) {
            Log.println(Log.ERROR, TAG, "RemoteControl: exception");
            e.printStackTrace();
        }

        return rv;
    }

    public void injectKeyEvent(IRemoteControlClient obj, KeyEvent event) {
        RemoteControlClient client = checkClient(obj);

        try {
            long previousIdentity = clearCallingIdentity();
            mWindowManager.injectKeyEvent(event, true);
            restoreCallingIdentity(previousIdentity);
        } catch(Exception e) {
            Log.println(Log.ERROR, TAG, "RemoteControl: exception");
            e.printStackTrace();
        }
    }

    public void injectMotionEvent(IRemoteControlClient obj, MotionEvent event) {
        RemoteControlClient client = checkClient(obj);

        try {
            long previousIdentity = clearCallingIdentity();
            mWindowManager.injectPointerEvent(event, false);
            restoreCallingIdentity(previousIdentity);
        } catch(Exception e) {
            Log.println(Log.ERROR, TAG, "RemoteControl: exception");
            e.printStackTrace();
        }
    }

    /* Custom Binder transaction implementation for getFrameBuffer(),
     * because AIDL doesn't do file descriptor passing. */

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        switch (code)
        {
        case RemoteControl.TRANSACTION_getFrameBuffer: {
            data.enforceInterface("android.os.IRemoteControl");
            IBinder b = data.readStrongBinder();
            IRemoteControlClient obj = IRemoteControlClient.Stub.asInterface(b);
            try {
                MemoryFile fb = this.getFrameBuffer(obj);
                if(fb != null) {
                    reply.writeInt(0);
                    reply.writeFileDescriptor(fb.getFileDescriptor());
                    reply.writeInt(fb.length());
                } else {
                    reply.writeInt(-1);
                }
                reply.writeNoException();
            } catch(Exception e) {
                Log.println(Log.ERROR, TAG, "RemoteControl: exception");
                e.printStackTrace();
                Log.println(Log.ERROR, TAG, "RemoteControl: writing exception to reply");
                reply.writeException(e);
            }
            return true;
        }
        }
        return super.onTransact(code, data, reply, flags);
    }

    /**
     * An object which allows us to make asynchronous callbacks into
     * the UI thread from other threads. We will use this to display
     * the security warning dialog.
     */
    private final Handler mHandler = new Handler();

    /**
     * Display a security warning dialog.
     *
     * This is purely for the user's benefit - we don't take any
     * action when the dialog is closed, other than hiding it. */
    private void showDialog(final String name) {
        mHandler.post(new Runnable() {
                public void run() {
                    mDlg = new AlertDialog.Builder(mContext)
                        .setMessage(mContext.getResources().getString(R.string.rcerr_body, name))
                        .setTitle(R.string.rcerr_title)
                        .setIcon(R.drawable.ic_dialog_alert)
                        .setNegativeButton(R.string.rcerr_button, RemoteControlService.this)
                        .create();
                    mDlg.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
                    mDlg.show();
                }
            });
    }

    public void onClick(DialogInterface dlg, int which) {
        mDlg.dismiss();
        mDlg = null;
    }

    /* Functions for adding and removing authorised applications */

    public void addAuthorisedApplication(String appName) {
        mContext.enforceCallingPermission("android.permission.AUTHORISE_REMOTE_CONTROL",
                                          "Not authorised to allow remote control");

        Log.e(TAG, "add: package is " + appName);

        PackageManager pm = mContext.getPackageManager();
        try {
            PackageInfo info = pm.getPackageInfo(appName, PackageManager.GET_SIGNATURES);

            for(Signature s : info.signatures) {
                addAuthorisedSignature(s);
            }
        } catch(PackageManager.NameNotFoundException e) {
            Log.e(TAG, "package " + appName + " not found");
        }
    }

    public void removeAuthorisedApplication(String appName) {
        mContext.enforceCallingPermission("android.permission.AUTHORISE_REMOTE_CONTROL",
                                          "Not authorised to disallow remote control");

        Log.e(TAG, "remove: package is " + appName);

        PackageManager pm = mContext.getPackageManager();
        try {
            PackageInfo info = pm.getPackageInfo(appName, PackageManager.GET_SIGNATURES);
            for(Signature s : info.signatures) {
                removeAuthorisedSignature(s);
            }
        } catch(PackageManager.NameNotFoundException e) {
            Log.e(TAG, "package " + appName + " not found");
        }
    }
}
