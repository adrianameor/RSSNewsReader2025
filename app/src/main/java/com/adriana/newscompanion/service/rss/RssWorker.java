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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
            Log.d(TAG, "=== Sync & Health Check Starting ===");
            
            // 1. Download new XML articles
            String text = feedRepository.refreshEntries();
            RssNotification rssNotification = new RssNotification(context);
            rssNotification.sendNotification(text);

            // 2. RECOVERY LOGIC (Requirement 4.2)
            // Reset any article that is stuck in priority 1 (Yellow) but has no content.
            // This allows the engine to pick up articles that were missed in previous syncs.
            Log.d(TAG, "Resetting stuck extraction queue...");
            feedRepository.getEntryRepository().requeueMissingEntries();
            
            // 3. PROCESS THE QUEUE
            // We use a latch to stay alive as long as possible (up to 9 minutes)
            // to process all 50-60 articles in one go.
            if (feedRepository.getEntryRepository().hasEmptyContentEntries()) {
                Log.d(TAG, "Starting extraction engine for unprocessed articles.");
                
                final CountDownLatch latch = new CountDownLatch(1);
                
                new Thread(() -> {
                    try {
                        // Keep extracting until the database says we are 100% finished
                        while (feedRepository.getEntryRepository().hasEmptyContentEntries()) {
                            // The engine in TtsExtractor now handles its own memory recycling
                            ttsExtractor.extractAllEntries();
                            Thread.sleep(10000); // Check progress every 10 seconds
                        }
                    } catch (InterruptedException e) {
                        Log.w(TAG, "Extraction polling interrupted.");
                    } finally {
                        latch.countDown();
                    }
                }).start();

                // Wait up to 9 minutes. If we don't finish, we return SUCCESS anyway
                // so that progress is saved and we can resume later.
                boolean finished = latch.await(9, TimeUnit.MINUTES);
                if (!finished) {
                    Log.w(TAG, "Time limit reached (9m). Saving progress for next sync.");
                } else {
                    Log.d(TAG, "✓ All articles processed successfully in this sync.");
                }
            }

            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Worker failed: " + e.getMessage());
            return Result.retry();
        }
    }
}
