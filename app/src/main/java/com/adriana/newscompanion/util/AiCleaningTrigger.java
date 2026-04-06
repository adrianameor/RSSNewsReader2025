package com.adriana.newscompanion.util;

import android.content.Context;
import android.util.Log;

import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.adriana.newscompanion.worker.AiCleaningWorker;
import com.adriana.newscompanion.worker.AiSummarizationWorker;
import com.adriana.newscompanion.worker.TranslationWorker;

/**
 * Utility class to manually trigger AI cleaning from anywhere in the app
 */
public class AiCleaningTrigger {
    
    private static final String TAG = "AiCleaningTrigger";
    
    /**
     * Manually trigger AI cleaning worker
     * This can be called from:
     * - Settings screen when user toggles AI cleaning
     * - Settings screen when user changes sort order
     * - Menu option to manually clean articles
     * - Any other place where you want to trigger cleaning
     */
    public static void triggerAiCleaning(Context context) {
        Log.d(TAG, "Manually triggering AI Cleaning Worker...");
        WorkManager workManager = WorkManager.getInstance(context);
        OneTimeWorkRequest aiCleaningWorkRequest = new OneTimeWorkRequest.Builder(AiCleaningWorker.class).build();
        workManager.enqueue(aiCleaningWorkRequest);
        Log.d(TAG, "AI Cleaning Worker enqueued successfully.");
    }

    public static void triggerFullReprocess(Context context) {
        WorkManager wm = WorkManager.getInstance(context);

        OneTimeWorkRequest translation =
                new OneTimeWorkRequest.Builder(TranslationWorker.class).build();

        OneTimeWorkRequest summary =
                new OneTimeWorkRequest.Builder(AiSummarizationWorker.class).build();

        wm.beginWith(translation)
                .then(summary)
                .enqueue();
    }
}
