package com.launchpoint.wavdrop.ui.screen.wrapped;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/**
 * P5-WR-VID-02C test receiver. It lives in the test APK (a different package, uid and process from the app) and is
 * written in plain Java on purpose: that process has no Kotlin runtime. It reads the shared URI, then tries two things
 * it must NOT be able to do, and returns every result to the instrumentation test as an activity result:
 *  - open the granted URI for writing (must be refused),
 *  - read a sibling URI that was never granted (must be refused).
 */
public class WrappedVideoReceiverActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StringBuilder out = new StringBuilder();
        try {
            Uri uri = getIntent().getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            out.append("type=").append(getIntent().getType()).append('\n');
            out.append("scheme=").append(uri == null ? null : uri.getScheme()).append('\n');
            out.append("grantFlag=").append((getIntent().getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0).append('\n');
            if (uri != null) {
                try {
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    long total = 0;
                    try (InputStream s = getContentResolver().openInputStream(uri)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = s.read(buf)) >= 0) { md.update(buf, 0, n); total += n; }
                    }
                    StringBuilder hex = new StringBuilder();
                    for (byte b : md.digest()) hex.append(String.format("%02x", b));
                    out.append("readBytes=").append(total).append('\n');
                    out.append("readSha256=").append(hex).append('\n');
                } catch (Exception e) {
                    out.append("readError=").append(e.getClass().getSimpleName()).append('\n');
                }
                try (OutputStream o = getContentResolver().openOutputStream(uri, "w")) {
                    o.write(1);
                    out.append("write=ALLOWED\n");
                } catch (Exception e) {
                    out.append("write=").append(e.getClass().getSimpleName()).append('\n');
                }
                String u = uri.toString();
                Uri sibling = Uri.parse(u.substring(0, u.lastIndexOf('/')) + "/wavdrop_wrapped_ungranted.mp4");
                try (InputStream s = getContentResolver().openInputStream(sibling)) {
                    s.read();
                    out.append("ungrantedRead=ALLOWED\n");
                } catch (Exception e) {
                    out.append("ungrantedRead=").append(e.getClass().getSimpleName()).append('\n');
                }
            }
        } catch (Exception e) {
            out.append("fatal=").append(e.getClass().getSimpleName()).append(':').append(e.getMessage()).append('\n');
        }
        setResult(RESULT_OK, new Intent().putExtra("report", out.toString()));
        finish();
    }
}
