package io.codemyriad.cassini.documents;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;

/** A separate test-APK UID with only framework dependencies; never part of the release app. */
public final class PermissionProbeActivity extends Activity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent result = new Intent().putExtra("uid", Process.myUid());
        try {
            android.database.Cursor cursor = getContentResolver().query(
                Uri.parse(getIntent().getStringExtra("uri")), null, null, null, null);
            if (cursor != null) cursor.close();
            result.putExtra("denied", false);
        } catch (SecurityException denied) {
            result.putExtra("denied", true).putExtra("reason", denied.getMessage());
        }
        setResult(RESULT_OK, result);
        finish();
    }
}
