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
            Log.d(TAG, "Starting RSS refresh...");
            String text = feedRepository.refreshEntries();
            RssNotification rssNotification = new RssNotification(context);
            rssNotification.sendNotification(text);

            // 1. Initial Extraction (Readability4J)
            // This process is asynchronous. It will turn the circle YELLOW.
            feedRepository.getEntryRepository().requeueMissingEntries();
            
            // The AI Pipeline Chain (Summarize -> Clean -> Translate) is now 
            // automatically triggered by the TtsExtractor once all extractions finish.
            // This prevents the AI workers from starting before the HTML content exists.
            ttsExtractor.extractAllEntries();

            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Error in RssWorker: " + e.getMessage());
            return Result.retry();
        }
    }
}
