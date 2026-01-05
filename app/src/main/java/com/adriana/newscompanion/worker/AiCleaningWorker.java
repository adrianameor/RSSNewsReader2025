package com.adriana.newscompanion.worker;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.hilt.work.HiltWorker;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.adriana.newscompanion.data.entry.Entry;
import com.adriana.newscompanion.data.feed.FeedRepository;
import com.adriana.newscompanion.data.repository.TranslationRepository;
import com.adriana.newscompanion.data.sharedpreferences.SharedPreferencesRepository;
import java.util.List;
import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;

@HiltWorker
public class AiCleaningWorker extends Worker {
    private static final String TAG = "AiCleaningWorker";
    private final FeedRepository feedRepository;
    private final TranslationRepository translationRepository;
    private final SharedPreferencesRepository sharedPreferencesRepository;

    @AssistedInject
    public AiCleaningWorker(@Assisted @NonNull Context context, @Assisted @NonNull WorkerParameters workerParams,
                            FeedRepository feedRepository, TranslationRepository translationRepository,
                            SharedPreferencesRepository sharedPreferencesRepository) {
        super(context, workerParams);
        this.feedRepository = feedRepository;
        this.translationRepository = translationRepository;
        this.sharedPreferencesRepository = sharedPreferencesRepository;
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Log.d(TAG, "=== AI Cleaning Worker Started (Phase 2) ===");
            long currentId = sharedPreferencesRepository.getCurrentReadingEntryId();
            String sortBy = sharedPreferencesRepository.getSortBy();
            
            // SMART GATE: Check if summarization is off
            int summarizeDisabled = sharedPreferencesRepository.isSummarizationEnabled() ? 0 : 1;

            // Fetch only articles that are "Ready" (Summarized OR Summarization is OFF)
            List<Entry> uncleaned = feedRepository.getEntryRepository().getUncleanedEntriesPrioritized(currentId, sortBy, summarizeDisabled);

            for (Entry entry : uncleaned) {
                String sourceHtml = entry.getOriginalHtml();
                if (sourceHtml != null && !sourceHtml.isEmpty()) {
                    Log.d(TAG, "AI Cleaning ID: " + entry.getId());
                    String cleanedHtml = translationRepository.cleanArticleHtml(sourceHtml).blockingGet();

                    if (cleanedHtml != null && !cleanedHtml.trim().isEmpty() && !cleanedHtml.equals(sourceHtml)) {
                        feedRepository.getEntryRepository().updateHtml(cleanedHtml, entry.getId());
                        feedRepository.getEntryRepository().updateOriginalHtml(cleanedHtml, entry.getId());
                        feedRepository.getEntryRepository().updateTranslated(null, entry.getId());
                        Log.d(TAG, "✓ ID " + entry.getId() + " cleaned.");
                    }
                    feedRepository.getEntryRepository().markAsAiCleaned(entry.getId());
                }
            }
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Error in AI Cleaning Worker", e);
            return Result.retry();
        }
    }
}
