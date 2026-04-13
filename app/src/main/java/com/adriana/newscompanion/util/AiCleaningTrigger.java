package com.adriana.newscompanion.util;

import android.content.Context;
import android.util.Log;

import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.adriana.newscompanion.worker.AiCleaningWorker;
public class AiCleaningTrigger {
    
    private static final String TAG = "AiCleaningTrigger";

    public static void triggerAiCleaning(Context context) {
        Log.d(TAG, "Manually triggering AI Cleaning Worker...");
        WorkManager workManager = WorkManager.getInstance(context);
        OneTimeWorkRequest aiCleaningWorkRequest = new OneTimeWorkRequest.Builder(AiCleaningWorker.class).build();
        workManager.enqueue(aiCleaningWorkRequest);
        Log.d(TAG, "AI Cleaning Worker enqueued successfully.");
    }
}
