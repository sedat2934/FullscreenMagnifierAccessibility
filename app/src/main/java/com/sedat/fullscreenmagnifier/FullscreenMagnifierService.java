package com.sedat.fullscreenmagnifier;

import android.accessibilityservice.AccessibilityButtonController;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.MagnificationConfig;
import android.accessibilityservice.AccessibilityService.MagnificationController;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;

public class FullscreenMagnifierService extends AccessibilityService {
    private static final String TAG = "FSMagService";
    private static final float DEFAULT_SCALE = 3.0f;
    private static final long ARM_TIMEOUT_MS = 8000L;

    private AccessibilityButtonController.AccessibilityButtonCallback buttonCallback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean waitingForTap = false;

    private final Runnable armTimeout = () -> {
        if (waitingForTap) {
            Log.i(TAG, "Tap wait timed out");
            disarmTouchCapture();
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        disarmTouchCapture();

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
        Log.i(TAG, "Service connected; press accessibility button, then tap screen");
    }

    private void handleAccessibilityButton() {
        try {
            MagnificationController mc = getMagnificationController();
            MagnificationConfig current = mc.getMagnificationConfig();
            boolean active = current != null && current.isActivated() && current.getScale() > 1.0f;

            if (active) {
                disarmTouchCapture();
                boolean ok = mc.reset(true);
                Log.i(TAG, "Magnification OFF from accessibility button, reset=" + ok);
                return;
            }

            if (waitingForTap) {
                Log.i(TAG, "Already waiting for first tap");
                return;
            }

            armTouchCapture();
        } catch (Throwable t) {
            Log.e(TAG, "Accessibility button handling failed", t);
        }
    }

    private void armTouchCapture() {
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            info.setMotionEventSources(InputDevice.SOURCE_TOUCHSCREEN);
            setServiceInfo(info);
            waitingForTap = true;
            mainHandler.removeCallbacks(armTimeout);
            mainHandler.postDelayed(armTimeout, ARM_TIMEOUT_MS);
            Log.i(TAG, "ARMED: waiting for first touchscreen tap");
        } catch (Throwable t) {
            waitingForTap = false;
            Log.e(TAG, "Could not arm touchscreen capture", t);
        }
    }

    private void disarmTouchCapture() {
        waitingForTap = false;
        mainHandler.removeCallbacks(armTimeout);
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.setMotionEventSources(0);
                setServiceInfo(info);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not disarm touchscreen capture", t);
        }
    }

    @Override
    public void onMotionEvent(MotionEvent event) {
        if (!waitingForTap || event == null) return;

        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            final float x = event.getX();
            final float y = event.getY();
            Log.i(TAG, "First tap captured at " + x + "," + y);

            disarmTouchCapture();
            enableFullscreenMagnification(x, y);
        }
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
        } catch (Throwable t) {
            Log.e(TAG, "Fullscreen magnification failed", t);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() {
        disarmTouchCapture();
    }

    @Override
    public void onDestroy() {
        disarmTouchCapture();
        try {
            if (buttonCallback != null) {
                getAccessibilityButtonController().unregisterAccessibilityButtonCallback(buttonCallback);
            }
        } catch (Throwable ignored) { }
        super.onDestroy();
    }
}
