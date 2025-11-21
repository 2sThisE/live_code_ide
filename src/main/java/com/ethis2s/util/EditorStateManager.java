package com.ethis2s.util;

import com.ethis2s.service.AntlrLanguageService.SyntaxError;
import com.ethis2s.service.RemoteCursorManager;

import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import org.fxmisc.richtext.CodeArea;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 모든 에디터 탭의 상태를 중앙에서 관리하는 클래스입니다.
 * 탭 ID를 키로 사용하여 CodeArea, HybridManager, 에러 목록, 검색 결과 등을 관리합니다.
 */
public class EditorStateManager {

    public static class UserLockInfo {
        public final String userId;
        public final String userNickname;

        public UserLockInfo(String userId, String userNickname) {
            this.userId = userId;
            this.userNickname = userNickname;
        }
    }

    // --- State Fields ---
    private final Map<String, CodeArea> codeAreaMap = new HashMap<>();
    private final Map<String, HybridManager> hybridManagerMap = new HashMap<>();
    private final Map<String, List<SyntaxError>> tabErrors = new HashMap<>();
    private final Map<String, String> tabFileNames = new HashMap<>();
    private final Map<String, List<Integer>> searchResultsMap = new HashMap<>();
    private final Map<String, Integer> currentMatchIndexMap = new HashMap<>();
    private final Map<String, Map<Integer, UserLockInfo>> lineLocks = new HashMap<>();
    private final Map<String, RemoteCursorManager> remoteCursorManagerMap = new HashMap<>();
    private final List<HybridManager> activeManagers = new ArrayList<>();
    private final Map<String, Boolean> initializingTabs = new HashMap<>();
    private final Map<String, Queue<Runnable>> pendingUpdatesMap = new HashMap<>();
    private final Map<String, OTManager> otManagers = new ConcurrentHashMap<>();

    // --- Search Properties ---
    private final IntegerProperty totalMatches = new SimpleIntegerProperty(0);
    private final IntegerProperty currentMatchIndex = new SimpleIntegerProperty(0);

    // --- Public API for State Management ---

    public void registerTab(String tabId, String fileName, CodeArea codeArea, HybridManager manager) {
        codeAreaMap.put(tabId, codeArea);
        hybridManagerMap.put(tabId, manager);
        tabFileNames.put(tabId, fileName);
        tabErrors.put(tabId, new ArrayList<>());
        searchResultsMap.put(tabId, new ArrayList<>());
        currentMatchIndexMap.put(tabId, -1);
        lineLocks.put(tabId, new HashMap<>());
        initializingTabs.put(tabId, true); // Start in initializing state
        pendingUpdatesMap.put(tabId, new ConcurrentLinkedQueue<>());
        activeManagers.add(manager);
    }

    public void registerCursorManager(String tabId, RemoteCursorManager manager) {
        remoteCursorManagerMap.put(tabId, manager);
    }

    public void unregisterTab(String tabId) {
        HybridManager manager = hybridManagerMap.remove(tabId);
        if (manager != null) {
            manager.shutdown();
            activeManagers.remove(manager);
        }
        codeAreaMap.remove(tabId);
        tabFileNames.remove(tabId);
        tabErrors.remove(tabId);
        searchResultsMap.remove(tabId);
        currentMatchIndexMap.remove(tabId);
        lineLocks.remove(tabId);
        remoteCursorManagerMap.remove(tabId);
        initializingTabs.remove(tabId);
        pendingUpdatesMap.remove(tabId);
        otManagers.remove(tabId);
    }

    public void shutdownAllManagers() {
        activeManagers.forEach(HybridManager::shutdown);
        activeManagers.clear();
    }

    public Optional<RemoteCursorManager> getCursorManager(String tabId) {
        return Optional.ofNullable(remoteCursorManagerMap.get(tabId));
    }

    // --- Initialization and Update Queue Management ---

    public boolean isInitializing(String tabId) {
        return initializingTabs.getOrDefault(tabId, false);
    }

    public void setInitializing(String tabId, boolean isInitializing) {
        initializingTabs.put(tabId, isInitializing);
    }

    public void queueUpdate(String tabId, Runnable update) {
        Queue<Runnable> queue = pendingUpdatesMap.get(tabId);
        if (queue != null) {
            queue.add(update);
        }
    }

