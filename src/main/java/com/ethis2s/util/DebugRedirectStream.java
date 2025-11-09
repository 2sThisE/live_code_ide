package com.ethis2s.util;

import java.io.OutputStream;
import java.io.PrintStream;
import com.ethis2s.view.DebugView;

public class DebugRedirectStream extends PrintStream {

    private DebugView debugView;

    public DebugRedirectStream(OutputStream out, DebugView debugView) {
        super(out);
        this.debugView = debugView;
    }

    @Override
    public void write(byte[] buf, int off, int len) {
        super.write(buf, off, len);
        String message = new String(buf, off, len);
        debugView.appendText(message);
    }
}
