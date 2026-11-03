package com.orbit.hub;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

/**
 * Carries out remote-control commands on this phone (tap, drag, scroll,
 * type, back / home / recents) for viewers on the owner's Bomba Live wall.
 * Only acts while enabled by the owner in Android's Accessibility settings;
 * commands arrive through ShareService's data channels while sharing.
 *
 * Coordinates in commands are normalized 0..1 over the whole display,
 * matching the shared video.
 */
public class OrbitAccessibilityService extends AccessibilityService {

    public static volatile OrbitAccessibilityService instance;

    private float dragX = -1, dragY = -1;
    private long dragT;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
        // Not used; gestures and window content are all we need.
    }

    @Override
    public void onInterrupt() {
    }

    private DisplayMetrics metrics() {
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        return dm;
    }

    private void gesture(float x0, float y0, float x1, float y1, long durationMs) {
        Path path = new Path();
        path.moveTo(x0, y0);
        if (x0 != x1 || y0 != y1) path.lineTo(x1, y1);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(1, durationMs)))
                .build();
        dispatchGesture(gesture, null, null);
    }

    /** Runs one control command. Called from ShareService's data channel. */
    public void handleCommand(JSONObject cmd) {
        try {
            String t = cmd.optString("t");
            DisplayMetrics dm = metrics();
            float w = dm.widthPixels, h = dm.heightPixels;
            switch (t) {
                case "click": {
                    float x = (float) cmd.optDouble("x", 0.5) * w;
                    float y = (float) cmd.optDouble("y", 0.5) * h;
                    gesture(x, y, x, y, 70);
                    break;
                }
                case "dblclick": {
                    float x = (float) cmd.optDouble("x", 0.5) * w;
                    float y = (float) cmd.optDouble("y", 0.5) * h;
                    gesture(x, y, x, y, 70);
                    gesture(x, y, x, y, 70);
                    break;
                }
                case "down":
                    dragX = (float) cmd.optDouble("x", 0.5) * w;
                    dragY = (float) cmd.optDouble("y", 0.5) * h;
                    dragT = System.currentTimeMillis();
                    break;
                case "move":
                    break; // drag is delivered as one stroke on release
                case "up": {
                    if (dragX >= 0) {
                        float x = (float) cmd.optDouble("x", dragX / w) * w;
                        float y = (float) cmd.optDouble("y", dragY / h) * h;
                        long dur = Math.min(1200, Math.max(120, System.currentTimeMillis() - dragT));
                        gesture(dragX, dragY, x, y, dur);
                        dragX = dragY = -1;
                    }
                    break;
                }
                case "wheel": {
                    int d = cmd.optInt("d", 0);
                    int times = Math.min(3, Math.abs(d));
                    for (int i = 0; i < times; i++) {
                        if (d > 0) gesture(w / 2, h * 0.65f, w / 2, h * 0.35f, 180);
                        else gesture(w / 2, h * 0.35f, w / 2, h * 0.65f, 180);
                    }
                    break;
                }
                case "text":
                    typeIntoFocused(cmd.optString("s", ""));
                    break;
                case "key":
                    key(cmd.optString("k", ""));
                    break;
                default:
                    break;
            }
        } catch (Exception ignored) {
        }
    }

    private void typeIntoFocused(String text) {
        if (text.isEmpty()) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus == null) return;
        CharSequence current = focus.getText();
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                (current == null ? "" : current.toString()) + text);
        focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    private void key(String k) {
        switch (k.toLowerCase()) {
            case "back":
                performGlobalAction(GLOBAL_ACTION_BACK);
                break;
            case "home":
                performGlobalAction(GLOBAL_ACTION_HOME);
                break;
            case "recents":
                performGlobalAction(GLOBAL_ACTION_RECENTS);
                break;
            case "enter": {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                AccessibilityNodeInfo focus = root == null ? null
                        : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (focus != null && Build.VERSION.SDK_INT >= 30) {
                    focus.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
                }
                break;
            }
            case "backspace": {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                AccessibilityNodeInfo focus = root == null ? null
                        : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (focus != null) {
                    CharSequence cur = focus.getText();
                    if (cur != null && cur.length() > 0) {
                        Bundle args = new Bundle();
                        args.putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                cur.subSequence(0, cur.length() - 1));
                        focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                    }
                }
                break;
            }
            default:
                break;
        }
    }
}
