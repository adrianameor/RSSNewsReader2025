package my.mmu.rssnewsreader.service.rss;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.hilt.work.HiltWorker;import androidx.work.Constraints; // Added for clarity, though may not be used directly here
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import my.mmu.rssnewsreader.data.feed.FeedRepository;
import my.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository; // <-- THE MISSING IMPORT
import my.mmu.rssnewsreader.service.tts.TtsExtractor;
import my.mmu.rssnewsreader.service.util.AutoTranslator;
import my.mmu.rssnewsreader.service.util.TextUtil;
import my.mmu.rssnewsreader.worker.TranslationWorker;

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

    @AssistedInject
    public RssWorker(
            @Assisted @NonNull Context context,
            @Assisted @NonNull WorkerParameters workerParams,
            FeedRepository feedRepository,
            TtsExtractor ttsExtractor,
            SharedPreferencesRepository sharedPreferencesRepository, TextUtil textUtil) {
        super(context, workerParams);
        this.context = context;
        this.feedRepository = feedRepository;
        this.ttsExtractor = ttsExtractor;
        this.sharedPreferencesRepository = sharedPreferencesRepository;
        this.textUtil = textUtil;
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Log.d(TAG, "Starting RSS refresh...");
            String text = feedRepository.refreshEntries();
            RssNotification rssNotification = new RssNotification(context);
            rssNotification.sendNotification(text);
            feedRepository.getEntryRepository().requeueMissingEntries();
            if (feedRepository.getEntryRepository().hasEmptyContentEntries()) {
                ttsExtractor.extractAllEntries();
            } else {
                Log.d(TAG, "No entries to extract in RssWorker.");
            }

            // The AutoTranslator is a separate system for translating the main article body, we leave it as is.
            AutoTranslator autoTranslator = new AutoTranslator(
                    feedRepository.getEntryRepository(),
                    this.textUtil,
                    feedRepository.getSharedPreferencesRepository()
            );
            autoTranslator.runAutoTranslation();


            // THIS IS THE FIX:
            // We check the user's setting before starting the background title translation.
            if (sharedPreferencesRepository.getAutoTranslate()) {
                Log.d(TAG, "Auto-translate is ON. Enqueuing background title/summary translation worker.");
                WorkManager workManager = WorkManager.getInstance(context);
                OneTimeWorkRequest translationWorkRequest = new OneTimeWorkRequest.Builder(TranslationWorker.class).build();
                workManager.enqueue(translationWorkRequest);
            } else {
                Log.d(TAG, "Auto-translate is OFF. Skipping background title/summary translation worker.");
            }

            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Error in RSS refresh: " + e.getMessage(), e); // Log the full exception
            return Result.retry();
        }
    }
}
