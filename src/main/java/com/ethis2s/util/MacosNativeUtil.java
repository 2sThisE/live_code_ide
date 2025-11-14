package com.ethis2s.util;

import com.sun.jna.FunctionMapper;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * 리플렉션과 JNA를 사용하여 macOS의 네이티브 윈도우 기능을 직접 제어하는 유틸리티 클래스.
 * Unified Title Bar 스타일 적용 및 네이티브 창 드래그 기능을 포함한다.
 */
public final class MacosNativeUtil {

    private interface MacosApi extends Library {
        Map<String, ?> OPTIONS = new HashMap<>() {{
            put(Library.OPTION_FUNCTION_MAPPER, (FunctionMapper) (library, method) -> {
                if (method.getName().startsWith("objc_msgSend")) {
                    return "objc_msgSend";
                }
                return method.getName();
            });
        }};
        MacosApi INSTANCE = Native.load("Foundation", MacosApi.class, OPTIONS);

        long objc_msgSend_retLong(Pointer receiver, Pointer selector);
        Pointer objc_msgSend_retPtr(Pointer receiver, Pointer selector);
        void objc_msgSend_retVoid(Pointer receiver, Pointer selector, long value);
        void objc_msgSend_retVoid(Pointer receiver, Pointer selector, boolean value);
        void objc_msgSend_retVoid_ptrArg(Pointer receiver, Pointer selector, Pointer value);

        Pointer sel_getUid(String name);
        Pointer objc_getClass(String name);
    }

    // --- Style Selectors ---
    private static final Pointer styleMaskSelector = MacosApi.INSTANCE.sel_getUid("styleMask");
    private static final Pointer setStyleMaskSelector = MacosApi.INSTANCE.sel_getUid("setStyleMask:");
    private static final Pointer setTitlebarAppearsTransparentSelector = MacosApi.INSTANCE.sel_getUid("setTitlebarAppearsTransparent:");
    private static final Pointer setTitleVisibilitySelector = MacosApi.INSTANCE.sel_getUid("setTitleVisibility:");
    
    // --- Drag Selectors ---
    private static final Pointer performWindowDragWithEventSelector = MacosApi.INSTANCE.sel_getUid("performWindowDragWithEvent:");
    private static final Pointer currentEventSelector = MacosApi.INSTANCE.sel_getUid("currentEvent");
    private static final Pointer sharedApplicationSelector = MacosApi.INSTANCE.sel_getUid("sharedApplication");
    private static final Pointer nsApplicationClass = MacosApi.INSTANCE.objc_getClass("NSApplication");

    // --- Native Constants ---
    private static final long NSWindowStyleMaskFullSizeContentView = 1 << 15;
    private static final long NSWindowTitleHidden = 1;

    /**
     * 주어진 Stage에 대해 macOS의 네이티브 Unified Title Bar 스타일을 강제로 적용한다.
     * 이 메소드는 반드시 stage.show()가 호출된 이후에 실행되어야 한다.
     * @param stage 스타일을 적용할 대상 Stage
     */
    public static void applyUnifiedTitleBarStyle(Stage stage) {
        String os = System.getProperty("os.name").toLowerCase();
        if (!os.contains("mac")) {
            return;
        }
        try {
            long nativeWindowPtr = getNativeWindowPointer(stage);
            if (nativeWindowPtr == 0) {
                System.err.println("MacosNativeUtil: 네이티브 포인터가 0입니다.");
                return;
            }

            Pointer nsWindowPtr = new Pointer(nativeWindowPtr);
            long oldStyleMask = MacosApi.INSTANCE.objc_msgSend_retLong(nsWindowPtr, styleMaskSelector);
            long newStyleMask = oldStyleMask | NSWindowStyleMaskFullSizeContentView;

            MacosApi.INSTANCE.objc_msgSend_retVoid(nsWindowPtr, setStyleMaskSelector, newStyleMask);
            MacosApi.INSTANCE.objc_msgSend_retVoid(nsWindowPtr, setTitlebarAppearsTransparentSelector, true);
            MacosApi.INSTANCE.objc_msgSend_retVoid(nsWindowPtr, setTitleVisibilitySelector, NSWindowTitleHidden);

            System.out.println("MacosNativeUtil: Unified Title Bar 스타일이 성공적으로 적용되었습니다.");

        } catch (Exception e) {
            System.err.println("MacosNativeUtil: 네이티브 스타일 적용 중 예외 발생");
            e.printStackTrace();
        }
    }

    /**
     * 현재 마우스 이벤트를 사용하여 네이티브 창 드래그를 시작한다.
     * 이 메소드는 MOUSE_PRESSED 이벤트에서만 호출되어야 한다.
     * @param event JavaFX MouseEvent (MOUSE_PRESSED 이벤트)
     * @param stage 현재 Stage
     */
    public static void performNativeWindowDrag(MouseEvent event, Stage stage) {
        String os = System.getProperty("os.name").toLowerCase();
        if (!os.contains("mac")) {
            return;
        }
        event.consume();
        try {
            long nativeWindowPtr = getNativeWindowPointer(stage);
            if (nativeWindowPtr == 0) return;

            Pointer nsApplication = MacosApi.INSTANCE.objc_msgSend_retPtr(nsApplicationClass, sharedApplicationSelector);
            Pointer currentNSEvent = MacosApi.INSTANCE.objc_msgSend_retPtr(nsApplication, currentEventSelector);
            
            Pointer nsWindowPtr = new Pointer(nativeWindowPtr);
            MacosApi.INSTANCE.objc_msgSend_retVoid_ptrArg(nsWindowPtr, performWindowDragWithEventSelector, currentNSEvent);

        } catch (Exception e) {
            System.err.println("MacosNativeUtil: 네이티브 드래그 처리 중 예외 발생");
            e.printStackTrace();
        }
    }

    /**
     * JavaFX Stage에서 네이티브 NSWindow 포인터를 추출한다.
     */
    private static long getNativeWindowPointer(Stage stage) throws Exception {
        Field peerField = javafx.stage.Window.class.getDeclaredField("peer");
        peerField.setAccessible(true);
        Object peer = peerField.get(stage);
        Field platformWindowField = peer.getClass().getDeclaredField("platformWindow");
        platformWindowField.setAccessible(true);
        Object platformWindow = platformWindowField.get(peer);
        Field ptrField = com.sun.glass.ui.Window.class.getDeclaredField("ptr");
        ptrField.setAccessible(true);
        return ptrField.getLong(platformWindow);
    }
}