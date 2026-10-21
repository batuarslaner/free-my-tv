package io.freemytv.fairplay;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.util.Log;

/**
 * Free My TV's FairPlay plugin, reached by the app's AirPlay receiver through a raw Binder (no AIDL). Only apps
 * signed with the same key may bind (the signature permission) or call (checked again on every call). Stateless.
 *
 * Protocol, descriptor "io.freemytv.fairplay.v1":
 *   FIRST_CALL_TRANSACTION + 0  VERSION                       → int api (1), String version
 *   FIRST_CALL_TRANSACTION + 1  FP_SETUP byte[] request       → int status (0 ok), byte[] reply
 *   FIRST_CALL_TRANSACTION + 2  DECRYPT_KEY byte[] message, byte[] ekey → int status, byte[16] key
 */
public class FairPlayService extends Service {
    static final String DESCRIPTOR = "io.freemytv.fairplay.v1";
    static final int VERSION = IBinder.FIRST_CALL_TRANSACTION, FP_SETUP = VERSION + 1, DECRYPT_KEY = VERSION + 2;
    static final int API = 1;

    private final Binder binder = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code < VERSION || code > DECRYPT_KEY) return super.onTransact(code, data, reply, flags);
            if (getPackageManager().checkSignatures(Binder.getCallingUid(), Process.myUid()) != PackageManager.SIGNATURE_MATCH) {
                Log.w("FreeMyTV", "fairplay: refused a caller signed with another key");
                return false;
            }
            data.enforceInterface(DESCRIPTOR);
            try {
                switch (code) {
                    case VERSION:
                        reply.writeInt(API);
                        reply.writeString("1.0");
                        return true;
                    case FP_SETUP: {
                        byte[] out = FairPlay.setup(data.createByteArray());
                        reply.writeInt(0);
                        reply.writeByteArray(out);
                        return true;
                    }
                    default: {
                        byte[] message = data.createByteArray();
                        byte[] out = FairPlay.decryptKey(message, data.createByteArray());
                        reply.writeInt(0);
                        reply.writeByteArray(out);
                        return true;
                    }
                }
            } catch (RuntimeException e) {
                Log.w("FreeMyTV", "fairplay: " + e.getMessage());
                reply.writeInt(1);
                reply.writeByteArray(null);
                return true;
            }
        }
    };

    @Override public IBinder onBind(Intent intent) {
        return binder;
    }
}
