package com.adriana.newscompanion.worker;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.hilt.work.HiltWorker;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.adriana.newscompanion.data.entry.Entry;
import com.adriana.newscompanion.data.feed.Feed;
import com.adriana.newscompanion.data.feed.FeedRepository;
import com.adriana.newscompanion.data.repository.TranslationRepository;
import com.adriana.newscompanion.data.sharedpreferences.SharedPreferencesRepository;
import com.adriana.newscompanion.service.util.TextUtil;

import java.util.List;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;

@HiltWorker
public class AiSummarizationWorker extends Worker {

    private static final String TAG = "AiSummarizationWorker";

    private final FeedRepository feedRepository;
    private final TranslationRepository translationRepository;
    private final SharedPreferencesRepository sharedPreferencesRepository;
    private final TextUtil textUtil;

    @AssistedInject
    public AiSummarizationWorker(
            @Assisted @NonNull Context context,
            @Assisted @NonNull WorkerParameters workerParams,
            FeedRepository feedRepository,
            TranslationRepository translationRepository,
            SharedPreferencesRepository sharedPreferencesRepository,
            TextUtil textUtil) {
        super(context, workerParams);
        this.feedRepository = feedRepository;
        this.translationRepository = translationRepository;
        this.sharedPreferencesRepository = sharedPreferencesRepository;
        this.textUtil = textUtil;
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Log.d(TAG, "=== AI Summarization Worker Started (Phase 1) ===");

            if (!sharedPreferencesRepository.isSummarizationEnabled()) {
                Log.d(TAG, "Summarization disabled. Exiting.");
                return Result.success();
            }

            long currentReadingId = sharedPreferencesRepository.getCurrentReadingEntryId();
            String sortBy = sharedPreferencesRepository.getSortBy();
            boolean autoTranslate = sharedPreferencesRepository.getAutoTranslate();
            String targetLang = sharedPreferencesRepository.getDefaultTranslationLanguage();

            List<Entry> unsummarized = feedRepository.getEntryRepository().getUnsummarizedEntriesPrioritized(currentReadingId, sortBy);
            Log.d(TAG, "Found " + unsummarized.size() + " articles to summarize.");

            for (Entry entry : unsummarized) {
                try {
                    String html = entry.getOriginalHtml();
                    if (html == null || html.isEmpty()) {
                        html = entry.getHtml();
                    }
                    
                    if (html == null || html.isEmpty()) continue;

                    String plainText = textUtil.extractHtmlContent(html, " ");
                    int length = sharedPreferencesRepository.getAiSummaryLength();

                    Log.d(TAG, "Summarizing ID: " + entry.getId() + (autoTranslate ? " (Direct to " + targetLang + ")" : ""));

                    // 1. Generate the summary (translated or original)
                    String summary = translationRepository.summarizeText(plainText, length, autoTranslate ? targetLang : null).blockingGet();

                    if (summary != null && !summary.isEmpty()) {
                        feedRepository.getEntryRepository().updateSummary(summary, entry.getId());
                        
                        // 2. NEW: If Auto-Translate is ON, also translate the TITLE immediately
                        if (autoTranslate) {
                            Feed feed = feedRepository.getFeedById(entry.getFeedId());
                            String sourceLang = (feed != null) ? feed.getLanguage() : "en";
                            
                            Log.d(TAG, "Translating title for Summary page | ID: " + entry.getId());
                            String translatedTitle = translationRepository.translateText(entry.getTitle(), sourceLang, targetLang).blockingGet();
                            
                            if (translatedTitle != null && !translatedTitle.isEmpty()) {
                                feedRepository.getEntryRepository().updateTranslatedTitle(translatedTitle, entry.getId());
                            }
                        }

                        // Mark as summarized
                        feedRepository.getEntryRepository().markAsAiSummarized(entry.getId(), autoTranslate);
                        Log.d(TAG, "✓ ID: " + entry.getId() + " summarized (and title translated) successfully.");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to summarize ID: " + entry.getId(), e);
                }
            }

            Log.d(TAG, "=== AI Summarization Worker Completed ===");
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Critical error in Summarization Worker", e);
            return Result.retry();
        }
    }
}
