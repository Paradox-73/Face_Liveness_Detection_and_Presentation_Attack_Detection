package io.mosip.registration.livenessmanager.device;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import java.io.File;

/**
 * Tells {@link AndroidKeystoreSigner} where the in-app provisioning files are exchanged, when the app process
 * starts: {@code liveness/} in the app-specific external files folder. Does no other work and serves no data.
 *
 * <p>A content provider is the only component that Android creates before the first capture in every process
 * without changes to ARC's application class.
 */
public final class DeviceProvisioningInitializer extends ContentProvider {

    /** Sub-folder of the app-specific external files folder. */
    public static final String FOLDER = "liveness";

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context != null) {
            Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            AndroidKeystoreSigner.useRequestFolder(() -> requestFolder(app));
        }
        return true;
    }

    /**
     * Returns the exchange folder of an app.
     *
     * @param context any context of the app
     * @return {@code Android/data/<package>/files/liveness}, or {@code null} while external storage is not
     *         available
     */
    static File requestFolder(Context context) {
        File base = context.getExternalFilesDir(null);
        return base == null ? null : new File(base, FOLDER);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
