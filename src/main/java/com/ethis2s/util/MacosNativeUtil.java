package com.ethis2s.util;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import javafx.stage.Stage;

import java.lang.reflect.Field;

/**
 * 리플렉션과 JNA를 사용하여 macOS의 네이티브 윈도우 기능을 직접 제어하는 유틸리티 클래스.
 * "신재창 기법 v2"의 핵심 구현을 포함한다.
 */
public final class MacosNativeUtil {

    private interface MacosApi extends Library {
        MacosApi INSTANCE = Native.load(null, MacosApi.class);

        long objc_msgSend(Pointer receiver, Pointer selector);
        void objc_msgSend(Pointer receiver, Pointer selector, long value);
        void objc_msgSend(Pointer receiver, Pointer selector, boolean value);

        Pointer sel_getUid(String name);
    }

    private static final Pointer styleMaskSelector = MacosApi.INSTANCE.sel_getUid("styleMask");
    private static final Pointer setStyleMaskSelector = MacosApi.INSTANCE.sel_getUid("setStyleMask:");
    private static final Pointer setTitlebarAppearsTransparentSelector = MacosApi.INSTANCE.sel_getUid("setTitlebarAppearsTransparent:");
    private static final Pointer setTitleVisibilitySelector = MacosApi.INSTANCE.sel_getUid("setTitleVisibility:");
    private static final long NSWindowStyleMaskFullSizeContentView = 1 << 15;
    private static final long NSWindowTitleHidden = 1;

    /**
     * 주어진 Stage에 대해 macOS의 네이티브 Unified Title Bar 스타일을 강제로 적용한다.
     * 이 메소드는 반드시 stage.show()가 호출된 이후에 실행되어야 한다.
     * @param stage 스타일을 적용할 대상 Stage
     */
    public static void applyUnifiedTitleBarStyle(Stage stage) {
        // macOS가 아니면 아무것도 하지 않음
        String os = System.getProperty("os.name").toLowerCase();
        if (!os.contains("mac")) {
            return;
        }

        try {
            // --- 리플렉션으로 네이티브 윈도우 포인터(ptr) 획득 ---
            Field peerField = javafx.stage.Window.class.getDeclaredField("peer");
            peerField.setAccessible(true);
            Object peer = peerField.get(stage);

            Field platformWindowField = peer.getClass().getDeclaredField("platformWindow");
            platformWindowField.setAccessible(true);
            Object platformWindow = platformWindowField.get(peer);

            Field ptrField = com.sun.glass.ui.Window.class.getDeclaredField("ptr");
            ptrField.setAccessible(true);
            long nativeWindowPtr = ptrField.getLong(platformWindow);

            if (nativeWindowPtr == 0) {
                System.err.println("MacosNativeUtil: 네이티브 포인터가 0입니다.");
                return;
            }

            // --- JNA로 네이티브 NSWindow 스타일 직접 수정 ---
            Pointer nsWindowPtr = new Pointer(nativeWindowPtr);
            long oldStyleMask = MacosApi.INSTANCE.objc_msgSend(nsWindowPtr, styleMaskSelector);
            long newStyleMask = oldStyleMask | NSWindowStyleMaskFullSizeContentView;

            MacosApi.INSTANCE.objc_msgSend(nsWindowPtr, setStyleMaskSelector, newStyleMask);
            MacosApi.INSTANCE.objc_msgSend(nsWindowPtr, setTitlebarAppearsTransparentSelector, true);
            MacosApi.INSTANCE.objc_msgSend(nsWindowPtr, setTitleVisibilitySelector, NSWindowTitleHidden);

            System.out.println("MacosNativeUtil: '신재창 기법 v2'가 성공적으로 적용되었습니다.");

        } catch (Exception e) {
            System.err.println("MacosNativeUtil: 네이티브 스타일 적용 중 예외 발생");
            e.printStackTrace();
        }
    }
}