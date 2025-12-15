package com.adriana.newscompanion.data.repository;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.reactivex.rxjava3.core.Single;
import com.adriana.newscompanion.data.deepseek.DeepSeekApiService;

@Singleton
public class TranslationRepository {

    private final DeepSeekApiService deepSeekApiService;
    private static final String DEEPSEEK_API_KEY = "sk-0f80e84528124637acca95c13aa30256";

    @Inject
    public TranslationRepository(DeepSeekApiService deepSeekApiService) {
        this.deepSeekApiService = deepSeekApiService;
    }

    public Single<String> translateText(String originalText, String sourceLang, String targetLang) {
        if (originalText == null || originalText.trim().isEmpty() || sourceLang.equalsIgnoreCase(targetLang)) {
            return Single.just(originalText);
        }

        boolean isHtml = originalText.contains("<") && originalText.contains(">");

        DeepSeekApiService.DeepSeekRequest request = new DeepSeekApiService.DeepSeekRequest(originalText, sourceLang, targetLang, isHtml);

        return deepSeekApiService.translate("Bearer " + DEEPSEEK_API_KEY, request)
                .map(response -> {
                    if (response != null && response.choices != null && !response.choices.isEmpty()) {
                        String translated = response.choices.get(0).message.content;
                        return translated != null ? translated.trim() : originalText;
                    }
                    return originalText; // Fallback to original text on failure
                })
                .onErrorReturnItem(originalText); // Fallback on error
    }

    public Single<String> summarizeText(String originalText, int wordCount) {
        if (originalText == null || originalText.trim().isEmpty()) {
            return Single.just("");
        }

        DeepSeekApiService.SummarizeRequest request = new DeepSeekApiService.SummarizeRequest(originalText, wordCount);

        return deepSeekApiService.summarize("Bearer " + DEEPSEEK_API_KEY, request)
                .map(response -> {
                    if (response != null && response.choices != null && !response.choices.isEmpty()) {
                        String summary = response.choices.get(0).message.content;
                        return summary != null ? summary.trim() : "";
                    }
                    return ""; // Fallback to empty string on failure
                })
                .onErrorReturnItem("Error: Could not generate summary."); // Fallback on error
    }
}