    public void processPendingUpdates(String tabId) {
        Queue<Runnable> queue = pendingUpdatesMap.get(tabId);
        if (queue != null) {
            while (!queue.isEmpty()) {
                Platform.runLater(queue.poll());
            }
        }
    }
    public void registerOTManager(String tabId, OTManager otManager) {
        this.otManagers.put(tabId, otManager);
    }
    public Optional<OTManager> getOTManager(String tabId) {
        return Optional.ofNullable(this.otManagers.get(tabId));
    }


    public boolean isOTPaused(String tabId) {
        // [핵심 수정] OTManager가 존재하지 않는 것을 '일시정지' 상태로 간주합니다.
        return !otManagers.containsKey(tabId);
    }

    public void disposeOT(String tabId) {
        getOTManager(tabId).ifPresent(OTManager::dispose);
        otManagers.remove(tabId);
        System.out.println("[StateManager] Disposed and removed OTManager for " + tabId);
    }

    // --- Getters and Helpers ---

    public Optional<CodeArea> getCodeArea(String tabId) {
        return Optional.ofNullable(codeAreaMap.get(tabId));
    }

    public Optional<HybridManager> getHybridManager(String tabId) {
        return Optional.ofNullable(hybridManagerMap.get(tabId));
    }

    public Map<String, List<SyntaxError>> getAllTabErrors() {
        return tabErrors;
    }
    
    public List<SyntaxError> getErrorsForTab(String tabId) {
        return tabErrors.getOrDefault(tabId, Collections.emptyList());
    }

    public void updateErrorsForTab(String tabId, List<SyntaxError> errors) {
        tabErrors.put(tabId, errors);
    }

    public void updateLineLock(String tabId, int line, String userId, String userNickname) {
        Map<Integer, UserLockInfo> locks = lineLocks.get(tabId);
        if (locks == null) return;

        if (userId == null || userId.isEmpty()) {locks.remove(line);}
        else {locks.put(line, new UserLockInfo(userId, userNickname));}
    }

    public Optional<UserLockInfo> getLineLockInfo(String tabId, int line) {
        Map<Integer, UserLockInfo> locks = lineLocks.get(tabId);
        if (locks == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(locks.get(line));
    }

    public boolean isLineLockedByCurrentUser(String tabId, int line, String currentUserId) {
        Map<Integer, UserLockInfo> locks = lineLocks.get(tabId);
        
        if (currentUserId == null || currentUserId.isEmpty()) {
            return false;
        }
        return getLineLockInfo(tabId, line)
                .map(lockInfo -> currentUserId.equals(lockInfo.userId))
                .orElse(false);
    }

    public Optional<String> getFileName(String tabId) {
        return Optional.ofNullable(tabFileNames.get(tabId));
    }

    public Optional<String> findTabIdForCodeArea(CodeArea codeArea) {
        return codeAreaMap.entrySet().stream()
            .filter(entry -> entry.getValue().equals(codeArea))
            .map(Map.Entry::getKey)
            .findFirst();
    }

    public static class OpenFileState {
        private final String fileName;
        private final String filePath;
        private final boolean otPaused;

        public OpenFileState(String fileName, String filePath, boolean otPaused) {
            this.fileName = fileName;
            this.filePath = filePath;
            this.otPaused = otPaused;
        }

        public String getFileName() { return fileName; }
        public String getFilePath() { return filePath; }
        public boolean isOtPaused() { return otPaused; }
    }

    public Map<String, OpenFileState> getAllOpenFileStates() {
        Map<String, OpenFileState> states = new HashMap<>();
        // We should iterate over tabFileNames as it represents all registered file tabs
        for (String tabId : tabFileNames.keySet()) {
            if (tabId.startsWith("file-")) {
                String filePath = tabId.substring(5);
                String fileName = tabFileNames.get(tabId);
                boolean otPaused = isOTPaused(tabId);
                states.put(tabId, new OpenFileState(fileName, filePath, otPaused));
            }
        }
        return states;
    }
    
    public Collection<CodeArea> getAllCodeAreas() {
        return codeAreaMap.values();
    }

    public List<Integer> getSearchResults(String tabId) {
        return searchResultsMap.computeIfAbsent(tabId, k -> new ArrayList<>());
    }
    
    public int getCurrentMatchIndex(String tabId) {
        return currentMatchIndexMap.getOrDefault(tabId, -1);
    }

    public void setCurrentMatchIndex(String tabId, int index) {
        currentMatchIndexMap.put(tabId, index);
    }

    public IntegerProperty totalMatchesProperty() {
        return totalMatches;
    }

    public IntegerProperty currentMatchIndexProperty() {
        return currentMatchIndex;
    }
}