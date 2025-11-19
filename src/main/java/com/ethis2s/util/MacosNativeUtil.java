package com.ethis2s.util;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import com.sun.jna.FunctionMapper;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
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
        CGRect.ByValue objc_msgSend_retCGRect(Pointer receiver, Pointer selector);
        long objc_msgSend_retLong(Pointer receiver, Pointer selector);
        Pointer objc_msgSend_retPtr(Pointer receiver, Pointer selector);
        void objc_msgSend_retVoid(Pointer receiver, Pointer selector, long value);
        void objc_msgSend_retVoid(Pointer receiver, Pointer selector, boolean value);
        void objc_msgSend_retVoid_ptrArg(Pointer receiver, Pointer selector, Pointer value);

        Pointer sel_getUid(String name);
        Pointer objc_getClass(String name);

        // --- [수정] 인자를 받는 objc_msgSend 시그니처들 추가 ---
        // int 인자를 받는 버전 (for objectAtIndex:)
        Pointer objc_msgSend_retPtr_intArg(Pointer receiver, Pointer selector, int value);
        // CGPoint를 반환하는 버전 (for mouseLocation)
        CGPoint.ByValue objc_msgSend_retCGPoint(Pointer receiver, Pointer selector);
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


    // --- 화면 보정을 위한 상수 ---
    private static final Pointer contentViewSelector = MacosApi.INSTANCE.sel_getUid("contentView");
    private static double cachedTitleBarOffset = -1.0;
    private static final Pointer mouseLocationSelector = MacosApi.INSTANCE.sel_getUid("mouseLocation");
    private static final Pointer nsEventClass = MacosApi.INSTANCE.objc_getClass("NSEvent");
    private static final Pointer screensSelector = MacosApi.INSTANCE.sel_getUid("screens");
    private static final Pointer nsScreenClass = MacosApi.INSTANCE.objc_getClass("NSScreen");
    private static final Pointer frameSelector = MacosApi.INSTANCE.sel_getUid("frame"); // 재사용
    private static final Pointer objectAtIndexSelector = MacosApi.INSTANCE.sel_getUid("objectAtIndex:"); // 추가


    // --- 구조체 정의 (이전과 동일) ---
    @Structure.FieldOrder({"origin", "size"})
    public static class CGRect extends Structure {
        public CGPoint origin;
        public CGSize size;
        public static class ByValue extends CGRect implements Structure.ByValue {}
    }
    @Structure.FieldOrder({"x", "y"})
    public static class CGPoint extends Structure {
        public double x, y;
        // Pointer로부터 CGPoint 객체를 생성하는 생성자 추가
        public CGPoint(Pointer p) { super(p); read(); }
        public CGPoint() { super(); }
    }
    @Structure.FieldOrder({"width", "height"})
    public static class CGSize extends Structure {
        public double width, height;
    }



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


    public static double calculateAndCacheOffset(Stage stage) {
        if (cachedTitleBarOffset > 0) return cachedTitleBarOffset;
        
        try {
            long nativeWindowPtr = getNativeWindowPointer(stage);
            if (nativeWindowPtr == 0) return 0.0;
            Pointer nsWindowPtr = new Pointer(nativeWindowPtr);

            // 1. 네이티브 NSWindow에게 "너의 contentView가 누구니?" 라고 물어봅니다.
            //    이것은 NSView 객체를 가리키는 포인터를 반환합니다.
            Pointer contentViewPtr = MacosApi.INSTANCE.objc_msgSend_retPtr(nsWindowPtr, contentViewSelector);
            if (contentViewPtr == null) {
                System.err.println("MacosNativeUtil: contentView 포인터를 얻지 못했습니다.");
                return 0.0;
            }

            // 2. 그 contentView에게 "너의 frame 정보가 뭐야?" 라고 물어봅니다.
            //    이것은 contentView의 위치와 크기를 담은 CGRect 구조체를 반환합니다.
            CGRect.ByValue contentViewFrame = MacosApi.INSTANCE.objc_msgSend_retCGRect(contentViewPtr, frameSelector);

            // 3. contentView의 화면상 Y좌표 시작점을 얻습니다.
            //    이것이 바로 JavaFX가 (착각 속에서) 0,0으로 삼아야 할 진짜 기준점입니다.
            double nativeContentY = contentViewFrame.origin.y;
            
            // 4. JavaFX Stage가 생각하는 자신의 화면상 Y좌표를 얻습니다.
            double javafxStageY = stage.getY();

            // 5. [최종 계산법]
            //    JavaFX가 생각하는 창의 시작점과, 실제 콘텐츠가 시작되는 점의 차이를 계산합니다.
            double offset = javafxStageY - nativeContentY;
            
            // 6. 계산된 값이 유효할 때만 캐싱합니다.
            if (Math.abs(offset) > 1) { // 0이 아닌 의미있는 값일 때
                 // macOS 좌표계는 하단이 0이므로, 화면 높이를 이용한 변환이 필요합니다.
                Pointer screensArray = MacosApi.INSTANCE.objc_msgSend_retPtr(nsScreenClass, screensSelector);
                Pointer mainScreen = MacosApi.INSTANCE.objc_msgSend_retPtr_intArg(screensArray, objectAtIndexSelector, 0);
                CGRect.ByValue screenFrame = MacosApi.INSTANCE.objc_msgSend_retCGRect(mainScreen, frameSelector);
                double screenHeight = screenFrame.size.height;
                
                // contentView의 프레임은 윈도우 내부 좌표일 수 있으므로, 윈도우 자체의 y좌표를 더해줘야 할 수 있습니다.
                // 더 정확한 방법은 convertRectToScreen: 셀렉터를 사용하는 것입니다.
                
                // 하지만 가장 간단한 접근법으로 돌아가겠습니다.
                // JavaFX가 생각하는 Scene의 Y 좌표와 Stage의 Y좌표 차이.
                offset = stage.getScene().getY(); // Scene의 창 내 Y 위치
                
                if (offset > 1 && offset < 100) {
                     System.out.println("MacosNativeUtil: 오프셋 계산 성공! [Scene Y: " + offset + "]");
                     cachedTitleBarOffset = offset;
                } else {
                     // 최후의 방법: 하드코딩된 값. Big Sur 이후 macOS의 표준 타이틀바 높이는 약 28-29pt 입니다.
                     cachedTitleBarOffset = 28.0;
                     System.out.println("MacosNativeUtil: 모든 계산 실패. 기본값 28.0으로 설정.");
                }

            }
            return cachedTitleBarOffset > 0 ? cachedTitleBarOffset : 0.0;

        } catch (Exception e) {
            // ...
             // 최후의 방법: 하드코딩된 값
             cachedTitleBarOffset = 28.0;
             return cachedTitleBarOffset;
        }
    }

    public static double getTitleBarHeightOffset() {
        return cachedTitleBarOffset > 0 ? cachedTitleBarOffset : 0.0;
    }
}