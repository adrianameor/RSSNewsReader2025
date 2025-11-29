package my.mmu.rssnewsreader.worker;

import android.content.Context;
import android.content.Intent;
import android.util.Log;import androidx.annotation.NonNull;
import androidx.hilt.work.HiltWorker;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;

import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.inject.Inject;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;
import my.mmu.rssnewsreader.data.entry.EntryRepository;
import my.mmu.rssnewsreader.data.repository.TranslationRepository;
import my.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import my.mmu.rssnewsreader.model.EntryInfo;

@HiltWorker
public class TranslationWorker extends Worker {

    private static final String TAG = "TranslationWorker";
    private final EntryRepository entryRepository;
    private final TranslationRepository translationRepository;
    private final SharedPreferencesRepository sharedPreferencesRepository;
    private final LanguageIdentifier languageIdentifier;

    @AssistedInject
    public TranslationWorker(
            @Assisted @NonNull Context context,
            @Assisted @NonNull WorkerParameters workerParams,
            EntryRepository entryRepository,
            TranslationRepository translationRepository,
            SharedPreferencesRepository sharedPreferencesRepository) {
        super(context, workerParams);
        this.entryRepository = entryRepository;
        this.translationRepository = translationRepository;
        this.sharedPreferencesRepository = sharedPreferencesRepository;
        this.languageIdentifier = LanguageIdentification.getClient();
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "WORKER: doWork started.");
        List<EntryInfo> entriesToProcess = entryRepository.getUntranslatedEntriesInfo();
        Log.d(TAG, "WORKER: Found " + entriesToProcess.size() + " entries that may need translation.");

        String defaultTargetLang = sharedPreferencesRepository.getDefaultTranslationLanguage();

        for (EntryInfo entry : entriesToProcess) {
            try {
                String originalTitle = entry.getEntryTitle();
                if (originalTitle == null || originalTitle.isEmpty()) {
                    continue; // Skip if there's no title.
                }

                // THIS IS THE FIX: We are removing the complex, unreliable "hybrid" logic.
                // We will now ONLY TRUST the language set for the entire feed (`feedLanguage`).
                // This is a much more reliable signal and will prevent jumbled translations.
                String sourceLang = entry.getFeedLanguage();
                String targetLang = entry.getTargetTranslationLanguage() != null ? entry.getTargetTranslationLanguage() : defaultTargetLang;

                // Condition to check if translation is needed at all for this entry.
                if (sourceLang == null || sourceLang.isEmpty() || sourceLang.equals("und") || sourceLang.equalsIgnoreCase(targetLang)) {
                    Log.d(TAG, "WORKER: Skipping entry " + entry.getEntryId() + " (Source: " + sourceLang + ", Target: " + targetLang + " - no translation needed).");
                    continue;
                }

                Log.d(TAG, "WORKER: Processing entry ID: " + entry.getEntryId() + " ('" + originalTitle + "') from " + sourceLang + " to " + targetLang);

                // Translate Title only if it's missing or empty.
                if (entry.getTranslatedTitle() == null || entry.getTranslatedTitle().trim().isEmpty()) {
                    String translatedTitle = translationRepository.translateText(originalTitle, sourceLang, targetLang).blockingGet();
                    Log.d(TAG, "WORKER: For entry " + entry.getEntryId() + ", received translated title: '" + translatedTitle + "'");
                    entryRepository.updateTranslatedTitle(translatedTitle, entry.getEntryId());
                }

                // Translate Summary (Description) only if it's missing or empty.
                if (entry.getTranslatedSummary() == null || entry.getTranslatedSummary().trim().isEmpty()) {
                    if (entry.getEntryDescription() != null && !entry.getEntryDescription().isEmpty()) {
                        String translatedSummary = translationRepository.translateText(entry.getEntryDescription(), sourceLang, targetLang).blockingGet();
                        entryRepository.updateTranslatedSummary(translatedSummary, entry.getEntryId());
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "WORKER: CRASHED while processing entry " + entry.getEntryId(), e);
            }
        }

        Log.d(TAG, "WORKER: Finished processing all entries.");

        // Send the "shout" to the UI to tell it to refresh.
        Log.d(TAG, "Broadcasting 'translation-finished' event.");
        Intent intent = new Intent("translation-finished");
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);

        return Result.success();
    }
}