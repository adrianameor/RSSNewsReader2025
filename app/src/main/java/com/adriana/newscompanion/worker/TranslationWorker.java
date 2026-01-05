package com.adriana.newscompanion.worker;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.hilt.work.HiltWorker;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;
import com.adriana.newscompanion.data.entry.EntryRepository;
import com.adriana.newscompanion.data.feed.FeedRepository;
import com.adriana.newscompanion.data.repository.TranslationRepository;
import com.adriana.newscompanion.data.sharedpreferences.SharedPreferencesRepository;
import com.adriana.newscompanion.model.EntryInfo;
import com.adriana.newscompanion.service.util.TextUtil;

@HiltWorker
public class TranslationWorker extends Worker {

    private static final String TAG = "TranslationWorker";
    private final EntryRepository entryRepository;
    private final FeedRepository feedRepository;
    private final TranslationRepository translationRepository;
    private final SharedPreferencesRepository sharedPreferencesRepository;
    private final TextUtil textUtil;

    @AssistedInject
    public TranslationWorker(
            @Assisted @NonNull Context context,
            @Assisted @NonNull WorkerParameters workerParams,
            EntryRepository entryRepository,
            FeedRepository feedRepository,
            TranslationRepository translationRepository,
            SharedPreferencesRepository sharedPreferencesRepository,
            TextUtil textUtil) {
        super(context, workerParams);
        this.entryRepository = entryRepository;
        this.feedRepository = feedRepository;
        this.translationRepository = translationRepository;
        this.sharedPreferencesRepository = sharedPreferencesRepository;
        this.textUtil = textUtil;
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "WORKER: Starting Intelligent Translation (Phase 3).");

        // Ensure we check settings before grabbing the list
        int cleaningDisabled = sharedPreferencesRepository.isAiCleaningEnabled() ? 0 : 1;
        List<EntryInfo> entriesToProcess = entryRepository.getUntranslatedEntriesInfo();
        String defaultTargetLang = sharedPreferencesRepository.getDefaultTranslationLanguage();
        Map<Long, Boolean> feedNeedsTranslation = new HashMap<>();

        Map<Long, String> feedSourceLang = new HashMap<>();

        for (EntryInfo entry : entriesToProcess) {
            try {
                String originalTitle = entry.getEntryTitle();
                if (originalTitle == null || originalTitle.isEmpty()) continue;

                String targetLang = entry.getTargetTranslationLanguage() != null ? entry.getTargetTranslationLanguage() : defaultTargetLang;

                if (!feedNeedsTranslation.containsKey(entry.getFeedId())) {
                    // 1. INTELLIGENT VERIFICATION: Use Title + Description for stronger detection
                    StringBuilder detectionText = new StringBuilder(originalTitle);
                    if (entry.getEntryDescription() != null && !entry.getEntryDescription().isEmpty()) {
                        String cleanDesc = org.jsoup.Jsoup.parse(entry.getEntryDescription()).text();
                        detectionText.append(" ").append(cleanDesc);
                    }

                    String verifiedLang = textUtil.identifyLanguageRx(detectionText.toString()).blockingGet();
                    String dbLang = entry.getFeedLanguage();

                    // SELF-CORRECTION: If DB says 'en' but we see 'ms', fix the DB
                    if (verifiedLang != null && !verifiedLang.equals("und") && !verifiedLang.equalsIgnoreCase(dbLang)) {
                        Log.w(TAG, "Self-Correction: Feed " + entry.getFeedId() + " was labeled " + dbLang + " but is actually " + verifiedLang);
                        feedRepository.updateTitleDescLanguage(entry.getFeedTitle(), "", verifiedLang, entry.getEntryLink());
                    }

                    String finalSourceLang = (verifiedLang != null && !verifiedLang.equals("und")) ? verifiedLang : dbLang;
                    feedSourceLang.put(entry.getFeedId(), finalSourceLang);

                    boolean needsTrans = !isSameLanguage(finalSourceLang, targetLang);
                    feedNeedsTranslation.put(entry.getFeedId(), needsTrans);
                    Log.d(TAG, "Feed " + entry.getFeedId() + " needs translation: " + needsTrans + " (source: " + finalSourceLang + ", target: " + targetLang + ")");
                }

                boolean needsTranslation = feedNeedsTranslation.get(entry.getFeedId());
                String finalSourceLang = feedSourceLang.get(entry.getFeedId());

                if (!needsTranslation) {
                    Log.d(TAG, "ID " + entry.getEntryId() + " from feed " + entry.getFeedId() + " does not need translation. Syncing.");
                    syncSameLanguageEntry(entry, originalTitle);
                    continue;
                }

                // 3. API PHASE: Perform actual AI translation
                Log.d(TAG, "WORKER: Translating ID " + entry.getEntryId() + " (" + finalSourceLang + " -> " + targetLang + ")");

                // Translate Title
                String translatedTitle = entry.getTranslatedTitle();
                if (translatedTitle == null || translatedTitle.trim().isEmpty()) {
                    translatedTitle = translationRepository.translateText(originalTitle, finalSourceLang, targetLang).blockingGet();
                    entryRepository.updateTranslatedTitle(translatedTitle, entry.getEntryId());
                }

                // Translate Full Article HTML
                String cleanedHtml = entryRepository.getHtmlById(entry.getEntryId());
                if (cleanedHtml != null && !cleanedHtml.isEmpty()) {
                    String translatedHtml = translationRepository.translateText(cleanedHtml, finalSourceLang, targetLang).blockingGet();
                    entryRepository.updateHtml(translatedHtml, entry.getEntryId());

                    String plainTextWithMarkers = textUtil.extractHtmlContent(translatedHtml, "--####--");
                    entryRepository.updateTranslated(translatedTitle + "--####--" + plainTextWithMarkers, entry.getEntryId());
                }

            } catch (Exception e) {
                Log.e(TAG, "WORKER: Error processing ID " + entry.getEntryId(), e);
            }
        }

        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(new Intent("translation-finished"));
        return Result.success();
    }

    private boolean isSameLanguage(String source, String target) {
        if (source == null || target == null) return false;
        String s = source.split("-")[0].toLowerCase();
        String t = target.split("-")[0].toLowerCase();
        return s.equals(t);
    }

    private void syncSameLanguageEntry(EntryInfo entry, String originalTitle) {
        String html = entryRepository.getHtmlById(entry.getEntryId());
        if (html != null) {
            entryRepository.updateTranslatedTitle(originalTitle, entry.getEntryId());
            String plainTextWithMarkers = textUtil.extractHtmlContent(html, "--####--");
            entryRepository.updateTranslatedSummary(plainTextWithMarkers, entry.getEntryId());
            entryRepository.updateTranslated(originalTitle + "--####--" + plainTextWithMarkers, entry.getEntryId());
        }
    }
}
