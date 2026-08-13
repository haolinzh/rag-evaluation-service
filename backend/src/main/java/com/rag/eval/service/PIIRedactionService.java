package com.rag.eval.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

@Service
@ConfigurationProperties(prefix = "pii")
public class PIIRedactionService {

    private boolean enabled;
    private List<PatternConfig> patterns;

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setPatterns(List<PatternConfig> patterns) { this.patterns = patterns; }

    public int redactCount(String text) {
        if (!enabled) return 0;
        int count = 0;
        for (PatternConfig pc : patterns) {
            var m = Pattern.compile(pc.regex).matcher(text);
            while (m.find()) count++;
        }
        return count;
    }

    public String redact(String text) {
        if (!enabled || text == null) return text;
        String result = text;
        for (PatternConfig pc : patterns) {
            result = result.replaceAll(pc.regex, pc.replacement);
        }
        return result;
    }

    public static class PatternConfig {
        public String name;
        public String regex;
        public String replacement;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getRegex() { return regex; }
        public void setRegex(String regex) { this.regex = regex; }
        public String getReplacement() { return replacement; }
        public void setReplacement(String replacement) { this.replacement = replacement; }
    }
}
