package my.mmu.rssnewsreader.data.repository;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import my.mmu.rssnewsreader.data.deepseek.DeepSeekApiService;

@Singleton
public class TranslationRepository {

    private final DeepSeekApiService deepSeekApiService;
    private final String DEEPSEEK_API_KEY = "sk-0f80e84528124637acca95c13aa30256";

    @Inject
    public TranslationRepository(DeepSeekApiService deepSeekApiService) {
        this.deepSeekApiService = deepSeekApiService;
    }

    /**
     * This is the main "Project Manager" method.
     * It splits the HTML into paragraphs and translates them in parallel.
     */
    public Single<String> translate(String html, String sourceLang, String targetLang) {
        if (sourceLang.equalsIgnoreCase(targetLang)) {
            return Single.just(html);
        }

        // Use Jsoup to parse the HTML and find all paragraphs
        Document doc = Jsoup.parse(html);
        Elements paragraphs = doc.select("p, h1, h2, h3, h4, h5, h6, li"); // Select paragraphs, headings, and list items

        // Create a list of translation "jobs" for each paragraph
        List<Single<String>> translationJobs = new ArrayList<>();
        for (Element p : paragraphs) {
            String originalText = p.html(); // Use .html() to keep inner tags like <b> or <a>
            if (!originalText.trim().isEmpty()) {
                // Add a job to translate this single piece of HTML
                translationJobs.add(translateText(originalText, sourceLang, targetLang));
            }
        }

        // Use RxJava's zip operator to run all jobs in parallel.
        // It's the "super-fast assistant" that collects all the results.
        return Single.zip(
                translationJobs,
                (Object[] translatedPieces) -> {
                    // Once all jobs are done, reassemble the "book".
                    int i = 0;
                    for (Element p : paragraphs) {
                        if (!p.html().trim().isEmpty()) {
                            p.html((String) translatedPieces[i]); // Replace original paragraph with translated one
                            i++;
                        }
                    }
                    return doc.html(); // Return the complete, reassembled HTML
                }
        );
    }

    /**
     * This is the small, single "translator" method.
     * It takes one piece of text and gets it translated by the API.
     * It now also cleans up the AI's response robustly.
     */
    private Single<String> translateText(String text, String sourceLang, String targetLang) {
        DeepSeekApiService.DeepSeekRequest request = new DeepSeekApiService.DeepSeekRequest(text, sourceLang, targetLang);
        return deepSeekApiService.translate("Bearer " + DEEPSEEK_API_KEY, request)
                .map(response -> {
                    if (response != null && response.choices != null && !response.choices.isEmpty()) {
                        String translatedText = response.choices.get(0).message.content;
                        if (translatedText != null) {
                            // THIS IS THE FIX: A more robust cleaning process.
                            String cleanedText = translatedText;

                            // First, remove the markdown blocks if they exist.
                            if (cleanedText.trim().startsWith("```html")) {
                                cleanedText = cleanedText.trim().substring(7);
                            }
                            if (cleanedText.trim().endsWith("```")) {
                                cleanedText = cleanedText.trim().substring(0, cleanedText.trim().length() - 3);
                            }

                            // Finally, trim any leading/trailing whitespace or newlines.
                            return cleanedText.trim();
                        }
                    }
                    // If translation fails, return the original text for that paragraph
                    return text;
                })
                .onErrorReturnItem(text); // If there's a network error, also return the original text
    }
}
