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
    private static final float MIN_SCALE = 1.5f;
    private static final float MAX_SCALE = 8.0f;

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

    private boolean pinchActive = false;
    private float pinchStartSpan = 0f;
    private float pinchStartScale = DEFAULT_SCALE;
    private float pinchStartCenterX = 0f;
    private float pinchStartCenterY = 0f;
    private float pinchStartFocusX = 0f;
    private float pinchStartFocusY = 0f;

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
        Log.i(TAG, "v4 connected; first tap magnifies; one-finger drag pans; two-finger pinch zooms");
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
        pinchActive = false;
        mainHandler.removeCallbacks(armTimeout);
        enableTouchCapture();
        Log.i(TAG, "Panning + pinch capture ENABLED");
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
        pinchActive = false;
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

        // Keep the navigation area usable. Only a single-finger DOWN is relayed.
        if (action == MotionEvent.ACTION_DOWN && event.getPointerCount() == 1
                && isInNavigationRelayArea(y)) {
            relayNavigationTap(x, y);
            return;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                pinchActive = false;
                downX = lastX = x;
                downY = lastY = y;
                dragging = false;
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.getPointerCount() >= 2) {
                    beginPinch(event);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (pinchActive && event.getPointerCount() >= 2) {
                    updatePinchZoom(event);
                } else if (event.getPointerCount() == 1) {
                    float oneX = event.getX(0);
                    float oneY = event.getY(0);
                    float dx = oneX - lastX;
                    float dy = oneY - lastY;
                    if (!dragging) {
                        float slop = dp(PAN_SLOP_DP);
                        dragging = Math.hypot(oneX - downX, oneY - downY) >= slop;
                    }
                    if (dragging) {
                        panByFingerDelta(dx, dy);
                    }
                    lastX = oneX;
                    lastY = oneY;
                }
                break;

            case MotionEvent.ACTION_POINTER_UP:
                if (pinchActive && event.getPointerCount() == 2) {
                    pinchActive = false;
                    dragging = false;

                    // ACTION_POINTER_UP still contains the pointer that is leaving.
                    // Seed one-finger panning with the remaining pointer to avoid a jump.
                    int leaving = event.getActionIndex();
                    int remaining = leaving == 0 ? 1 : 0;
                    if (remaining < event.getPointerCount()) {
                        downX = lastX = event.getX(remaining);
                        downY = lastY = event.getY(remaining);
                    }
                    Log.i(TAG, "Pinch finished");
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                pinchActive = false;
                break;
        }

        // Touchscreen events are intentionally captured while magnified. Therefore
        // one-finger panning and two-finger pinch gestures are not delivered to the
        // app underneath the magnifier.
    }

    private void beginPinch(MotionEvent event) {
        if (event.getPointerCount() < 2) return;
        try {
            MagnificationConfig current = getMagnificationController().getMagnificationConfig();
            if (current == null || !current.isActivated() || current.getScale() <= 1.0f) return;

            pinchStartSpan = pointerSpan(event);
            if (pinchStartSpan <= 0f) return;

            pinchStartScale = current.getScale();
            pinchStartCenterX = current.getCenterX();
            pinchStartCenterY = current.getCenterY();
            pinchStartFocusX = pointerFocusX(event);
            pinchStartFocusY = pointerFocusY(event);
            pinchActive = true;
            dragging = false;
            Log.i(TAG, "Pinch started scale=" + pinchStartScale
                    + " focus=" + pinchStartFocusX + "," + pinchStartFocusY);
        } catch (Throwable t) {
            pinchActive = false;
            Log.e(TAG, "Could not start pinch", t);
        }
    }

    private void updatePinchZoom(MotionEvent event) {
        if (event.getPointerCount() < 2 || pinchStartSpan <= 0f) return;
        try {
            float span = pointerSpan(event);
            if (span <= 0f) return;

            float requestedScale = pinchStartScale * (span / pinchStartSpan);
            float newScale = clamp(requestedScale, MIN_SCALE, MAX_SCALE);

            DisplayMetrics dm = getResources().getDisplayMetrics();
            float displayCenterX = dm.widthPixels / 2.0f;
            float displayCenterY = dm.heightPixels / 2.0f;

            // Keep the content that was under the pinch midpoint approximately under
            // that midpoint while scale changes. This makes pinch feel like a normal
            // photo/map zoom instead of zooming only around the display center.
            float newCenterX = pinchStartCenterX
                    + (pinchStartFocusX - displayCenterX)
                    * ((1.0f / pinchStartScale) - (1.0f / newScale));
            float newCenterY = pinchStartCenterY
                    + (pinchStartFocusY - displayCenterY)
                    * ((1.0f / pinchStartScale) - (1.0f / newScale));

            float halfViewportW = dm.widthPixels / (2.0f * newScale);
            float halfViewportH = dm.heightPixels / (2.0f * newScale);
            newCenterX = clamp(newCenterX, halfViewportW, dm.widthPixels - halfViewportW);
            newCenterY = clamp(newCenterY, halfViewportH, dm.heightPixels - halfViewportH);

            MagnificationConfig next = new MagnificationConfig.Builder()
                    .setMode(MagnificationConfig.MAGNIFICATION_MODE_FULLSCREEN)
                    .setActivated(true)
                    .setScale(newScale)
                    .setCenterX(newCenterX)
                    .setCenterY(newCenterY)
                    .build();

            boolean ok = getMagnificationController().setMagnificationConfig(next, false);
            if (!ok) {
                Log.w(TAG, "Pinch zoom update rejected scale=" + newScale);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Pinch zoom failed", t);
        }
    }

    private static float pointerSpan(MotionEvent event) {
        float dx = event.getX(1) - event.getX(0);
        float dy = event.getY(1) - event.getY(0);
        return (float) Math.hypot(dx, dy);
    }

    private static float pointerFocusX(MotionEvent event) {
        return (event.getX(0) + event.getX(1)) / 2.0f;
    }

    private static float pointerFocusY(MotionEvent event) {
        return (event.getY(0) + event.getY(1)) / 2.0f;
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
