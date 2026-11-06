package ai.freeapp.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Queue;

public class PhoneControlService extends AccessibilityService {
    private static final String PREFS = "freeapp_phone_control";
    private static final String KEY_ENABLED = "user_enabled";
    private static final String KEY_ACTION = "pending_action";
    private static final String KEY_VALUE = "pending_value";
    private static volatile PhoneControlService activeService;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poller = new Runnable() { @Override public void run() { runPendingAction(); handler.postDelayed(this, 500); } };

    @Override public void onServiceConnected() {
        activeService = this;
        prefs().edit().putBoolean(KEY_ENABLED, true).apply();
        handler.removeCallbacks(poller);
        handler.post(poller);
        AccessibilityServiceInfo info = getServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.notificationTimeout = 150;
        setServiceInfo(info);
        runPendingAction();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isUserControlEnabled()) return;
        runPendingAction();
    }

    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        handler.removeCallbacks(poller);
        if (activeService == this) activeService = null;
        prefs().edit().putBoolean(KEY_ENABLED, false).apply();
        super.onDestroy();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private boolean isUserControlEnabled() {
        return prefs().getBoolean(KEY_ENABLED, false);
    }

    private void runPendingAction() {
        if (!isUserControlEnabled()) return;
        String action = prefs().getString(KEY_ACTION, "");
        if (action == null || action.isEmpty()) return;
        String value = prefs().getString(KEY_VALUE, "");
        prefs().edit().remove(KEY_ACTION).remove(KEY_VALUE).apply();
        if ("tap".equals(action)) {
            clickVisibleText(value);
        } else if ("type".equals(action)) {
            typeIntoFocusedField(value);
        } else if ("scroll_down".equals(action)) {
            scroll(true);
        } else if ("scroll_up".equals(action)) {
            scroll(false);
        } else if ("back".equals(action)) {
            performGlobalAction(GLOBAL_ACTION_BACK);
        } else if ("home".equals(action)) {
            performGlobalAction(GLOBAL_ACTION_HOME);
        }
    }

    private boolean clickVisibleText(String wanted) {
        if (wanted == null || wanted.trim().isEmpty()) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(wanted.trim())) {
                if (node == null) continue;
                AccessibilityNodeInfo target = node;
                while (target != null && !target.isClickable()) target = target.getParent();
                if (target != null) return target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        } finally {
            root.recycle();
        }
        return false;
    }

    private boolean typeIntoFocusedField(String value) {
        if (value == null || value.trim().isEmpty() || looksSensitive(value)) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused == null || !focused.isEditable()) return false;
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        } finally {
            root.recycle();
        }
    }

    private boolean scroll(boolean down) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            return root.performAction(down ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        } finally {
            root.recycle();
        }
    }

    private boolean looksSensitive(String value) {
        String s = value.toLowerCase(Locale.ROOT);
        return s.contains("password") || s.contains("passcode") || s.contains("otp")
                || s.contains("one-time code") || s.contains("verification code")
                || s.contains("security code") || s.contains("cvv") || s.contains("card number");
    }

    public static boolean isConnected() { return activeService != null; }

    public static void queueAction(android.content.Context context, String action, String value) {
        SharedPreferences p = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!p.getBoolean(KEY_ENABLED, false)) return;
        p.edit().putString(KEY_VALUE, value == null ? "" : value)
                .putString(KEY_ACTION, action == null ? "" : action).apply();
        PhoneControlService service = activeService;
        if (service != null) service.handler.post(service::runPendingAction);
    }
}
