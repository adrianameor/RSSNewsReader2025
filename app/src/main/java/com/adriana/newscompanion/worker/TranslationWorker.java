package com.adriana.newscompanion.worker;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.hilt.work.HiltWorker;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.List;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;
import com.adriana.newscompanion.data.entry.EntryRepository;
import com.adriana.newscompanion.data.repository.TranslationRepository;
import com.adriana.newscompanion.data.sharedpreferences.SharedPreferencesRepository;
import com.adriana.newscompanion.model.EntryInfo;

@HiltWorker
public class TranslationWorker extends Worker {

    private static final String TAG = "TranslationWorker";
    private final EntryRepository entryRepository;
    private final TranslationRepository translationRepository;
    private final SharedPreferencesRepository sharedPreferencesRepository;

    @AssistedInject
    public TranslationWorker(
            @Assisted @NonNull Context context,
            @Assisted @NonNull WorkerParameters workerParams,
            EntryRepository entryRepository,
            TranslationRepository translationRepository,
            SharedPreferencesRepository sharedPreferencesRepository) {
        super(context, workerParams);
        this.entryRepository = entryRepository;
        this.translationRepository = translationRepository;
        this.sharedPreferencesRepository = sharedPreferencesRepository;
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "WORKER: doWork started (Phase 3: Full Translation).");
        List<EntryInfo> entriesToProcess = entryRepository.getUntranslatedEntriesInfo();
        String defaultTargetLang = sharedPreferencesRepository.getDefaultTranslationLanguage();

        for (EntryInfo entry : entriesToProcess) {
            try {
                String sourceLang = entry.getFeedLanguage();
                String targetLang = entry.getTargetTranslationLanguage() != null ? entry.getTargetTranslationLanguage() : defaultTargetLang;

                if (sourceLang == null || sourceLang.isEmpty() || sourceLang.equals("und") || sourceLang.equalsIgnoreCase(targetLang)) continue;

                // 1. Translate Title
                String translatedTitle = entry.getTranslatedTitle();
                if (translatedTitle == null || translatedTitle.trim().isEmpty()) {
                    translatedTitle = translationRepository.translateText(entry.getEntryTitle(), sourceLang, targetLang).blockingGet();
                    entryRepository.updateTranslatedTitle(translatedTitle, entry.getEntryId());
                }

                // 2. Translate Full Article HTML
                String cleanedHtml = entryRepository.getHtmlById(entry.getEntryId());
                if (cleanedHtml != null && !cleanedHtml.isEmpty()) {
                    Log.d(TAG, "WORKER: Translating cleaned HTML for ID: " + entry.getEntryId());
                    String translatedHtml = translationRepository.translateText(cleanedHtml, sourceLang, targetLang).blockingGet();
                    
                    // Save HTML for the WebView
                    entryRepository.updateHtml(translatedHtml, entry.getEntryId());
                    
                    // FIX: Extract PLAIN TEXT for TTS (prevents reading <HTML> tags)
                    Document doc = Jsoup.parse(translatedHtml);
                    String plainText = doc.text(); 
                    
                    // Save Title + Text for TTS
                    entryRepository.updateTranslated(translatedTitle + "--####--" + plainText, entry.getEntryId());
                }

            } catch (Exception e) {
                Log.e(TAG, "WORKER: Error processing ID " + entry.getEntryId(), e);
            }
        }

        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(new Intent("translation-finished"));
        return Result.success();
    }
}
