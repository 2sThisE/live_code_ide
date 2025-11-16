package com.ethis2s.service;

import com.ethis2s.util.HybridManager;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.PlainTextChange;
import org.fxmisc.richtext.model.TwoDimensional.Bias;

public class InputInterpreter {

    private final HybridManager manager;
    private final CodeArea codeArea;

    public InputInterpreter(HybridManager manager, CodeArea codeArea) {
        this.manager = manager;
        this.codeArea = codeArea;
    }

    /**
     * Interprets a user-initiated text change, checks for line locks,
     * and sends the appropriate edit operation to the server.
     * @param change The text change to interpret.
     */
    public void interpretAndSend(PlainTextChange change) {
        int startPos = change.getPosition();
        int startLine = codeArea.offsetToPosition(startPos, Bias.Forward).getMajor();

        if (manager.isLineLockedByOther(startLine)) return;
        
        String removed = change.getRemoved();
        String inserted = change.getInserted();

        if (!removed.isEmpty()) {
            manager.requestFileEditOperation("DELETE", change.getPosition(), "", removed.length());
        }
        if (!inserted.isEmpty()) {
            manager.requestFileEditOperation("INSERT", change.getPosition(), inserted, 0);
        }
    }
}
