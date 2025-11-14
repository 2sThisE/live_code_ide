package com.ethis2s.util;

import com.sun.jna.Callback;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.ByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.stage.Stage;

import java.lang.reflect.Method;
import java.util.List;

public final class WindowsNativeUtil {

    // --- JNA Interfaces ---
    private interface Dwmapi extends StdCallLibrary {
        Dwmapi INSTANCE = Native.load("dwmapi", Dwmapi.class, W32APIOptions.DEFAULT_OPTIONS);
        @Structure.FieldOrder({"cxLeftWidth", "cxRightWidth", "cyTopHeight", "cyBottomHeight"})
        class MARGINS extends Structure {
            public int cxLeftWidth, cxRightWidth, cyTopHeight, cyBottomHeight;
        }
        WinNT.HRESULT DwmExtendFrameIntoClientArea(WinDef.HWND hWnd, MARGINS pMarInset);
        boolean DwmDefWindowProc(WinDef.HWND hWnd, int msg, WinDef.WPARAM wParam, WinDef.LPARAM lParam, LRESULT_ByReference plResult);
    }

    private interface User32 extends StdCallLibrary {
        User32 INSTANCE = Native.load("user32", User32.class, W32APIOptions.DEFAULT_OPTIONS);
        boolean GetWindowRect(WinDef.HWND hWnd, WinDef.RECT lpRect);
        boolean SetWindowPos(WinDef.HWND hWnd, WinDef.HWND hWndInsertAfter, int X, int Y, int cx, int cy, int uFlags);

        int SWP_FRAMECHANGED = 0x0020;
        int SWP_NOMOVE = 0x0002;
        int SWP_NOSIZE = 0x0001;
        int SWP_NOZORDER = 0x0004;
    }

    private interface Comctl32 extends StdCallLibrary {
        Comctl32 INSTANCE = Native.load("comctl32", Comctl32.class, W32APIOptions.DEFAULT_OPTIONS);
        boolean SetWindowSubclass(WinDef.HWND hWnd, SUBCLASSPROC pfnSubclass, BaseTSD.LONG_PTR uIdSubclass, BaseTSD.DWORD_PTR dwRefData);
        WinDef.LRESULT DefSubclassProc(WinDef.HWND hWnd, int uMsg, WinDef.WPARAM wParam, WinDef.LPARAM lParam);
    }

    // --- JNA Callbacks & Structures ---
    public interface SUBCLASSPROC extends Callback {
        WinDef.LRESULT callback(WinDef.HWND hWnd, int uMsg, WinDef.WPARAM wParam, WinDef.LPARAM lParam, BaseTSD.LONG_PTR uIdSubclass, BaseTSD.DWORD_PTR dwRefData);
    }

    public static class LRESULT_ByReference extends ByReference {
        public LRESULT_ByReference() { this(new WinDef.LRESULT(0)); }
        public LRESULT_ByReference(WinDef.LRESULT value) {
            super(Native.POINTER_SIZE);
            setValue(value);
        }
        public void setValue(WinDef.LRESULT value) { getPointer().setLong(0, value.longValue()); }
        public WinDef.LRESULT getValue() { return new WinDef.LRESULT(getPointer().getLong(0)); }
    }

    // --- Constants ---
    private static final int WM_NCCALCSIZE = 0x0083, WM_NCHITTEST = 0x0084;
    private static final int HTCLIENT = 1;
    private static final int HTLEFT = 10, HTRIGHT = 11, HTTOP = 12, HTTOPLEFT = 13, HTTOPRIGHT = 14;
    private static final int HTBOTTOM = 15, HTBOTTOMLEFT = 16, HTBOTTOMRIGHT = 17;

    private static SUBCLASSPROC subclassProc; // Keep a reference

