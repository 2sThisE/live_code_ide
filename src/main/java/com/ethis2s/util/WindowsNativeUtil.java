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
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.stage.Stage;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

public final class WindowsNativeUtil {

    public static class HMONITOR extends WinDef.HWND {
        public HMONITOR() { }
        public HMONITOR(Pointer p) { super(p); }
    }

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

        @Structure.FieldOrder({"x", "y"})
        class POINT extends Structure {
            public int x, y;
        }

        @Structure.FieldOrder({"ptReserved", "ptMaxSize", "ptMaxPosition", "ptMinTrackSize", "ptMaxTrackSize"})
        class MINMAXINFO extends Structure {
            public POINT ptReserved;
            public POINT ptMaxSize;
            public POINT ptMaxPosition;
            public POINT ptMinTrackSize;
            public POINT ptMaxTrackSize;
            public MINMAXINFO() { super(); }
            public MINMAXINFO(Pointer p) { super(p); }
             @Override
            protected List<String> getFieldOrder() {
                return Arrays.asList("ptReserved", "ptMaxSize", "ptMaxPosition", "ptMinTrackSize", "ptMaxTrackSize");
            }
        }

        @Structure.FieldOrder({"cbSize", "rcMonitor", "rcWork", "dwFlags"})
        class MONITORINFO extends Structure {
            public int cbSize = size();
            public WinDef.RECT rcMonitor;
            public WinDef.RECT rcWork;
            public int dwFlags;
        }

        class NCCALCSIZE_PARAMS extends Structure {
            public WinDef.RECT[] rgrc = new WinDef.RECT[3];
            public Pointer lppos;

            public NCCALCSIZE_PARAMS() { super(); }
            public NCCALCSIZE_PARAMS(Pointer p) { super(p); }

            @Override
            protected List<String> getFieldOrder() {
                return Arrays.asList("rgrc", "lppos");
            }
        }

        boolean GetWindowRect(WinDef.HWND hWnd, WinDef.RECT lpRect);
        BaseTSD.LONG_PTR GetWindowLongPtr(WinDef.HWND hWnd, int nIndex);
        BaseTSD.LONG_PTR SetWindowLongPtr(WinDef.HWND hWnd, int nIndex, BaseTSD.LONG_PTR dwNewLong);
        boolean SetWindowPos(WinDef.HWND hWnd, WinDef.HWND hWndInsertAfter, int X, int Y, int cx, int cy, int uFlags);
        HMONITOR MonitorFromWindow(WinDef.HWND hWnd, int dwFlags);
        boolean GetMonitorInfoW(HMONITOR hMonitor, MONITORINFO lpmi);


