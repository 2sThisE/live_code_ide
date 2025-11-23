package com.ethis2s.service;

import com.ethis2s.util.HybridManager;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.PlainTextChange;
import org.fxmisc.undo.UndoManager;
import org.fxmisc.undo.UndoManagerFactory;
import org.reactfx.EventStream;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public class CollaborativeUndoHelper {

    public static void install(CodeArea codeArea, EditorInputManager inputManager, HybridManager hybridManager) {
        
        EventStream<List<PlainTextChange>> changes = codeArea.multiPlainChanges();

        EventStream<List<PlainTextChange>> userChanges = changes.filter(c -> 
            inputManager.getLastInitiator() == ChangeInitiator.USER
        );

        // (1) Inverter
        Function<PlainTextChange, PlainTextChange> inverter = PlainTextChange::invert;

        // (2) Applier
        Consumer<List<PlainTextChange>> applier = c -> {
            for (PlainTextChange change : c) {
                hybridManager.controlledReplaceText(
                    change.getPosition(),
                    change.getPosition() + change.getRemoved().length(),
                    change.getInserted(),
                    ChangeInitiator.USER 
                );
            }
        };

        // (3) ★★★ [핵심] Merger: 단어 단위 그룹화 로직 ★★★
        BiFunction<PlainTextChange, PlainTextChange, Optional<PlainTextChange>> merger = (change1, change2) -> {
            // 1. 기본적으로 RichTextFX가 연속된 변경인지 확인 (위치가 이어지는지)
            Optional<PlainTextChange> merged = change1.mergeWith(change2);
            
            if (merged.isPresent()) {
                // 2. [추가 로직] "단어 단위" 끊기 전략
                // 새로 들어온 입력(change2)이 공백이나 줄바꿈을 포함하고 있다면,
                // 이전 단어와 합치지 않고 별도의 Undo 그룹으로 만듭니다.
                String newText = change2.getInserted();
                if (containsWhitespace(newText)) {
                    // 공백을 입력하는 순간, 이전 글자들과의 병합을 끊음 -> "Hello" 와 " "가 분리됨
                    return Optional.empty(); 
                }
                
                // 3. [추가 로직] 타입이 바뀌면 끊기 (입력하다가 삭제하는 경우 등)
                // (mergeWith가 어느 정도 처리해주지만 확실하게 하기 위해)
                if (!isSameType(change1, change2)) {
                    return Optional.empty();
                }
            }
            return merged;
        };

        // (4) IsIdentity: 변경사항이 없는 경우(무의미한 변경) 무시
        Predicate<PlainTextChange> isIdentity = change -> 
            change.getInserted().equals(change.getRemoved());

        // 3. 커스텀 UndoManager 생성 (가장 강력한 오버로딩 사용)
        UndoManager<List<PlainTextChange>> customUndoManager = UndoManagerFactory.<PlainTextChange>fixedSizeHistoryMultiChangeUM(
            userChanges,
            inverter,
            applier,
            merger,
            isIdentity,     // 의미 없는 변경 무시
            Duration.ofMillis(500), // ★ [시간 기준] 0.5초 동안 쉬지 않고 치면 묶음
            100             // 히스토리 용량
        );

        // 4. 적용
        codeArea.setUndoManager(customUndoManager);
    }

    // [헬퍼] 공백 문자 포함 여부 확인
    private static boolean containsWhitespace(String text) {
        for (char c : text.toCharArray()) {
            if (Character.isWhitespace(c)) {
                return true;
            }
        }
        return false;
    }

    // [헬퍼] 같은 유형의 편집인지 확인 (삽입끼리, 삭제끼리)
    private static boolean isSameType(PlainTextChange c1, PlainTextChange c2) {
        boolean c1IsInsert = c1.getRemoved().isEmpty();
        boolean c2IsInsert = c2.getRemoved().isEmpty();
        return c1IsInsert == c2IsInsert;
    }
}