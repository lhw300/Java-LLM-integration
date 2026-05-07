package com.lcallai.intent;
import com.lcallai.ChatHistory;
// SimpleIntentClassifier.java - 不调用 LLM，直接返回 QUERY
public class SimpleIntentClassifier extends IntentClassifier {

    public SimpleIntentClassifier() {
        super(null, null); // 不需要 llmClient
    }

    @Override
    public IntentResult classify(String userText, ChatHistory history) {
        // Skip LLM entirely, always return QUERY with original text
        return IntentResult.builder(IntentResult.Intent.QUERY)
                .refinedQuery(userText)
                .build();
    }
}