package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import io.freemytv.airplay.FairPlay;

import java.io.IOException;

/**
 * FairPlay through the GPL-3.0 plugin's service (fairplay/FairPlayService): a raw Binder with descriptor
 * "io.freemytv.fairplay.v1". Calls block, so never on the main thread. If the plugin's process dies, the next call
 * waits for Android to bring it back.
 */
final class FairPlayClient implements FairPlay {
    private static final String DESCRIPTOR = "io.freemytv.fairplay.v1";
    private static final int VERSION = IBinder.FIRST_CALL_TRANSACTION, FP_SETUP = VERSION + 1, DECRYPT_KEY = VERSION + 2;
    private static final long WAIT_MS = 5_000;

    private final Context context;
    private IBinder binder;
    private boolean bound, closed;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (FairPlayClient.this) {
                binder = service;
                FairPlayClient.this.notifyAll();
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            synchronized (FairPlayClient.this) {
                binder = null;
            }
            AppLog.w("airplay: the FairPlay plugin's process died");
        }

        /** The plugin was updated or reinstalled: Android won't reconnect this binding, the next call binds again. */
        @Override public void onBindingDied(ComponentName name) {
            AppLog.w("airplay: the FairPlay plugin was replaced");
            unbind();
        }

        @Override public void onNullBinding(ComponentName name) {
            unbind();
        }
    };

    FairPlayClient(Context context) {
        this.context = context.getApplicationContext();
    }

    /** The plugin's API version (1), after binding. */
    int version() throws IOException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            call(VERSION, data, reply);
            return reply.readInt();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override public byte[] setup(byte[] request) throws IOException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeByteArray(request);
            call(FP_SETUP, data, reply);
            return result(reply);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override public byte[] decryptKey(byte[] keyMessage, byte[] ekey) throws IOException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeByteArray(keyMessage);
            data.writeByteArray(ekey);
            call(DECRYPT_KEY, data, reply);
            return result(reply);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    synchronized void close() {
        closed = true;
        unbind();
    }

    private synchronized void unbind() {
        if (bound) {
            try {
                context.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
            }
        }
        bound = false;
        binder = null;
        notifyAll();
    }

    private static byte[] result(Parcel reply) throws IOException {
        int status = reply.readInt();
        byte[] out = reply.createByteArray();
        if (status != 0 || out == null) throw new IOException("the FairPlay plugin refused the message");
        return out;
    }

    private void call(int code, Parcel data, Parcel reply) throws IOException {
        try {
            if (!binder().transact(code, data, reply, 0)) throw new IOException("the FairPlay plugin refused the call");
        } catch (RemoteException e) {
            throw new IOException("the FairPlay plugin is gone: " + e.getMessage());
        }
    }

    private synchronized IBinder binder() throws IOException {
        if (closed) throw new IOException("AirPlay is stopping");
        if (!bound) {
            Intent intent = new Intent().setComponent(new ComponentName(AirPlay.PLUGIN, AirPlay.PLUGIN + ".FairPlayService"));
            try {
                bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            } catch (SecurityException e) {
                throw new IOException("not allowed to bind the FairPlay plugin: " + e.getMessage());
            }
            if (!bound) throw new IOException("the FairPlay plugin is not installed");
        }
        long until = System.currentTimeMillis() + WAIT_MS;
        while (binder == null || !binder.isBinderAlive()) {
            if (closed || !bound) throw new IOException("the FairPlay plugin went away");
            long left = until - System.currentTimeMillis();
            if (left <= 0) throw new IOException("the FairPlay plugin didn't answer");
            try {
                wait(left);
            } catch (InterruptedException e) {
                throw new IOException("interrupted");
            }
        }
        return binder;
    }
}
