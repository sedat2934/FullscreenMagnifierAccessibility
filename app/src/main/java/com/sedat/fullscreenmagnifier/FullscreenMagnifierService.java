package com.sedat.fullscreenmagnifier;

import android.accessibilityservice.AccessibilityButtonController;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.MagnificationConfig;
import android.accessibilityservice.AccessibilityService.MagnificationController;
import android.graphics.Point;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

public class FullscreenMagnifierService extends AccessibilityService {
    private static final String TAG = "FSMagService";
    private static final float DEFAULT_SCALE = 3.0f;

    private AccessibilityButtonController.AccessibilityButtonCallback buttonCallback;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        AccessibilityButtonController controller = getAccessibilityButtonController();
        buttonCallback = new AccessibilityButtonController.AccessibilityButtonCallback() {
            @Override
            public void onClicked(AccessibilityButtonController controller) {
                toggleFullscreenMagnification();
            }

            @Override
            public void onAvailabilityChanged(AccessibilityButtonController controller, boolean available) {
                Log.i(TAG, "Accessibility button available=" + available);
            }
        };
        controller.registerAccessibilityButtonCallback(buttonCallback);
        Log.i(TAG, "Service connected and accessibility button callback registered");
    }

    private void toggleFullscreenMagnification() {
        try {
            MagnificationController mc = getMagnificationController();
            MagnificationConfig current = mc.getMagnificationConfig();
            boolean active = current != null && current.isActivated() && current.getScale() > 1.0f;

            if (active) {
                boolean ok = mc.reset(true);
                Log.i(TAG, "Fullscreen magnification OFF, reset=" + ok);
                return;
            }

            float centerX = 0f;
            float centerY = 0f;
            Display display = getDisplay();
            if (display != null) {
                Point size = new Point();
                display.getRealSize(size);
                centerX = size.x / 2f;
                centerY = size.y / 2f;
            }

            MagnificationConfig.Builder builder = new MagnificationConfig.Builder()
                    .setMode(MagnificationConfig.MAGNIFICATION_MODE_FULLSCREEN)
                    .setActivated(true)
                    .setScale(DEFAULT_SCALE);

            if (centerX > 0f && centerY > 0f) {
                builder.setCenterX(centerX).setCenterY(centerY);
            }

            boolean ok = mc.setMagnificationConfig(builder.build(), true);
            Log.i(TAG, "Fullscreen magnification ON, result=" + ok +
                    " center=" + centerX + "," + centerY);
        } catch (Throwable t) {
            Log.e(TAG, "Magnification toggle failed", t);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No event processing needed.
    }

    @Override
    public void onInterrupt() {
        // Nothing to interrupt.
    }

    @Override
    public void onDestroy() {
        try {
            if (buttonCallback != null) {
                getAccessibilityButtonController().unregisterAccessibilityButtonCallback(buttonCallback);
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
