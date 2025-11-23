package com.ethis2s.util;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [최종본] 명령어 템플릿의 변수를 ConfigManager와 동적 컨텍스트를 사용하여
 * '재귀적으로(recursively)' 해석하는 순수 해석 엔진입니다.
 */
public class VariableResolver {

    private final ConfigManager configManager;
    private final Map<String, String> dynamicContext;

    public VariableResolver(ConfigManager configManager, Map<String, String> dynamicContext) {
        this.configManager = configManager;
        this.dynamicContext = dynamicContext;
    }

    /**
     * 주어진 템플릿 문자열을 완전히 해석될 때까지 재귀적으로 치환합니다.
     * @param template 해석할 템플릿 (예: "{java_command}")
     * @return 모든 변수가 치환된 최종 문자열
     */
    public String resolve(String template) {
        if (template == null) return "";
        
        String current = template;
        Pattern pattern = Pattern.compile("\\{([^}]+)\\}");

        // 무한 루프 방지를 위해 최대 10번까지만 반복합니다.
        for (int i = 0; i < 10; i++) {
            Matcher matcher = pattern.matcher(current);
            if (!matcher.find()) {
                break; // 더 이상 치환할 {변수}가 없으면 해석 완료.
            }
            
            // matcher를 리셋하여 처음부터 다시 검색하도록 합니다.
            matcher.reset();
            
            StringBuffer sb = new StringBuffer();
            while (matcher.find()) {
                String keyWithBraces = matcher.group(0); // {key} 형태
                String key = matcher.group(1); // key 부분
                String value = null;

                // 1. 동적 컨텍스트(실행 시점 정보)에서 먼저 변수 값을 찾습니다. (가장 높은 우선순위)
                if (dynamicContext != null && dynamicContext.containsKey(key)) {
                    value = dynamicContext.get(key);
                } 
                // 2. 동적 컨텍스트에 없으면, ConfigManager(정적 설정)에서 찾습니다.
                else {
                    // "run-variables" 섹션에서 "{key}" 형태로 찾습니다.
                    Map<String, Object> factoryMap = configManager.get("runConfig", "factory", Map.class, new HashMap<>());
                    if (factoryMap != null && factoryMap.containsKey(keyWithBraces)) {
                        // Map의 값이 Object일 수 있으므로 String으로 변환
                        value = String.valueOf(factoryMap.get(keyWithBraces));
                    }
                }
                
                if (value != null) {
                    // 값을 찾았으면 치환합니다.
                    // quoteReplacement는 값에 '$'나 '\' 같은 특수문자가 있을 경우를 대비한 안전장치입니다.
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
                } else {
                    // ConfigManager에도 값이 없으면, 변수를 해석하지 않고 그대로 둡니다.
                    matcher.appendReplacement(sb, keyWithBraces);
                }
            }
            matcher.appendTail(sb);
            
            // 이전 단계와 결과가 같다면 더 이상 치환할 것이 없으므로 종료합니다.
            if (current.equals(sb.toString())) {
                break;
            }
            current = sb.toString();
        }
        return current;
    }
}