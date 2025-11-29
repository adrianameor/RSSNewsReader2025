package my.mmu.rssnewsreader.data.repository;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.reactivex.rxjava3.core.Single;
import my.mmu.rssnewsreader.data.deepseek.DeepSeekApiService;

@Singleton
public class TranslationRepository {

    private final DeepSeekApiService deepSeekApiService;
    private static final String DEEPSEEK_API_KEY = "sk-0f80e84528124637acca95c13aa30256";

    @Inject
    public TranslationRepository(DeepSeekApiService deepSeekApiService) {
        this.deepSeekApiService = deepSeekApiService;
    }

    /**
     * A simple, robust method to translate a block of text (plain or HTML).
     */
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
}