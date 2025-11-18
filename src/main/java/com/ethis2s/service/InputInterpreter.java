package com.ethis2s.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.ethis2s.model.Operation;
import com.ethis2s.util.HybridManager;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.PlainTextChange;

public class InputInterpreter {

    private final HybridManager manager;
    private final CodeArea codeArea;

    public InputInterpreter(HybridManager manager, CodeArea codeArea) {
        this.manager = manager;
        this.codeArea = codeArea;
    }

    /**
     * Interprets a PlainTextChange and sends it to the HybridManager as a list of Operations.
     * @param change The change event from the CodeArea.
     * @return A list of generated Operation objects.
     */
    public List<Operation> interpret(PlainTextChange change) {
        String inserted = change.getInserted();
        String removed = change.getRemoved();
        int position = change.getPosition();
        int cursorPosition = codeArea.getCaretPosition(); // Get current caret position after the change

        if (!inserted.isEmpty() && removed.isEmpty()) {
            // INSERT operation
            Operation op = new Operation(Operation.Type.INSERT, position, inserted, cursorPosition, -1, null);
            return Collections.singletonList(op);
        } else if (inserted.isEmpty() && !removed.isEmpty()) {
            // DELETE operation
            Operation op = new Operation(Operation.Type.DELETE, position, removed, removed.length(), cursorPosition, -1, null);
            return Collections.singletonList(op);
        } else if (!inserted.isEmpty() && !removed.isEmpty()) {
            // REPLACE operation, modeled as DELETE then INSERT
            List<Operation> ops = new ArrayList<>();
            ops.add(new Operation(Operation.Type.DELETE, position, removed, removed.length(), -1, -1, null)); // Cursor pos is irrelevant for the intermediate delete
            ops.add(new Operation(Operation.Type.INSERT, position, inserted, cursorPosition, -1, null));
            return ops;
        }
        return Collections.emptyList(); // No actual change
    }
}