        int GWL_STYLE = -16;
        int SWP_FRAMECHANGED = 0x0020;
        int SWP_NOMOVE = 0x0002;
        int SWP_NOSIZE = 0x0001;
        int SWP_NOZORDER = 0x0004;
        long WS_CAPTION = 0x00C00000L;
        long WS_SYSMENU = 0x00080000L;
        long WS_THICKFRAME = 0x00040000L;
        long WS_MINIMIZEBOX = 0x00020000L;
        long WS_MAXIMIZEBOX = 0x00010000L;
        long WS_MAXIMIZE = 0x01000000L;
        int MONITOR_DEFAULTTONEAREST = 0x00000002;
        int WM_GETMINMAXINFO = 0x0024;
    }

    private interface Comctl32 extends StdCallLibrary {
        Comctl32 INSTANCE = Native.load("comctl32", Comctl32.class, W32APIOptions.DEFAULT_OPTIONS);
        boolean SetWindowSubclass(WinDef.HWND hWnd, SUBCLASSPROC pfnSubclass, BaseTSD.LONG_PTR uIdSubclass, BaseTSD.DWORD_PTR dwRefData);
        WinDef.LRESULT DefSubclassProc(WinDef.HWND hWnd, int uMsg, WinDef.WPARAM wParam, WinDef.LPARAM lParam);
    }

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

    private static final int WM_NCCALCSIZE = 0x0083, WM_NCHITTEST = 0x0084;
    private static final int HTCLIENT = 1, HTCAPTION = 2;
    private static final int HTLEFT = 10, HTRIGHT = 11, HTTOP = 12, HTTOPLEFT = 13, HTTOPRIGHT = 14;
    private static final int HTBOTTOM = 15, HTBOTTOMLEFT = 16, HTBOTTOMRIGHT = 17;

    private static SUBCLASSPROC subclassProc;

    public static void applyCustomWindowStyle(Stage stage, Node draggableArea, List<Node> nonDraggableNodes) {
        try {
            WinDef.HWND hwnd = getHwnd(stage);
            if (hwnd == null) return;

            subclassProc = (hWnd, uMsg, wParam, lParam, uIdSubclass, dwRefData) -> {
                LRESULT_ByReference lResult = new LRESULT_ByReference();
                if (Dwmapi.INSTANCE.DwmDefWindowProc(hWnd, uMsg, wParam, lParam, lResult)) {
                    return lResult.getValue();
                }

                switch (uMsg) {
                    case User32.WM_GETMINMAXINFO:
                        // 최대화 관련 로직 완전 제거
                        return Comctl32.INSTANCE.DefSubclassProc(hWnd, uMsg, wParam, lParam);
                    
                    case WM_NCCALCSIZE:
                        if (wParam.intValue() == 1) {
                            // 클라이언트 영역을 창 전체로 확장하여 기본 프레임을 제거
                            return new WinDef.LRESULT(0);
                        }
                        break;

                    case WM_NCHITTEST: {
                        final int x = (int)(short)(lParam.longValue() & 0xFFFF);
                        final int y = (int)(short)((lParam.longValue() >> 16) & 0xFFFF);

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

                        if (checkHit(draggableArea, x, y)) {
                            if (isCursorOnNodes(nonDraggableNodes, x, y)) {
                                return new WinDef.LRESULT(HTCLIENT);
                            }
                            return new WinDef.LRESULT(HTCAPTION);
                        }
                        return new WinDef.LRESULT(HTCLIENT);
                    }
                }
                return Comctl32.INSTANCE.DefSubclassProc(hWnd, uMsg, wParam, lParam);
            };
            Comctl32.INSTANCE.SetWindowSubclass(hwnd, subclassProc, new BaseTSD.LONG_PTR(1), new BaseTSD.DWORD_PTR(0));

            // maximizedProperty 리스너 완전 제거

            BaseTSD.LONG_PTR originalStyle = User32.INSTANCE.GetWindowLongPtr(hwnd, User32.GWL_STYLE);
            long newStyle = originalStyle.longValue() | User32.WS_CAPTION | User32.WS_SYSMENU | User32.WS_THICKFRAME | User32.WS_MINIMIZEBOX | User32.WS_MAXIMIZEBOX;
            User32.INSTANCE.SetWindowLongPtr(hwnd, User32.GWL_STYLE, new BaseTSD.LONG_PTR(newStyle));

            Dwmapi.MARGINS margins = new Dwmapi.MARGINS();
            margins.cxLeftWidth = 1;
            margins.cxRightWidth = 1;
            margins.cyTopHeight = 1;
            margins.cyBottomHeight = 1;
            Dwmapi.INSTANCE.DwmExtendFrameIntoClientArea(hwnd, margins);

            User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, 0, 0,
                User32.SWP_FRAMECHANGED | User32.SWP_NOMOVE | User32.SWP_NOSIZE | User32.SWP_NOZORDER);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void applyCustomAlertWindowStyle(Stage stage, Node draggableArea, List<Node> nonDraggableNodes) {
        try {
            WinDef.HWND hwnd = getHwnd(stage);
            if (hwnd == null) return;

            subclassProc = (hWnd, uMsg, wParam, lParam, uIdSubclass, dwRefData) -> {
                LRESULT_ByReference lResult = new LRESULT_ByReference();
                if (Dwmapi.INSTANCE.DwmDefWindowProc(hWnd, uMsg, wParam, lParam, lResult)) {
                    return lResult.getValue();
                }

                switch (uMsg) {
                    case WM_NCCALCSIZE:
                        if (wParam.intValue() == 1) {
                            return new WinDef.LRESULT(0); // 기본 프레임 제거
                        }
                        break;

                    case WM_NCHITTEST: {
                        final int x = (int)(short)(lParam.longValue() & 0xFFFF);
                        final int y = (int)(short)((lParam.longValue() >> 16) & 0xFFFF);

                        // 크기 조절 로직 완전 제거
                        if (checkHit(draggableArea, x, y)) {
                            if (isCursorOnNodes(nonDraggableNodes, x, y)) {
                                return new WinDef.LRESULT(HTCLIENT);
                            }
                            return new WinDef.LRESULT(HTCAPTION);
                        }
                        return new WinDef.LRESULT(HTCLIENT);
                    }
                }
                return Comctl32.INSTANCE.DefSubclassProc(hWnd, uMsg, wParam, lParam);
            };
            Comctl32.INSTANCE.SetWindowSubclass(hwnd, subclassProc, new BaseTSD.LONG_PTR(1), new BaseTSD.DWORD_PTR(0));

            BaseTSD.LONG_PTR originalStyle = User32.INSTANCE.GetWindowLongPtr(hwnd, User32.GWL_STYLE);
            // 크기 조절(WS_THICKFRAME)과 최대화 버튼(WS_MAXIMIZEBOX) 스타일 제거
            long newStyle = originalStyle.longValue() | User32.WS_CAPTION | User32.WS_SYSMENU;
            User32.INSTANCE.SetWindowLongPtr(hwnd, User32.GWL_STYLE, new BaseTSD.LONG_PTR(newStyle));

            Dwmapi.MARGINS margins = new Dwmapi.MARGINS();
            margins.cxLeftWidth = 1;
            margins.cxRightWidth = 1;
            margins.cyTopHeight = 1;
            margins.cyBottomHeight = 1;
            Dwmapi.INSTANCE.DwmExtendFrameIntoClientArea(hwnd, margins);

            User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, 0, 0,
                User32.SWP_FRAMECHANGED | User32.SWP_NOMOVE | User32.SWP_NOSIZE | User32.SWP_NOZORDER);

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

    public static void printNativeWindowSize(Stage stage) {
        WinDef.HWND hwnd = getHwnd(stage);
        if (hwnd != null) {
            WinDef.RECT rect = new WinDef.RECT();
            User32.INSTANCE.GetWindowRect(hwnd, rect);
            System.out.printf("Native Window Size: w=%d, h=%d\n", rect.right - rect.left, rect.bottom - rect.top);
        }
    }

    public static void printMaximizeDebugInfo(Stage stage) {
        WinDef.HWND hwnd = getHwnd(stage);
        if (hwnd != null) {
            // Get Native Window Info
            WinDef.RECT rect = new WinDef.RECT();
            User32.INSTANCE.GetWindowRect(hwnd, rect);
            System.out.printf("Native Window Rect: x=%d, y=%d, w=%d, h=%d\n",
                    rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top);

            // Get Monitor Info
            HMONITOR hMonitor = User32.INSTANCE.MonitorFromWindow(hwnd, User32.MONITOR_DEFAULTTONEAREST);
            User32.MONITORINFO monitorInfo = new User32.MONITORINFO();
            User32.INSTANCE.GetMonitorInfoW(hMonitor, monitorInfo);
            WinDef.RECT workArea = monitorInfo.rcWork;
            System.out.printf("Monitor Work Area:  x=%d, y=%d, w=%d, h=%d\n",
                    workArea.left, workArea.top, workArea.right - workArea.left, workArea.bottom - workArea.top);
        }
    }
}