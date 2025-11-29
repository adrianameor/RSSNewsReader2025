package my.mmu.rssnewsreader.service.util;

import android.util.Log;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import my.mmu.rssnewsreader.data.entry.Entry;
import my.mmu.rssnewsreader.data.entry.EntryRepository;
import my.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import my.mmu.rssnewsreader.model.EntryInfo;

public class AutoTranslator {
    private static final String TAG = "AutoTranslator";

    private final EntryRepository entryRepository;
    private final TextUtil textUtil;
    private final SharedPreferencesRepository prefs;
    private final String delimiter = "--####--";
    private final CompositeDisposable disposables = new CompositeDisposable();

    public AutoTranslator(EntryRepository entryRepository, TextUtil textUtil, SharedPreferencesRepository prefs) {
        this.entryRepository = entryRepository;
        this.textUtil = textUtil;
        this.prefs = prefs;
    }

    public void runAutoTranslation(@Nullable Runnable onComplete) {
        if (!prefs.getAutoTranslate()) {
            if (onComplete != null) onComplete.run();
            disposables.dispose();
            return;
        }

        List<EntryInfo> untranslatedEntries = entryRepository.getUntranslatedEntriesInfo();
        AtomicInteger remaining = new AtomicInteger(untranslatedEntries.size());

        if (untranslatedEntries.isEmpty()) {
            Log.d("AUTOTRANSLATOR_DEBUG", "No new entries to translate.");
            if (onComplete != null) onComplete.run();
            disposables.dispose();
            return;
        }

        Log.d("AUTOTRANSLATOR_DEBUG", "Found " + untranslatedEntries.size() + " entries to process.");

        for (EntryInfo entry : untranslatedEntries) {
            long id = entry.getEntryId();
            String title = entry.getEntryTitle();
            // This was a bug from before, ensure we get the ORIGINAL html to translate
            String html = entryRepository.getOriginalHtmlById(id);
            String content = entry.getContent();

            Log.d("AUTOTRANSLATOR_DEBUG", "Looping for entry ID: " + id + ". Starting processing.");

            if (html != null && title != null && !title.trim().isEmpty()) {
                Log.d("AUTOTRANSLATOR_DEBUG", "ID: " + id + " - Calling identifyLanguageRx...");

                disposables.add(textUtil.identifyLanguageRx(content)
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(sourceLang -> {
                            Log.d("AUTOTRANSLATOR_DEBUG", "ID: " + id + " - Language identified as: '" + sourceLang + "'. Checking if translation is needed.");
                            String targetLang = prefs.getDefaultTranslationLanguage();

                            if (!sourceLang.equalsIgnoreCase(targetLang)) {
                                Log.d("AUTOTRANSLATOR_DEBUG", "ID: " + id + " - Translation needed. Starting translation jobs.");
                                Single<String> titleTranslationJob = textUtil.translateText(sourceLang, targetLang, title);
                                Single<String> bodyTranslationJob = textUtil.translateHtml(sourceLang, targetLang, html, progress -> {});

                                disposables.add(Single.zip(
                                                titleTranslationJob,
                                                bodyTranslationJob,
                                                (translatedTitle, translatedBodyHtml) -> {
                                                    Log.d("AUTOTRANSLATOR_DEBUG", "--- Translation successful for ID: " + id + " ---");
                                                    Log.d("AUTOTRANSLATOR_DEBUG", "Received Translated Title: '" + translatedTitle + "'");

                                                    entryRepository.updateTranslatedTitle(translatedTitle, id);
                                                    entryRepository.updateHtml(translatedBodyHtml, id);

                                                    org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(translatedBodyHtml);
                                                    String translatedBodyText = textUtil.extractHtmlContent(doc.body().html(), delimiter);
                                                    entryRepository.updateTranslatedSummary(translatedBodyText, id);
                                                    entryRepository.updateTranslated(translatedTitle + "\n\n" + translatedBodyText, id);
                                                    return true;
                                                })
                                        .subscribe(success -> {
                                            Log.d(TAG, "Successfully translated and saved article ID: " + id);
                                            if (remaining.decrementAndGet() == 0 && onComplete != null) {
                                                onComplete.run();
                                                disposables.dispose();
                                            }
                                        }, error -> {
                                            Log.e("AUTOTRANSLATOR_DEBUG", "Translation API failed for ID: " + id, error);
                                            if (remaining.decrementAndGet() == 0 && onComplete != null) {
                                                onComplete.run();
                                                disposables.dispose();
                                            }
                                        })
                                );
                            } else {
                                Log.d("AUTOTRANSLATOR_DEBUG", "ID: " + id + " - Source language is same as target. Skipping.");
                                if (remaining.decrementAndGet() == 0 && onComplete != null) {
                                    onComplete.run();
                                    disposables.dispose();
                                }
                            }
                        }, error -> {
                            Log.e("AUTOTRANSLATOR_DEBUG", "Language detection failed for ID: " + id, error);
                            if (remaining.decrementAndGet() == 0 && onComplete != null) {
                                onComplete.run();
                                disposables.dispose();
                            }
                        }));
            } else {
                Log.w("AUTOTRANSLATOR_DEBUG", "Skipping entry ID: " + id + " due to null html or title.");
                if (remaining.decrementAndGet() == 0 && onComplete != null) {
                    onComplete.run();
                    disposables.dispose();
                }
            }
        }
    }

    public void runAutoTranslation() {
        runAutoTranslation(null);
    }
}
