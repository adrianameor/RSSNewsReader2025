package my.mmu.rssnewsreader.data.repository;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.reactivex.rxjava3.core.Single;
import my.mmu.rssnewsreader.data.deepseek.DeepSeekApiService;

@Singleton
public class TranslationRepository {

    private final DeepSeekApiService deepSeekApiService;
    // TODO: The API key should not be hardcoded. It should be stored securely, for example in local.properties.
    private final String DEEPSEEK_API_KEY = "sk-0f80e84528124637acca95c13aa30256";

    @Inject
    public TranslationRepository(DeepSeekApiService deepSeekApiService) {
        this.deepSeekApiService = deepSeekApiService;
    }

    public Single<String> translate(String text, String sourceLang, String targetLang) {
        // Automatically skip if source and target languages are the same or similar
        if (sourceLang.equalsIgnoreCase(targetLang)) {
            return Single.just(text);
        }

        DeepSeekApiService.DeepSeekRequest request = new DeepSeekApiService.DeepSeekRequest(text, sourceLang, targetLang);
        return deepSeekApiService.translate("Bearer " + DEEPSEEK_API_KEY, request)
                .map(response -> {
                    if (response != null && response.choices != null && !response.choices.isEmpty()) {
                        String translatedText = response.choices.get(0).message.content;
                        if (translatedText != null) {
                            return translatedText.trim();
                        }
                    }
                    // If we get here, the response was not in the expected format.
                    throw new Exception("Translation failed or returned empty content.");
                });
    }
}
