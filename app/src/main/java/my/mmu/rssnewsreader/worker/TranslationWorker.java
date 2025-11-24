package my.mmu.rssnewsreader.worker;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;
import my.mmu.rssnewsreader.data.entry.EntryRepository;
import my.mmu.rssnewsreader.data.repository.TranslationRepository;
import my.mmu.rssnewsreader.model.EntryInfo; // THIS IS THE MISSING IMPORT
import java.util.List;
import android.util.Log;
import androidx.hilt.work.HiltWorker;

@HiltWorker
public class TranslationWorker extends Worker {

    private final EntryRepository entryRepository;
    private final TranslationRepository translationRepository;

    @AssistedInject
    public TranslationWorker(
            @Assisted @NonNull Context context,
            @Assisted @NonNull WorkerParameters workerParams,
            EntryRepository entryRepository,
            TranslationRepository translationRepository) {
        super(context, workerParams);
        this.entryRepository = entryRepository;
        this.translationRepository = translationRepository;
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d("TranslationWorker", "doWork: Worker started.");
        // Use the correct method to get the info, including the language
        List<EntryInfo> untranslatedEntries = entryRepository.getUntranslatedEntriesInfo();
        Log.d("TranslationWorker", "doWork: Found " + untranslatedEntries.size() + " entries to process.");


        for (EntryInfo entry : untranslatedEntries) {
            try {
                Log.d("TranslationWorker", "doWork: Processing entry ID: " + entry.getEntryId());

                // Only proceed if the title or summary actually needs translation
                if (entry.getTranslatedTitle() == null || entry.getTranslatedSummary() == null) {
                    String sourceLang = entry.getFeedLanguage();
                    // Use the per-article language if set, otherwise default to English for the background job
                    String targetLang = entry.getTargetTranslationLanguage() != null ? entry.getTargetTranslationLanguage() : "en";

                    if (sourceLang == null || sourceLang.isEmpty() || sourceLang.equalsIgnoreCase(targetLang)) {
                        continue; // Skip if we can't translate
                    }

                    // --- THIS IS THE FIX: We now make the calls BLOCKING ---

                    // Translate Title if it's missing
                    if (entry.getTranslatedTitle() == null && entry.getEntryTitle() != null && !entry.getEntryTitle().isEmpty()) {
                        // .blockingGet() forces the worker to WAIT here until the translation is complete.
                        String translatedTitle = translationRepository.translate(entry.getEntryTitle(), sourceLang, targetLang).blockingGet();
                        entryRepository.updateTranslatedTitle(translatedTitle, entry.getEntryId());
                        Log.d("TranslationWorker", "doWork: Successfully updated title for entry " + entry.getEntryId());
                    }

                    // Translate Summary if it's missing
                    if (entry.getTranslatedSummary() == null && entry.getEntryDescription() != null && !entry.getEntryDescription().isEmpty()) {
                        // .blockingGet() forces the worker to WAIT here too.
                        String translatedSummary = translationRepository.translate(entry.getEntryDescription(), sourceLang, targetLang).blockingGet();
                        entryRepository.updateTranslatedSummary(translatedSummary, entry.getEntryId());
                        Log.d("TranslationWorker", "doWork: Successfully updated summary for entry " + entry.getEntryId());
                    }
                }
            } catch (Exception e) {
                // If one article fails, log it and continue to the next one.
                Log.e("TranslationWorker", "Failed to translate title/summary for entry " + entry.getEntryId(), e);
            }
        }
        return Result.success();
    }
}