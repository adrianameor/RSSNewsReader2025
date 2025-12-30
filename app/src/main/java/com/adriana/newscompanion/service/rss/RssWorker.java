package com.adriana.newscompanion.service.rss;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.hilt.work.HiltWorker;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.adriana.newscompanion.data.feed.FeedRepository;
import com.adriana.newscompanion.data.repository.TranslationRepository;
import com.adriana.newscompanion.data.sharedpreferences.SharedPreferencesRepository;
import com.adriana.newscompanion.service.tts.TtsExtractor;
import com.adriana.newscompanion.service.util.TextUtil;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;

@HiltWorker
public class RssWorker extends Worker {

    public static final String TAG = "RssWorker";

    private final Context context;
    private final FeedRepository feedRepository;
    private final TtsExtractor ttsExtractor;
    private final SharedPreferencesRepository sharedPreferencesRepository;
    private final TextUtil textUtil;
    private final TranslationRepository translationRepository;

    @AssistedInject
    public RssWorker(
            @Assisted @NonNull Context context,
            @Assisted @NonNull WorkerParameters workerParams,
            FeedRepository feedRepository,
            TtsExtractor ttsExtractor,
            SharedPreferencesRepository sharedPreferencesRepository,
            TextUtil textUtil,
            TranslationRepository translationRepository) {
        super(context, workerParams);
        this.context = context;
        this.feedRepository = feedRepository;
        this.ttsExtractor = ttsExtractor;
        this.sharedPreferencesRepository = sharedPreferencesRepository;
        this.textUtil = textUtil;
        this.translationRepository = translationRepository;
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Log.d(TAG, "=== Starting Background Sync and Health Check ===");
            
            // 1. Download new XML data from all RSS feeds
            String text = feedRepository.refreshEntries();
            
            // 2. Notify the user of new articles
            RssNotification rssNotification = new RssNotification(context);
            rssNotification.sendNotification(text);

            // 3. STUCK QUEUE RECOVERY:
            // We force-reset the priority for all articles that have NO content.
            // This ensures that articles from previous days that were interrupted (stuck at Red)
            // are moved back into the extraction queue (Yellow).
            Log.d(TAG, "Performing Health Check: Re-queueing stuck entries...");
            feedRepository.getEntryRepository().requeueMissingEntries();
            
            // 4. TRIGGER ENGINE:
            // Start the TtsExtractor. It will now find both the new articles
            // and the "stuck" articles we just reset.
            if (feedRepository.getEntryRepository().hasEmptyContentEntries()) {
                Log.d(TAG, "Empty content entries found. Dispatching extraction engine.");
                ttsExtractor.extractAllEntries();
            } else {
                Log.d(TAG, "No processing needed. All articles complete.");
            }

            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Sync failed: " + e.getMessage());
            return Result.retry();
        }
    }
}
