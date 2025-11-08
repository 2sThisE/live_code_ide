package com.ethis2s.service;

import java.util.List;

public interface CompletionService {
    List<String> getSuggestions(String code, int caretPosition);
}