    public static void applyCustomWindowStyle(Stage stage, List<Node> clickableNodes, Node minimizeButton, Node maximizeButton, Node closeButton) {
        try {
            System.out.println("[DEBUG] applyCustomWindowStyle called.");
            WinDef.HWND hwnd = getHwnd(stage);
            if (hwnd == null) {
                System.err.println("[DEBUG] HWND is NULL. Aborting.");
                return;
            }
            System.out.println("[DEBUG] HWND found: " + hwnd);

            subclassProc = (hWnd, uMsg, wParam, lParam, uIdSubclass, dwRefData) -> {
                // 모든 메시지를 DWM에 먼저 전달하여 네이티브 컨트롤 상호작용을 처리하게 합니다.
                LRESULT_ByReference lResult = new LRESULT_ByReference();
                boolean handledByDwm = Dwmapi.INSTANCE.DwmDefWindowProc(hWnd, uMsg, wParam, lParam, lResult);

                if (handledByDwm) {
                    return lResult.getValue();
                }

                // DWM이 처리하지 않은 메시지만 직접 처리합니다.
                switch (uMsg) {
                    case WM_NCCALCSIZE:
                        System.out.println("[DEBUG] WM_NCCALCSIZE received. Hiding title bar.");
                        // 전체를 클라이언트 영역으로 만들어 네이티브 타이틀바를 숨깁니다.
                        if (wParam.intValue() == 1) return new WinDef.LRESULT(0);
                        break;

                    case WM_NCHITTEST: {
                        final int x = (int)(short)(lParam.longValue() & 0xFFFF);
                        final int y = (int)(short)((lParam.longValue() >> 16) & 0xFFFF);

                        // 클릭 가능한 JavaFX UI 영역은 HTCLIENT로 처리합니다.
                        if (isCursorOnNodes(clickableNodes, x, y)) {
                            return new WinDef.LRESULT(HTCLIENT);
                        }

                        // 창 크기 조절 테두리를 처리합니다.
                        WinDef.RECT rect = new WinDef.RECT();
                        User32.INSTANCE.GetWindowRect(hWnd, rect);
                        int resizeBorder = 8;
                        boolean onTop = y >= rect.top && y < rect.top + resizeBorder;
                        boolean onBottom = y < rect.bottom && y >= rect.bottom - resizeBorder;
                        boolean onLeft = x >= rect.left && x < rect.left + resizeBorder;
                        boolean onRight = x < rect.right && x >= rect.right - resizeBorder;

                        if (onTop && onLeft) return new WinDef.LRESULT(HTTOPLEFT);
                        if (onTop && onRight) return new WinDef.LRESULT(HTTOPRIGHT);
                        if (onBottom && onLeft) return new WinDef.LRESULT(HTBOTTOMLEFT);
                        if (onBottom && onRight) return new WinDef.LRESULT(HTBOTTOMRIGHT);
                        if (onTop) return new WinDef.LRESULT(HTTOP);
                        if (onBottom) return new WinDef.LRESULT(HTBOTTOM);
                        if (onLeft) return new WinDef.LRESULT(HTLEFT);
                        if (onRight) return new WinDef.LRESULT(HTRIGHT);

                        // 나머지 영역은 기본 프로시저에 맡겨 창 드래그(HTCAPTION)가 가능하게 합니다.
                        break;
                    }
                }
                return Comctl32.INSTANCE.DefSubclassProc(hWnd, uMsg, wParam, lParam);
            };

            Comctl32.INSTANCE.SetWindowSubclass(hwnd, subclassProc, new BaseTSD.LONG_PTR(1), new BaseTSD.DWORD_PTR(0));

            // "Sheet of Glass" 효과를 적용하여 창 전체를 DWM 렌더링 영역으로 만듭니다.
            Dwmapi.MARGINS margins = new Dwmapi.MARGINS();
            margins.cxLeftWidth = -1;
            margins.cxRightWidth = -1;
            margins.cyTopHeight = -1;
            margins.cyBottomHeight = -1;
            WinNT.HRESULT result = Dwmapi.INSTANCE.DwmExtendFrameIntoClientArea(hwnd, margins);
            System.out.println("[DEBUG] DwmExtendFrameIntoClientArea result: " + result);


            // Windows에 프레임 변경을 즉시 적용하도록 강제합니다.
            boolean setResult = User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, 0, 0,
                User32.SWP_FRAMECHANGED | User32.SWP_NOMOVE | User32.SWP_NOSIZE | User32.SWP_NOZORDER);
            System.out.println("[DEBUG] SetWindowPos with SWP_FRAMECHANGED result: " + setResult);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static boolean checkHit(Node node, int screenX, int screenY) {
        if (node == null || !node.isVisible()) return false;
        Bounds bounds = node.localToScreen(node.getBoundsInLocal());
        return bounds != null && bounds.contains(screenX, screenY);
    }

    private static boolean isCursorOnNodes(List<Node> nodes, int screenX, int screenY) {
        if (nodes == null) return false;
        for (Node node : nodes) {
            if (checkHit(node, screenX, screenY)) return true;
        }
        return false;
    }

    @SuppressWarnings({"deprecation"})
    private static WinDef.HWND getHwnd(Stage stage) {
        try {
            Object peer = com.sun.javafx.stage.WindowHelper.getPeer(stage);
            Method getPlatformWindowMethod = peer.getClass().getMethod("getPlatformWindow");
            Object platformWindow = getPlatformWindowMethod.invoke(peer);
            Method getNativeWindowMethod = platformWindow.getClass().getMethod("getNativeWindow");
            long handle = (long) getNativeWindowMethod.invoke(platformWindow);
            return new WinDef.HWND(new Pointer(handle));
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return null;
    }
}