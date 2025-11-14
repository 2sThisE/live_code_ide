package com.ethis2s.util;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.win32.W32APIOptions;
import javafx.stage.Stage;

import java.lang.reflect.Field;

/**
 * JNA를 사용하여 Windows의 네이티브 윈도우 기능을 직접 제어하는 유틸리티 클래스.
 * 커스텀 타이틀 바 드래그 및 리사이즈를 위해 WM_NCHITTEST 메시지를 처리한다.
 */
public final class WindowsNativeUtil {

    private interface User32Ex extends User32 {
        User32Ex INSTANCE = Native.load("user32", User32Ex.class, W32APIOptions.DEFAULT_OPTIONS);

        // SetWindowLongPtr와 GetWindowLongPtr는 32비트/64비트 호환성을 위해 필요
        BaseTSD.LONG_PTR GetWindowLongPtr(WinDef.HWND hWnd, int nIndex);
        BaseTSD.LONG_PTR SetWindowLongPtr(WinDef.HWND hWnd, int nIndex, User32.WindowProc wndProc);
        WinDef.LRESULT CallWindowProc(BaseTSD.LONG_PTR lpPrevWndFunc, WinDef.HWND hWnd, int uMsg, WinDef.WPARAM wParam, WinDef.LPARAM lParam);
    }

    private static final int WM_NCHITTEST = 0x0084;
    private static final int GWLP_WNDPROC = -4;

    // 히트 테스트 결과 상수들
    private static final int HTCLIENT = 1;  // 클라이언트 영역 (컨텐츠)
    private static final int HTCAPTION = 2; // 타이틀 바
    private static final int HTTOP = 12;
    private static final int HTBOTTOM = 15;
    private static final int HTLEFT = 10;
    private static final int HTRIGHT = 11;
    private static final int HTTOPLEFT = 13;
    private static final int HTTOPRIGHT = 14;
    private static final int HTBOTTOMLEFT = 16;
    private static final int HTBOTTOMRIGHT = 17;

    private static BaseTSD.LONG_PTR defaultWndProc;
    private static User32.WindowProc customWndProc;
    private static Stage stage;

    /**
     * 주어진 Stage에 대해 커스텀 창 드래그 및 리사이즈 기능을 활성화한다.
     * 이 메소드는 반드시 stage.show()가 호출된 이후에 실행되어야 한다.
     * @param stage 기능을 적용할 대상 Stage
     * @param titleBarHeight 드래그 가능한 타이틀 바의 높이 (px)
     * @param resizeBorder 리사이즈 가능한 테두리의 두께 (px)
     */
    public static void enableCustomWindowBehavior(Stage stage, int titleBarHeight, int resizeBorder) {
        WindowsNativeUtil.stage = stage;
        try {
            // 1. 네이티브 윈도우 핸들(HWND) 획득
            WinDef.HWND hwnd = getHwnd(stage);

            // 2. 새로운 윈도우 프로시저(메시지 핸들러) 정의
            customWndProc = (hWnd, uMsg, wParam, lParam) -> {
                if (uMsg == WM_NCHITTEST) {
                    // 마우스 좌표 계산
                    int screenX = (int) (lParam.longValue() & 0xFFFF);
                    int screenY = (int) ((lParam.longValue() >> 16) & 0xFFFF);
                    double stageX = WindowsNativeUtil.stage.getX();
                    double stageY = WindowsNativeUtil.stage.getY();
                    double stageW = WindowsNativeUtil.stage.getWidth();
                    double stageH = WindowsNativeUtil.stage.getHeight();

                    boolean onTop = screenY >= stageY && screenY < stageY + resizeBorder;
                    boolean onBottom = screenY < stageY + stageH && screenY >= stageY + stageH - resizeBorder;
                    boolean onLeft = screenX >= stageX && screenX < stageX + resizeBorder;
                    boolean onRight = screenX < stageX + stageW && screenX >= stageX + stageW - resizeBorder;

                    if (onTop && onLeft) return new WinDef.LRESULT(HTTOPLEFT);
                    if (onTop && onRight) return new WinDef.LRESULT(HTTOPRIGHT);
                    if (onBottom && onLeft) return new WinDef.LRESULT(HTBOTTOMLEFT);
                    if (onBottom && onRight) return new WinDef.LRESULT(HTBOTTOMRIGHT);
                    if (onTop) return new WinDef.LRESULT(HTTOP);
                    if (onBottom) return new WinDef.LRESULT(HTBOTTOM);
                    if (onLeft) return new WinDef.LRESULT(HTLEFT);
                    if (onRight) return new WinDef.LRESULT(HTRIGHT);

                    if (screenY >= stageY && screenY < stageY + titleBarHeight) {
                        return new WinDef.LRESULT(HTCAPTION);
                    }
                    
                    // 그 외에는 모두 클라이언트 영역
                    return new WinDef.LRESULT(HTCLIENT);
                }
                // 다른 메시지들은 원래 프로시저에게 전달
                return User32Ex.INSTANCE.CallWindowProc(defaultWndProc, hWnd, uMsg, wParam, lParam);
            };

            // 3. 기존 윈도우 프로시저를 저장하고, 우리의 커스텀 프로시저로 교체 (서브클래싱)
            defaultWndProc = User32Ex.INSTANCE.GetWindowLongPtr(hwnd, GWLP_WNDPROC);
            User32Ex.INSTANCE.SetWindowLongPtr(hwnd, GWLP_WNDPROC, customWndProc);

            System.out.println("WindowsNativeUtil: 커스텀 윈도우 동작이 성공적으로 적용되었습니다.");

        } catch (Exception e) {
            System.err.println("WindowsNativeUtil: 커스텀 윈도우 동작 적용 중 예외 발생");
            e.printStackTrace();
        }
    }

    /**
     * 리플렉션을 사용하여 Stage에서 네이티브 HWND 핸들을 추출한다.
     */
    private static WinDef.HWND getHwnd(Stage stage) throws Exception {
        Field peerField = javafx.stage.Window.class.getDeclaredField("peer");
        peerField.setAccessible(true);
        Object peer = peerField.get(stage);

        Field platformWindowField = peer.getClass().getDeclaredField("platformWindow");
        platformWindowField.setAccessible(true);
        Object platformWindow = platformWindowField.get(peer);

        Field ptrField = com.sun.glass.ui.Window.class.getDeclaredField("ptr");
        ptrField.setAccessible(true);
        long hwndPtr = ptrField.getLong(platformWindow);

        return new WinDef.HWND(new Pointer(hwndPtr));
    }
}