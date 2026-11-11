package io.codemyriad.cassini.preflight;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.os.HandlerThread;
import android.os.Process;
import android.view.accessibility.AccessibilityEvent;

/** A shell-UID window observer, launched from the test APK before instrumentation starts. */
public final class WindowEvents {
    private WindowEvents() {}

    public static void main(String[] arguments) throws Exception {
        System.out.println("START " + Process.myPid());
        System.out.flush();
        HandlerThread events = new HandlerThread("cassini-window-events");
        events.start();
        UiAutomation automation = null;
        try {
            Class<?> connectionType = Class.forName("android.app.IUiAutomationConnection");
            Object connection = Class.forName("android.app.UiAutomationConnection")
                    .getDeclaredConstructor().newInstance();
            automation = UiAutomation.class.getDeclaredConstructor(android.os.Looper.class, connectionType)
                    .newInstance(events.getLooper(), connection);
            UiAutomation.class.getDeclaredMethod("connect").invoke(automation);
            automation.setOnAccessibilityEventListener(event -> {
                if (event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                        || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    System.out.println("WINDOW");
                    System.out.flush();
                }
            });
            AccessibilityServiceInfo info = automation.getServiceInfo();
            info.eventTypes = AccessibilityEvent.TYPE_WINDOWS_CHANGED | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(info);
            System.out.println("READY");
            System.out.flush();
            // The host closes this observer through its private stdin pipe.
            System.in.read();
        } finally {
            if (automation != null) {
                automation.setOnAccessibilityEventListener(null);
                UiAutomation.class.getDeclaredMethod("disconnect").invoke(automation);
            }
            events.quitSafely();
            events.join();
            System.out.println("STOPPED");
            System.out.flush();
        }
    }
}
