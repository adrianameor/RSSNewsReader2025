package my.mmu.rssnewsreader.service.util;

import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Tag;
import org.jsoup.select.Elements;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.functions.BiConsumer;
import io.reactivex.rxjava3.functions.Consumer;
import io.reactivex.rxjava3.schedulers.Schedulers;
import my.mmu.rssnewsreader.data.repository.TranslationRepository;
import my.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;

public class TextUtil {
    public static final String TAG = TextUtil.class.getSimpleName();
    private final CompositeDisposable compositeDisposable;
    private final SharedPreferencesRepository sharedPreferencesRepository;
    private final TranslationRepository translationRepository;

    @Inject
    public TextUtil(SharedPreferencesRepository sharedPreferencesRepository, TranslationRepository translationRepository) {
        this.sharedPreferencesRepository = sharedPreferencesRepository;
        this.translationRepository = translationRepository;
        compositeDisposable = new CompositeDisposable();
    }

    public String extractHtmlContent(String html, String delimiter) {
        if (html == null) {
            return "";
        }

        Document doc = Jsoup.parse(html);
        StringBuilder content = new StringBuilder();

        // Using CSS selector to directly access the required elements
        String cssQuery = "h2, h3, h4, h5, h6, p, td, pre, th, li, figcaption, blockquote, section";
        Elements elements = doc.select(cssQuery);

        // Iterate over the selected elements and append them to the StringBuilder
        for (Element element : elements) {
            content.append(element.text());
            content.append(delimiter);  // Append the delimiter after each element's text
        }

        return content.toString().trim();  // Return the trimmed result to remove the last delimiter
    }
    // This is now the one and only method for translating HTML.
    // It sends the entire HTML document to the translation engine at once.
    public Single<String> translateHtml(String sourceLanguage, String targetLanguage, String html, Consumer<Integer> progressCallback) {
        Log.d(TAG, "translateHtml: from " + sourceLanguage + " to " + targetLanguage);

        // We use our now-fixed translateText method to handle the full HTML document.
        return translateText(sourceLanguage, targetLanguage, html)
                .doOnSubscribe(disposable -> {
                    // Immediately report some progress to show the user something is happening.
                    try {
                        progressCallback.accept(10);
                    } catch (Throwable e) {
                        Log.e(TAG, "Progress callback failed on start", e);
                    }
                })
                .doOnSuccess(translatedHtml -> {
                    // Report 100% progress immediately upon success.
                    try {
                        progressCallback.accept(100);
                    } catch (Throwable e) {
                        Log.e(TAG, "Progress callback failed on completion", e);
                    }
                })
                .doOnError(error -> Log.e(TAG, "translateHtml failed", error));
    }

    // Overloaded version for convenience, provides a no-op progress callback.
    public Single<String> translateHtml(String sourceLanguage, String targetLanguage, String html) {
        return translateHtml(sourceLanguage, targetLanguage, html, progress -> {});
    }

    // Keep the paragraph method signature for compatibility, but have it delegate to the new robust method.
    public Single<String> translateHtmlByParagraph(String sourceLanguage, String targetLanguage, String html, String title, long articleId, Consumer<Integer> progressCallback) {
        Log.d(TAG, "translateHtmlByParagraph: Delegating to new unified translateHtml method.");
        // We can add the title back into the HTML before translating if necessary,
        // but for now, we will keep it simple and just translate the main body HTML.
        return translateHtml(sourceLanguage, targetLanguage, html, progressCallback);
    }

    public Single<String> translateText(String sourceLanguage, String targetLanguage, String text) {
        return translationRepository.translateText(text, sourceLanguage, targetLanguage);
    }

    public Single<String> identifyLanguageRx(String sentence) {
        float confidenceThreshold = (float) sharedPreferencesRepository.getConfidenceThreshold() / 100;

        LanguageIdentificationOptions options = new LanguageIdentificationOptions.Builder()
                .setConfidenceThreshold(confidenceThreshold)
                .build();

        LanguageIdentifier languageIdentifier = LanguageIdentification.getClient(options);

        return Single.fromCallable(() -> languageIdentifier.identifyLanguage(sentence))
                .subscribeOn(Schedulers.io())
                .map(languageCodeTask -> {
                    try {
                        String languageCode = Tasks.await(languageCodeTask);
                        if ("und".equals(languageCode)) {
                            Log.i(TAG, "Unable to identify language.");
                            return "und";
                        } else {
                            Log.i(TAG, "Identified language: " + languageCode);
                            return languageCode;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error identifying language", e);
                        return "und";
                    }
                })
                .onErrorReturnItem("und");
    }

    public void onDestroy() {
        compositeDisposable.dispose();
    }
}