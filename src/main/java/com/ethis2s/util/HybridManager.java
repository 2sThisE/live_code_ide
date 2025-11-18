package com.ethis2s.util;

import com.ethis2s.controller.ProjectController;
import com.ethis2s.model.Operation;
import com.ethis2s.service.AntlrCompletionService;
import com.ethis2s.service.AntlrLanguageService;
import com.ethis2s.service.EditorInputManager;
import com.ethis2s.service.AntlrLanguageService.AnalysisResult;
import com.ethis2s.service.AntlrLanguageService.BracketPair;
import com.ethis2s.service.AntlrLanguageService.SyntaxError;
import com.ethis2s.service.ChangeInitiator;
import com.ethis2s.util.Tm4eSyntaxHighlighter.StyleToken;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;
import org.antlr.v4.runtime.Token;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class HybridManager {
    
    private static final int LARGE_UPDATE_THRESHOLD = 1000;
    private final CodeArea codeArea;
    private final Tm4eSyntaxHighlighter highlighter;
    private final AntlrLanguageService analyzer;
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private AntlrCompletionService completionService = null;
    private final Consumer<List<SyntaxError>> onErrorUpdate;
    private final Runnable onAnalysisStart;
    private final Runnable onAnalysisFinish;
    private final PauseTransition analysisDebouncer;
    private List<StyleToken> lastTm4eTokens;
    private List<StyleToken> lastErrorTokens;
    private List<StyleToken> lastBracketTokens; // 괄호 위치 데이터의 단일 소스
    private List<StyleToken> lastBracketColorTokens;
    private List<StyleToken> lastSearchHighlightTokens;
    private AnalysisResult lastAnalysisResult;
    private CompletableFuture<AnalysisResult> currentAntlrFuture;
    private long tm4eRequestCounter = 0;

    private boolean isLargeUpdate = false;
    private List<StyleToken> previouslyRenderedBrackets; // 경량 렌더러가 이전에 그렸던 위치를 기억
    private boolean isTyping = false; // 타이핑 상태를 추적할 깃발
    private final ProjectController projectController;
    private String filePath;
    private final EditorStateManager stateManager;
    private EditorInputManager inputManager;

    // --- Version Control ---
    private final OTManager otManager;
    private boolean isApplyingServerChange = false;
    

    public HybridManager(CodeArea codeArea, String fileExtension, Consumer<List<SyntaxError>> onErrorUpdate, Runnable onAnalysisStart, Runnable onAnalysisFinish, ProjectController projectController, String filePath, EditorStateManager stateManager, long initialVersion) {
        this.codeArea = codeArea;
        this.onErrorUpdate = onErrorUpdate;
        this.onAnalysisStart = onAnalysisStart;
        this.onAnalysisFinish = onAnalysisFinish;
        this.projectController = projectController;
        this.filePath = filePath;
        this.stateManager = stateManager;
        
        this.otManager = new OTManager(initialVersion, projectController, this, filePath);
        
        this.highlighter = new Tm4eSyntaxHighlighter(codeArea, fileExtension);

        if (AntlrLanguageService.isSupported(fileExtension)) {
            this.analyzer = new AntlrLanguageService(fileExtension);
            this.completionService = new AntlrCompletionService(this.analyzer);
        } else {
            this.analyzer = null;
            this.completionService = null;
        }
        
        EditorEnhancer enhancer = new EditorEnhancer(codeArea, this.completionService, this);
        this.inputManager = new EditorInputManager(codeArea, enhancer, this.completionService, this);
        this.inputManager.registerEventHandlers();

        this.analysisDebouncer = new PauseTransition(Duration.millis(300));
        this.analysisDebouncer.setOnFinished(e -> {
            runTm4eHighlighting();
            runAntlrAnalysis();
        });

        codeArea.multiPlainChanges()
            .subscribe(changes -> {
                isTyping = true; // 텍스트 변경 시작 -> 깃발 올리기
                if (isLargeUpdate) { /* ... large update logic ... */ 
                    Platform.runLater(() -> isTyping = false);
                    return; 
                }

                if (lastTm4eTokens != null) {
                    for (var change : changes) {
                        int diff = change.getInserted().length() - change.getRemoved().length();
                        int endOfRemoval = change.getPosition() + change.getRemoved().length();

                        // 삭제된 영역과 겹치는 에러 토큰을 미리 제거
                        if (lastErrorTokens != null && change.getRemoved().length() > 0) {
                            lastErrorTokens.removeIf(token -> 
                                token.start < endOfRemoval && token.end > change.getPosition()
                            );
                        }

                        if (diff != 0) {
                            shiftTokens(lastTm4eTokens, change.getPosition(), diff);
                            if (lastAnalysisResult != null && lastAnalysisResult.symbolTokens != null) {
                                shiftTokens(lastAnalysisResult.symbolTokens, change.getPosition(), diff);
                            }
                            if (lastErrorTokens != null) {
                                shiftTokens(lastErrorTokens, change.getPosition(), diff);
                            }
                            if (lastBracketColorTokens != null) {
                                shiftTokens(lastBracketColorTokens, change.getPosition(), diff);
                            }
                            if (lastSearchHighlightTokens != null) {
                                shiftTokens(lastSearchHighlightTokens, change.getPosition(), diff);
                            }
                            // ★★★ 괄호 예측 스타일링 부활! ★★★
                            if (lastBracketTokens != null) {
                                shiftTokens(lastBracketTokens, change.getPosition(), diff);
                            }
                            if (previouslyRenderedBrackets != null) {
                                shiftTokens(previouslyRenderedBrackets, change.getPosition(), diff);
                            }
                        }
                    }
                    // 타이핑 시에는 무거운 전체 렌더러 호출
                    applyHighlighting();
                }
                analysisDebouncer.playFromStart();
                Platform.runLater(() -> isTyping = false); // 모든 작업 후 -> 깃발 내리기 예약
            });
        
        codeArea.caretPositionProperty().addListener((obs, oldPos, newPos) -> {
            if (inputManager.getLastInitiator() == ChangeInitiator.USER) {
                // 커서 이동 시에는 데이터 업데이트 후, 가벼운 괄호 전용 렌더러 호출
                updateBracketHighlightingData();
                renderBracketHighlightOnly();
            }
        });
    }

    // --- OTManager Delegation ---

    public OTManager getOtManager() {
        return otManager;
    }

    public void handleBroadcast(long newVersion, String uniqId, String requesterId, Operation serverOp) {
        otManager.handleBroadcast(newVersion, uniqId, requesterId, serverOp);
    }

    public void handleCatchUp(JSONArray operations) {
        otManager.handleCatchUp(operations);
    }

    public boolean isApplyingServerChange() {
        return isApplyingServerChange;
    }

    public void setApplyingServerChange(boolean isApplyingServerChange) {
        this.isApplyingServerChange = isApplyingServerChange;
    }

    // --- End of OTManager Delegation ---

    public void requestLineLock(int line) {
        projectController.lineLockRequest(this.filePath, line);
    }

    public void cursorMoveRequest(int cursorPosition) {
        projectController.cursorMoveRequest(this.filePath, cursorPosition);
    }

    public void requestFileEditOperation(String type, int position, String text, int length, int cursorPosition, long version, String uniqId) {
        projectController.fileEditOperationRequest(this.filePath, type, position, text, length, cursorPosition, version, uniqId);
    }

    public void controlledReplaceText(int start, int end, String text, ChangeInitiator initiator) {
        if (inputManager != null) {
            inputManager.controlledReplaceText(start, end, text, initiator);
        }
    }

    public void resetInitiatorToUser() {
        if (inputManager != null) {
            inputManager.resetInitiatorToUser();
        }
    }

    public boolean isLineLockedByOther(int line) {
        Optional<String> currentUserIdOpt = projectController.getCurrentUserId();
        if (currentUserIdOpt.isEmpty()) {
            return false; // Cannot determine current user, so don't block anything
        }
        String currentUserId = currentUserIdOpt.get();

        return stateManager.getLineLockInfo("file-" + this.filePath, line + 1)
                .map(lockInfo -> !lockInfo.userId.equals(currentUserId))
                .orElse(false);
    }

    public boolean isLineLockedByCurrentUser(int line) {
        Optional<String> currentUserIdOpt = projectController.getCurrentUserId();
        if (currentUserIdOpt.isEmpty()) {
            return false;
        }
        // Note: line numbers in stateManager are 1-based.
        return stateManager.isLineLockedByCurrentUser("file-" + this.filePath, line + 1, currentUserIdOpt.get());
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public void prepareForLargeUpdate(int expectedSize) {
        if (expectedSize < LARGE_UPDATE_THRESHOLD) {
            return;
        }
        this.isLargeUpdate = true;
        analysisDebouncer.stop();
    }

    public void requestImmediateAnalysis() {
        analysisDebouncer.stop();
        if (currentAntlrFuture != null && !currentAntlrFuture.isDone()) {
            currentAntlrFuture.cancel(true);
        }
        runTm4eHighlighting();
        runAntlrAnalysis();
    }

    private void runTm4eHighlighting() {
        long requestId = ++tm4eRequestCounter;
        String text = codeArea.getText();
        CompletableFuture.supplyAsync(() -> highlighter.computeHighlighting(text), analysisExecutor)
            .thenAcceptAsync(tokens -> {
                if (requestId == tm4eRequestCounter) {
                    this.lastTm4eTokens = tokens;
                    applyHighlighting();
                }
            }, Platform::runLater);
    }

    private void runAntlrAnalysis() {
        if (analyzer == null) {
            return;
        }
        onAnalysisStart.run();

        if (currentAntlrFuture != null && !currentAntlrFuture.isDone()) {
            currentAntlrFuture.cancel(true);
        }
        String text = codeArea.getText();
        currentAntlrFuture = analyzer.analyze(text, codeArea.getCaretPosition());

        currentAntlrFuture.whenComplete((result, throwable) -> onAnalysisFinish.run());

        if (completionService != null) {
            completionService.updateAnalysisResult(currentAntlrFuture);
        }
        currentAntlrFuture.thenAcceptAsync(analysisResult -> {
            this.lastAnalysisResult = analysisResult;
            computeBracketColors(); // 괄호 색상 계산
            this.lastErrorTokens = new ArrayList<>();
            if (analysisResult.errors != null) {
                for (SyntaxError error : analysisResult.errors) {
                    int line = error.line - 1;
                    if (line < 0 || line >= codeArea.getParagraphs().size()) continue;
                    int start = codeArea.getAbsolutePosition(line, error.charPositionInLine);
                    int end = start + error.length;
                    if (start < end) {
                        lastErrorTokens.add(new StyleToken(start, Math.min(end, codeArea.getLength()), Collections.singletonList("syntax-error")));
                    }
                }
            }
            if (this.lastTm4eTokens == null) {
                analysisDebouncer.playFromStart(); 
                return;
            }
            this.onErrorUpdate.accept(analysisResult.errors);
            applyHighlighting(); // 분석 완료 후 전체 스타일링 다시 적용
        }, Platform::runLater);
    }
    
    private void computeBracketColors() {
        if (lastAnalysisResult == null || lastAnalysisResult.bracketMapping == null) {
            this.lastBracketColorTokens = Collections.emptyList();
            return;
        }

        final List<String> colors = Arrays.asList("bracket-pair-2", "bracket-pair-1", "bracket-pair-3");
        List<StyleToken> colorTokens = new ArrayList<>();
        
        // 모든 여는 괄호와 닫는 괄호 토큰을 하나의 리스트에 넣고 위치 순으로 정렬합니다.
        List<Token> allBrackets = new ArrayList<>();
        allBrackets.addAll(lastAnalysisResult.bracketMapping.getPairMap().keySet().stream()
            .map(pos -> lastAnalysisResult.bracketMapping.getReversePairMap().get(lastAnalysisResult.bracketMapping.getPairMap().get(pos).getStartIndex()))
            .collect(Collectors.toList()));
        allBrackets.addAll(lastAnalysisResult.bracketMapping.getPairMap().values());
        allBrackets.sort(Comparator.comparingInt(Token::getStartIndex));

        Stack<String> parenStack = new Stack<>();
        Stack<String> braceStack = new Stack<>();
        Stack<String> squareStack = new Stack<>();

        for (Token token : allBrackets) {
            String text = token.getText();
            int level = 0;
            String styleClass = "";

            switch (text) {
                case "(":
                    level = parenStack.size();
                    styleClass = colors.get(level % colors.size());
                    parenStack.push(text);
                    break;
                case "{":
                    level = braceStack.size();
                    styleClass = colors.get(level % colors.size());
                    braceStack.push(text);
                    break;
                case "[":
                    level = squareStack.size();
                    styleClass = colors.get(level % colors.size());
                    squareStack.push(text);
                    break;
                case ")":
                    if (!parenStack.isEmpty()) parenStack.pop();
                    level = parenStack.size();
                    styleClass = colors.get(level % colors.size());
                    break;
                case "}":
                    if (!braceStack.isEmpty()) braceStack.pop();
                    level = braceStack.size();
                    styleClass = colors.get(level % colors.size());
                    break;
                case "]":
                    if (!squareStack.isEmpty()) squareStack.pop();
                    level = squareStack.size();
                    styleClass = colors.get(level % colors.size());
                    break;
            }
            if (!styleClass.isEmpty()) {
                colorTokens.add(new StyleToken(token.getStartIndex(), token.getStopIndex() + 1, Collections.singletonList(styleClass)));
            }
        }

        this.lastBracketColorTokens = colorTokens;
    }

    private void shiftTokens(List<StyleToken> tokens, int position, int diff) {
        if (tokens == null) return;
        for (StyleToken token : tokens) {
            if (token.start >= position) {
                token.start += diff;
                token.end += diff;
            } else if (token.end > position) {
                token.end += diff;
            }
        }
    }

    private void updateBracketHighlightingData() {
        if (lastAnalysisResult == null) {
            this.lastBracketTokens = null;
            return;
        }
        Optional<BracketPair> bracketPairOpt = lastAnalysisResult.bracketMapping.findEnclosingPair(codeArea.getCaretPosition());
        if (bracketPairOpt.isPresent()) {
            BracketPair pair = bracketPairOpt.get();
            this.lastBracketTokens = new ArrayList<>();
            this.lastBracketTokens.add(new StyleToken(pair.start.getStartIndex(), pair.start.getStopIndex() + 1, Collections.singletonList("bracket-highlight")));
            this.lastBracketTokens.add(new StyleToken(pair.end.getStartIndex(), pair.end.getStopIndex() + 1, Collections.singletonList("bracket-highlight")));
        } else {
            this.lastBracketTokens = null;
        }
    }

    private void renderBracketHighlightOnly() {
        int docLength = codeArea.getLength();
        if (previouslyRenderedBrackets != null) {
            for (StyleToken oldToken : previouslyRenderedBrackets) {
                // Defensive check to prevent IndexOutOfBoundsException
                if (oldToken.start < docLength && oldToken.end <= docLength) {
                    List<String> style = codeArea.getStyleOfChar(oldToken.start).stream().filter(s -> !s.equals("bracket-highlight")).collect(Collectors.toList());
                    codeArea.setStyle(oldToken.start, oldToken.end, style);
                }
            }
        }

        if (lastBracketTokens != null) {
            for (StyleToken newToken : lastBracketTokens) {
                // Defensive check to prevent IndexOutOfBoundsException
                if (newToken.start < docLength && newToken.end <= docLength) {
                    List<String> style = Stream.concat(codeArea.getStyleOfChar(newToken.start).stream(), Stream.of("bracket-highlight")).distinct().collect(Collectors.toList());
                    codeArea.setStyle(newToken.start, newToken.end, style);
                }
            }
        }
        
        this.previouslyRenderedBrackets = this.lastBracketTokens;
    }

    private StyleSpans<Collection<String>> tokensToSpans(List<StyleToken> tokens) {
        int docLength = codeArea.getLength();
        if (tokens == null || tokens.isEmpty() || docLength == 0) {
            return StyleSpans.singleton(Collections.emptyList(), docLength);
        }
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        int lastEnd = 0;
        
        // Sort tokens just in case they are out of order
        tokens.sort(Comparator.comparingInt(t -> t.start));

        for (StyleToken token : tokens) {
            // Clamp values to be within the document bounds
            int start = Math.min(token.start, docLength);
            int end = Math.min(token.end, docLength);

            // Ensure tokens are valid and non-overlapping
            if (start < end && start >= lastEnd) {
                 if (start > lastEnd) {
                    spansBuilder.add(Collections.emptyList(), start - lastEnd);
                }
                spansBuilder.add(token.styleClasses, end - start);
                lastEnd = end;
            }
        }
        int remaining = docLength - lastEnd;
        if (remaining > 0) {
            spansBuilder.add(Collections.emptyList(), remaining);
        }
        return spansBuilder.create();
    }

    public void updateSearchHighlights(List<StyleToken> searchTokens) {
        this.lastSearchHighlightTokens = searchTokens;
        applyHighlighting();
    }

    private void applyHighlighting() {
        if (codeArea.getText().isEmpty()) {
            codeArea.setStyleSpans(0, StyleSpans.singleton(Collections.emptyList(), 0));
            return;
        }
        if (lastTm4eTokens == null) return;

        StyleSpans<Collection<String>> tm4eSpans = tokensToSpans(lastTm4eTokens);
        StyleSpans<Collection<String>> finalSpans = tm4eSpans;

        // Overlay search highlights first, so they are below other styles if needed
        if (lastSearchHighlightTokens != null) {
            StyleSpans<Collection<String>> searchSpans = tokensToSpans(lastSearchHighlightTokens);
            finalSpans = finalSpans.overlay(searchSpans, (base, search) -> {
                if (!search.isEmpty()) {
                    Set<String> combined = new HashSet<>(base);
                    combined.addAll(search);
                    return combined;
                }
                return base;
            });
        }

        if (lastAnalysisResult != null) {
            StyleSpans<Collection<String>> symbolSpans = tokensToSpans(lastAnalysisResult.symbolTokens);
            StyleSpans<Collection<String>> errorSpans = tokensToSpans(lastErrorTokens);
            StyleSpans<Collection<String>> bracketSpans = tokensToSpans(lastBracketTokens);
            StyleSpans<Collection<String>> bracketColorSpans = tokensToSpans(lastBracketColorTokens);
            
            finalSpans = finalSpans
                .overlay(bracketColorSpans, (base, color) -> {
                    if (!color.isEmpty()) {
                        Set<String> combined = new HashSet<>(base);
                        combined.addAll(color);
                        return combined;
                    }
                    return base;
                }) // 괄호 색상을 먼저 적용
                .overlay(symbolSpans, (base, symbol) -> !symbol.isEmpty() ? symbol : base)
                .overlay(errorSpans, (base, error) -> {
                    if (!error.isEmpty()) {
                        Set<String> combined = new HashSet<>(base);
                        combined.addAll(error);
                        return combined;
                    }
                    return base;
                })
                .overlay(bracketSpans, (base, bracket) -> {
                    if (!bracket.isEmpty()) {
                        Set<String> combined = new HashSet<>(base);
                        combined.addAll(bracket);
                        return combined;
                    }
                    return base;
                });
        }
        
        codeArea.setStyleSpans(0, finalSpans);
        this.previouslyRenderedBrackets = this.lastBracketTokens;
    }

    public void shutdown() {
        if (highlighter != null) highlighter.shutdown();
        if (analyzer != null) analyzer.shutdown();
        analysisExecutor.shutdown();
    }
}
