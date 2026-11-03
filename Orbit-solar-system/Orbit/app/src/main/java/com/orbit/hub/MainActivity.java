package com.orbit.hub;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {

    private WebView web;
    private static final int REQ_HOME_ROLE = 7;
    private static final int REQ_PROJECTION = 42;
    private String pendingCode;
    private String pendingName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "BombaNative");
        ShareService.sink = status -> {
            if (web != null) {
                final String js = "window.bombaShareStatus&&window.bombaShareStatus("
                        + status.toString() + ")";
                web.post(() -> web.evaluateJavascript(js, null));
            }
        };
        if (savedInstanceState == null) {
            web.loadUrl("file:///android_asset/index.html");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJECTION) return;
        if (resultCode == RESULT_OK && data != null && pendingCode != null) {
            Intent svc = new Intent(this, ShareService.class);
            svc.putExtra(ShareService.EXTRA_RESULT_CODE, resultCode);
            svc.putExtra(ShareService.EXTRA_RESULT_DATA, data);
            svc.putExtra(ShareService.EXTRA_CODE, pendingCode);
            svc.putExtra(ShareService.EXTRA_NAME, pendingName);
            startForegroundService(svc);
        } else {
            try {
                JSONObject s = new JSONObject();
                s.put("state", "error");
                s.put("message", "Sharing was cancelled");
                ShareService.lastStatusJson = s.toString();
                if (ShareService.sink != null) ShareService.sink.onShareStatus(s);
            } catch (Exception ignored) {
            }
        }
        pendingCode = null;
        pendingName = null;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Home button pressed while Orbit is the home app: reset to the ball.
        if (web != null) {
            web.post(() -> web.evaluateJavascript("window.bombaHome&&window.bombaHome()", null));
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private boolean isDefaultHome() {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo info = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return info != null && info.activityInfo != null
                && getPackageName().equals(info.activityInfo.packageName);
    }

    private void openHomeSwitch() {
        // Not the home app yet: the system asks with its own dialog (Android 10+).
        if (!isDefaultHome() && Build.VERSION.SDK_INT >= 29) {
            RoleManager rm = (RoleManager) getSystemService(ROLE_SERVICE);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)
                    && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME_ROLE);
                return;
            }
        }
        // Already home (switch back) or older Android: system home settings list.
        try {
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    /** Bridge the page's existing UI expects, under its original name. */
    public class Bridge {

        @JavascriptInterface
        public void requestApps() {
            new Thread(() -> {
                JSONArray arr = new JSONArray();
                try {
                    PackageManager pm = getPackageManager();
                    Intent main = new Intent(Intent.ACTION_MAIN);
                    main.addCategory(Intent.CATEGORY_LAUNCHER);
                    List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
                    List<ResolveInfo> sorted = new ArrayList<>(apps);
                    Collections.sort(sorted, new ResolveInfo.DisplayNameComparator(pm));
                    for (ResolveInfo ri : sorted) {
                        JSONObject o = new JSONObject();
                        o.put("label", String.valueOf(ri.loadLabel(pm)));
                        o.put("pkg", ri.activityInfo.packageName);
                        o.put("icon", iconDataUri(pm, ri));
                        arr.put(o);
                    }
                } catch (Exception ignored) {
                }
                final String js = "window.bombaApps&&window.bombaApps(" + arr.toString() + ")";
                web.post(() -> web.evaluateJavascript(js, null));
            }).start();
        }

        private String iconDataUri(PackageManager pm, ResolveInfo ri) {
            try {
                Drawable d = ri.loadIcon(pm);
                int size = 96;
                Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bmp);
                d.setBounds(0, 0, size, size);
                d.draw(canvas);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void launch(final String pkg) {
            runOnUiThread(() -> {
                try {
                    Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                    if (i != null) {
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    }
                } catch (Exception ignored) {
                }
            });
        }

        @JavascriptInterface
        public void pickWallpaper() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_SET_WALLPAPER);
                    startActivity(Intent.createChooser(i, "Wallpaper"));
                } catch (Exception ignored) {
                }
            });
        }

        @JavascriptInterface
        public void homeSettings() {
            runOnUiThread(MainActivity.this::openHomeSwitch);
        }

        @JavascriptInterface
        public void startScreenShare(final String code, final String name) {
            runOnUiThread(() -> {
                pendingCode = code;
                pendingName = name;
                MediaProjectionManager mpm =
                        (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
            });
        }

        @JavascriptInterface
        public void stopScreenShare() {
            stopService(new Intent(MainActivity.this, ShareService.class));
            ShareService.lastStatusJson = "{}";
        }

        @JavascriptInterface
        public String shareState() {
            return ShareService.lastStatusJson;
        }
    }
}
