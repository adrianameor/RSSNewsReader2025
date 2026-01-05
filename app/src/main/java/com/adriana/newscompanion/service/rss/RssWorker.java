package com.adriana.newscompanion.service.rss;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.hilt.work.HiltWorker;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.adriana.newscompanion.data.feed.FeedRepository;
import com.adriana.newscompanion.service.tts.TtsExtractor;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@HiltWorker
public class RssWorker extends Worker {

    public static final String TAG = "RssWorker";

    private final FeedRepository feedRepository;
    private final TtsExtractor ttsExtractor;

    @AssistedInject
    public RssWorker(
            @Assisted @NonNull Context context,
            @Assisted @NonNull WorkerParameters workerParams,
            FeedRepository feedRepository,
            TtsExtractor ttsExtractor) {
        super(context, workerParams);
        this.feedRepository = feedRepository;
        this.ttsExtractor = ttsExtractor;
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Log.d(TAG, "=== Starting Global Sync & Recovery ===");
            
            // 1. Download XML from all feeds
            feedRepository.refreshEntries();
            
            // 2. Health Check: Reset priority for all articles missing content
            // This method in the repository now resets ANY entry with null html to priority 1.
            feedRepository.getEntryRepository().requeueMissingEntries();
            
            // 3. Extraction Engine: Turns Red -> Yellow -> Green
            if (feedRepository.getEntryRepository().hasEmptyContentEntries()) {
                Log.d(TAG, "Unprocessed articles found. Starting extraction.");
                
                final CountDownLatch latch = new CountDownLatch(1);
                
                new Thread(() -> {
                    try {
                        // The engine handles its own memory recycling and AI batch triggering
                        ttsExtractor.extractAllEntries();

                        Thread.sleep(5000);

                        // Wait for extraction to complete or the worker to timeout
                        while (feedRepository.getEntryRepository().hasEmptyContentEntries()) {
                            Thread.sleep(10000); // Poll every 10 seconds
                        }
                    } catch (InterruptedException ignored) {
                    } finally {
                        latch.countDown();
                    }
                }).start();

                // Stay alive for up to 9 minutes to finish the bulk of the work
                latch.await(9, TimeUnit.MINUTES);
            }

            Log.d(TAG, "=== Global Sync Completed ===");
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Sync failed: " + e.getMessage());
            return Result.retry();
        }
    }
}
