package com.sedat.fullscreenmagnifier;

import android.accessibilityservice.AccessibilityButtonController;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.MagnificationConfig;
import android.accessibilityservice.AccessibilityService.MagnificationController;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;

public class FullscreenMagnifierService extends AccessibilityService {
    private static final String TAG = "FSMagService";
    private static final float DEFAULT_SCALE = 3.0f;
    private static final long ARM_TIMEOUT_MS = 8000L;
    private static final float PAN_SLOP_DP = 3.0f;
    private static final float NAV_RELAY_MIN_DP = 72.0f;

    private AccessibilityButtonController.AccessibilityButtonCallback buttonCallback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean waitingForTap = false;
    private boolean touchCaptureEnabled = false;
    private boolean magnified = false;

    private float lastX;
    private float lastY;
    private float downX;
    private float downY;
    private boolean dragging = false;

    private final Runnable armTimeout = () -> {
        if (waitingForTap && !magnified) {
            Log.i(TAG, "Tap wait timed out");
            disableTouchCapture();
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        disableTouchCapture();
        refreshMagnificationState();

        AccessibilityButtonController controller = getAccessibilityButtonController();
        buttonCallback = new AccessibilityButtonController.AccessibilityButtonCallback() {
            @Override
            public void onClicked(AccessibilityButtonController controller) {
                handleAccessibilityButton();
            }

            @Override
            public void onAvailabilityChanged(AccessibilityButtonController controller, boolean available) {
                Log.i(TAG, "Accessibility button available=" + available);
            }
        };
        controller.registerAccessibilityButtonCallback(buttonCallback);
        Log.i(TAG, "v3 connected; accessibility button -> first tap -> fullscreen magnification; one-finger drag pans");
    }

    private void handleAccessibilityButton() {
        try {
            refreshMagnificationState();
            if (magnified) {
                disableTouchCapture();
                boolean ok = getMagnificationController().reset(true);
                magnified = false;
                Log.i(TAG, "Magnification OFF from accessibility button, reset=" + ok);
                return;
            }

            if (waitingForTap) {
                Log.i(TAG, "Already waiting for first tap");
                return;
            }

            armForFirstTap();
        } catch (Throwable t) {
            Log.e(TAG, "Accessibility button handling failed", t);
        }
    }

    private void armForFirstTap() {
        waitingForTap = true;
        magnified = false;
        dragging = false;
        enableTouchCapture();
        mainHandler.removeCallbacks(armTimeout);
        mainHandler.postDelayed(armTimeout, ARM_TIMEOUT_MS);
        Log.i(TAG, "ARMED: waiting for first touchscreen tap");
    }

    private void enablePanningCapture() {
        waitingForTap = false;
        magnified = true;
        dragging = false;
        mainHandler.removeCallbacks(armTimeout);
        enableTouchCapture();
        Log.i(TAG, "One-finger panning capture ENABLED");
    }

    private void enableTouchCapture() {
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.setMotionEventSources(InputDevice.SOURCE_TOUCHSCREEN);
                setServiceInfo(info);
                touchCaptureEnabled = true;
            }
        } catch (Throwable t) {
            touchCaptureEnabled = false;
            Log.e(TAG, "Could not enable touchscreen capture", t);
        }
    }

    private void disableTouchCapture() {
        waitingForTap = false;
        dragging = false;
        mainHandler.removeCallbacks(armTimeout);
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.setMotionEventSources(0);
                setServiceInfo(info);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not disable touchscreen capture", t);
        }
        touchCaptureEnabled = false;
    }

    @Override
    public void onMotionEvent(MotionEvent event) {
        if (!touchCaptureEnabled || event == null) return;

        final int action = event.getActionMasked();
        final float x = event.getX();
        final float y = event.getY();

        if (waitingForTap) {
            if (action == MotionEvent.ACTION_DOWN) {
                Log.i(TAG, "First tap captured at " + x + "," + y);
                waitingForTap = false;
                mainHandler.removeCallbacks(armTimeout);
                enableFullscreenMagnification(x, y);
            }
            return;
        }

        if (!magnified) return;

        // Keep the navigation area usable. The original touch is consumed, then
        // replayed after capture is temporarily released so Home/Back/a11y button
        // can still be pressed while magnified.
        if (action == MotionEvent.ACTION_DOWN && isInNavigationRelayArea(y)) {
            relayNavigationTap(x, y);
            return;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = x;
                downY = lastY = y;
                dragging = false;
                break;

            case MotionEvent.ACTION_MOVE:
                float dx = x - lastX;
                float dy = y - lastY;
                if (!dragging) {
                    float slop = dp(PAN_SLOP_DP);
                    dragging = Math.hypot(x - downX, y - downY) >= slop;
                }
                if (dragging) {
                    panByFingerDelta(dx, dy);
                }
                lastX = x;
                lastY = y;
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                break;
        }
        // Intentionally do not delegate any app-area touch while magnified.
        // Because SOURCE_TOUCHSCREEN is captured, the underlying app does not
        // receive these one-finger gestures.
    }

    private void enableFullscreenMagnification(float centerX, float centerY) {
        try {
            MagnificationConfig config = new MagnificationConfig.Builder()
                    .setMode(MagnificationConfig.MAGNIFICATION_MODE_FULLSCREEN)
                    .setActivated(true)
                    .setScale(DEFAULT_SCALE)
                    .setCenterX(centerX)
                    .setCenterY(centerY)
                    .build();

            boolean ok = getMagnificationController().setMagnificationConfig(config, true);
            Log.i(TAG, "Fullscreen magnification ON, result=" + ok
                    + " center=" + centerX + "," + centerY
                    + " scale=" + DEFAULT_SCALE);

            if (ok) {
                enablePanningCapture();
            } else {
                magnified = false;
                disableTouchCapture();
            }
        } catch (Throwable t) {
            magnified = false;
            disableTouchCapture();
            Log.e(TAG, "Fullscreen magnification failed", t);
        }
    }

    private void panByFingerDelta(float dx, float dy) {
        try {
            MagnificationController mc = getMagnificationController();
            MagnificationConfig current = mc.getMagnificationConfig();
            if (current == null || !current.isActivated() || current.getScale() <= 1.0f) {
                magnified = false;
                disableTouchCapture();
                return;
            }

            float scale = current.getScale();
            float centerX = current.getCenterX();
            float centerY = current.getCenterY();

            // Content follows the finger: dragging right reveals content on the left,
            // dragging left reveals content on the right.
            float newCenterX = centerX - (dx / scale);
            float newCenterY = centerY - (dy / scale);

            DisplayMetrics dm = getResources().getDisplayMetrics();
            float halfViewportW = dm.widthPixels / (2.0f * scale);
            float halfViewportH = dm.heightPixels / (2.0f * scale);
            newCenterX = clamp(newCenterX, halfViewportW, dm.widthPixels - halfViewportW);
            newCenterY = clamp(newCenterY, halfViewportH, dm.heightPixels - halfViewportH);

            MagnificationConfig next = new MagnificationConfig.Builder()
                    .setMode(MagnificationConfig.MAGNIFICATION_MODE_FULLSCREEN)
                    .setActivated(true)
                    .setScale(scale)
                    .setCenterX(newCenterX)
                    .setCenterY(newCenterY)
                    .build();

            mc.setMagnificationConfig(next, false);
        } catch (Throwable t) {
            Log.e(TAG, "Panning failed", t);
        }
    }

    private void relayNavigationTap(float x, float y) {
        Log.i(TAG, "Relaying navigation-area tap at " + x + "," + y);
        disableTouchCapture();

        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 60);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

        mainHandler.postDelayed(() -> {
            boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    super.onCompleted(gestureDescription);
                    mainHandler.postDelayed(() -> {
                        refreshMagnificationState();
                        if (magnified) enablePanningCapture();
                    }, 180);
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    super.onCancelled(gestureDescription);
                    mainHandler.postDelayed(() -> {
                        refreshMagnificationState();
                        if (magnified) enablePanningCapture();
                    }, 180);
                }
            }, mainHandler);
            Log.i(TAG, "Navigation tap dispatched=" + dispatched);
            if (!dispatched) {
                refreshMagnificationState();
                if (magnified) enablePanningCapture();
            }
        }, 40);
    }

    private boolean isInNavigationRelayArea(float y) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int navHeight = 0;
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (id > 0) {
            try { navHeight = getResources().getDimensionPixelSize(id); } catch (Throwable ignored) { }
        }
        float relayHeight = Math.max(navHeight + dp(16), dp(NAV_RELAY_MIN_DP));
        return y >= (dm.heightPixels - relayHeight);
    }

    private void refreshMagnificationState() {
        try {
            MagnificationConfig current = getMagnificationController().getMagnificationConfig();
            magnified = current != null && current.isActivated() && current.getScale() > 1.0f;
        } catch (Throwable t) {
            magnified = false;
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float v, float min, float max) {
        if (max < min) return v;
        return Math.max(min, Math.min(max, v));
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() {
        disableTouchCapture();
    }

    @Override
    public void onDestroy() {
        disableTouchCapture();
        try {
            if (buttonCallback != null) {
                getAccessibilityButtonController().unregisterAccessibilityButtonCallback(buttonCallback);
            }
        } catch (Throwable ignored) { }
        super.onDestroy();
    }
}
