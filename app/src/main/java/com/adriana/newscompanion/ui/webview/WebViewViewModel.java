package com.adriana.newscompanion.ui.webview;

import android.annotation.SuppressLint;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;

// Adriana start 2
import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
// ad end
import com.adriana.newscompanion.data.entry.Entry;
import com.adriana.newscompanion.data.entry.EntryRepository;
// Adriana start
import com.adriana.newscompanion.data.repository.TranslationRepository;
// ad end
import com.adriana.newscompanion.data.sharedpreferences.SharedPreferencesRepository;
import com.adriana.newscompanion.model.EntryInfo;

import java.text.SimpleDateFormat;
import java.util.Date;
import io.reactivex.rxjava3.core.Single;
import com.adriana.newscompanion.service.util.TextUtil;

@HiltViewModel
public class WebViewViewModel extends ViewModel {
    private final EntryRepository entryRepository;
    private final TranslationRepository translationRepository;
    private final SharedPreferencesRepository sharedPreferencesRepository;
    private final TextUtil textUtil;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final MutableLiveData<String> originalHtmlLiveData = new MutableLiveData<>();
    private final MutableLiveData<String> translatedHtmlLiveData = new MutableLiveData<>();
    private final MutableLiveData<String> translatedTextReady = new MutableLiveData<>();
    private final MutableLiveData<Boolean> loadingState = new MutableLiveData<>();
    private final MutableLiveData<Long> entryIdTrigger = new MutableLiveData<>();
    private final MutableLiveData<Boolean> _isTranslating = new MutableLiveData<>(false);
    public LiveData<Boolean> isTranslating() {
        return _isTranslating;
    }
    private final MutableLiveData<String> _translatedArticleContent = new MutableLiveData<>();
    public LiveData<String> getTranslatedArticleContent() {
        return _translatedArticleContent;
    }
    private final MutableLiveData<String> _translationError = new MutableLiveData<>();
    public LiveData<String> getTranslationError() {
        return _translationError;
    }
    public LiveData<Boolean> getLoadingState() {
        return loadingState;
    }
    public void setLoadingState(boolean isLoading) {
        loadingState.postValue(isLoading);
    }

    private final MutableLiveData<String> _snackbarMessage = new MutableLiveData<>();
    public LiveData<String> getSnackbarMessage() { return _snackbarMessage; }

    private final MutableLiveData<TranslationResult> _translationResult = new MutableLiveData<>();    public LiveData<TranslationResult> getTranslationResult() { return _translationResult; }

    public static class TranslationResult {
        public final String finalHtml;
        public final String finalTitle;
        public TranslationResult(String finalHtml, String finalTitle) {
            this.finalHtml = finalHtml;
            this.finalTitle = finalTitle;
        }
    }

    @Inject
    public WebViewViewModel(EntryRepository entryRepository, TranslationRepository translationRepository, SharedPreferencesRepository sharedPreferencesRepository, TextUtil textUtil) {
        this.entryRepository = entryRepository;
        this.translationRepository = translationRepository;
        this.sharedPreferencesRepository = sharedPreferencesRepository;
        this.textUtil = textUtil;
    }

    // Clean up the subscriptions when the ViewModel is no longer needed to prevent memory leaks.
    @Override
    protected void onCleared() {
        super.onCleared();
        disposables.clear();
    }

    public void resetEntry(long id) {
        entryRepository.updateHtml(null, id);
        entryRepository.updateOriginalHtml(null, id);
        entryRepository.updateTranslatedText(null, id);
        entryRepository.updateTranslated(null, id);
        entryRepository.updateContent(null, id);
        entryRepository.updateSentCountByLink(0, id);
        entryRepository.updatePriority(1, id);
    }

    public void clearLiveEntryCache(long id) {
        Entry entry = entryRepository.getEntryById(id);
        if (entry != null) {
            entry.setTranslated(null);
            entry.setHtml(null);
            entry.setContent(null);
        }
    }

    public void updateHtml(String html, long id) {
        entryRepository.updateHtml(html, id);
        translatedHtmlLiveData.postValue(html);
    }

    public void updateContent(String content, long id) {
        entryRepository.updateContent(content, id);
    }

    public void updateBookmark(String bool, long id) {
        entryRepository.updateBookmark(bool, id);
    }

    public EntryInfo getLastVisitedEntry() {
        return entryRepository.getLastVisitedEntry();
    }

    public String getHtmlById(long id) {
        return entryRepository.getHtmlById(id);
    }

    public String getStyle() {
        return "<style>\n" +
                "    @font-face {\n" +
                "        font-family: open_sans;\n" +
                "        src: url(\\\"file:///android_res/font/open_sans.ttf\\\")\n" +
                "    }\n" +
                "    body {\n" +
                "        font-family: open_sans;\n" +
                "        text-align: justify;\n" +
                "        font-size: 0.875em;\n" +
                "    }\n" +
                "</style>";
    }

    @SuppressLint("SimpleDateFormat")
    public String getHtml(String entryTitle, String feedTitle, Date publishDate, String feedImageUrl) {
        return "<div class=\"entry-header\">" +
                "  <div style=\"display: flex; align-items: center;\">" +
                "    <img style=\"margin-right: 10px; width: 20px; height: 20px\" src=" + feedImageUrl + ">" +
                "    <p style=\"font-size: 0.75em\">" + feedTitle + "</p>" +
                "  </div>" +
                "  <p style=\"margin:0; font-size: 1.25em; font-weight:bold\">" + entryTitle + "</p>" +
                "  <p style=\"font-size: 0.75em;\">" + new SimpleDateFormat("EEE, d MMM yyyy 'at' hh:mm aaa").format(publishDate) + "</p>" +
                "</div>";
    }

    public boolean endsWithBreak(String text) {
        return text.endsWith(".") || text.endsWith("?") || text.endsWith("!") || text.endsWith("！") || text.endsWith("？") || text.endsWith("。");
    }

    public EntryInfo getEntryInfoById(long id) {
        return entryRepository.getEntryInfoById(id);
    }

    public void updateOriginalHtml(String html, long id) {
        entryRepository.updateOriginalHtml(html, id);
        originalHtmlLiveData.postValue(html);
    }

    public LiveData<String> getOriginalHtmlLiveData() {
        return originalHtmlLiveData;
    }
    public LiveData<String> getTranslatedHtmlLiveData() {
        return translatedHtmlLiveData;
    }
    public String getOriginalHtmlById(long id) {
        return entryRepository.getOriginalHtmlById(id);
    }
    public void triggerEntryRefresh(long entryId) {
        entryIdTrigger.postValue(entryId);
    }

    public LiveData<Entry> getLiveEntry() {
        return Transformations.switchMap(entryIdTrigger, id ->
                entryRepository.getEntryEntityById(id)
        );
    }

    public LiveData<Entry> getEntryEntityById(long entryId) {
        return entryRepository.getEntryEntityById(entryId);
    }

    public Entry getEntryById(long entryId) {
        return entryRepository.getEntryById(entryId);
    }

    public String getTranslatedById(long id) {
        Entry entry = getEntryById(id);
        return (entry != null) ? entry.getTranslated() : null;
    }

    public void updateTranslated(String text, long entryId) {
        entryRepository.updateTranslated(text, entryId);
    }

    public void updateEntryTranslatedField(long entryId, String translatedContent) {
        Entry entry = entryRepository.getEntryById(entryId);
        if (entry != null) {
            entry.setTranslated(translatedContent);
        }
    }

    public LiveData<String> getTranslatedTextReady() {
        return translatedTextReady;
    }

    public void setTranslatedTextReady(long id, String text) {
        if (text != null && !text.trim().isEmpty()) {
            translatedTextReady.postValue(text);
        }
    }
    // Helper method to post a snackbar message from the ViewModel
    public void clearSnackbar() {
        _snackbarMessage.setValue(null);
    }// THIS IS THE FINAL, ROBUST FIX THAT PERFORMS LANGUAGE DETECTION FIRST
    public void translateArticle(long entryId) {
        final String originalHtml = entryRepository.getOriginalHtmlById(entryId);
        if (originalHtml == null || originalHtml.trim().isEmpty()) {
            _translationError.setValue("Original article content not available.");
            return;
        }

        String plainContentForDetection = textUtil.extractHtmlContent(originalHtml, "--####--");
        if (plainContentForDetection == null || plainContentForDetection.trim().isEmpty()) {
            _translationError.setValue("Could not extract content to identify language.");
            return;
        }

        final String targetLang = sharedPreferencesRepository.getDefaultTranslationLanguage();
        if (targetLang == null || targetLang.isEmpty()) {
            _translationError.setValue("Please select a target translation language first.");
            return;
        }

        disposables.add(textUtil.identifyLanguageRx(plainContentForDetection)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        sourceLang -> {
                            if (sourceLang.equalsIgnoreCase(targetLang)) {
                                _snackbarMessage.setValue("Article is already in the target language.");
                                return;
                            }

                            _isTranslating.setValue(true);

                            EntryInfo entryInfo = entryRepository.getEntryInfoById(entryId);
                            if (entryInfo == null || entryInfo.getEntryTitle() == null) {
                                _translationError.setValue("Cannot translate, article data is missing.");
                                _isTranslating.setValue(false);
                                return;
                            }
                            final String originalTitle = entryInfo.getEntryTitle();

                            // THIS IS THE FIX: Use TextUtil for both jobs
                            Single<String> titleJob = textUtil.translateText(sourceLang, targetLang, originalTitle);
                            Single<String> bodyJob = textUtil.translateHtml(sourceLang, targetLang, originalHtml, progress -> {});

                            disposables.add(Single.zip(
                                            titleJob,
                                            bodyJob,
                                            (translatedTitle, translatedBodyHtml) -> {
                                                // Background work
                                                entryRepository.updateTranslatedTitle(translatedTitle, entryId);
                                                entryRepository.updateHtml(translatedBodyHtml, entryId);
                                                String translatedBodyText = textUtil.extractHtmlContent(translatedBodyHtml, "--####--");
                                                entryRepository.updateTranslatedSummary(translatedBodyText, entryId);
                                                entryRepository.updateTranslated(translatedTitle + "--####--" + translatedBodyText, entryId);
                                                return new TranslationResult(translatedBodyHtml, translatedTitle);
                                            })
                                    .subscribeOn(Schedulers.io())
                                    .observeOn(AndroidSchedulers.mainThread())
                                    .subscribe(
                                            result -> {
                                                _isTranslating.setValue(false);
                                                _translationResult.setValue(result);
                                            },
                                            error -> {
                                                _isTranslating.setValue(false);
                                                _translationError.setValue("Translation failed: " + error.getMessage());
                                            }
                                    )
                            );
                        },
                        error -> _translationError.setValue("Could not identify source language.")
                )
        );
    }
    public void cancelTranslation() {
        // This interrupts and cancels all ongoing RxJava jobs added to this CompositeDisposable.
        disposables.clear();
        // Set the translating state back to false to hide the progress bar.
        _isTranslating.postValue(false);
        // Post a message to the UI to confirm cancellation.
        _snackbarMessage.postValue("Translation cancelled.");
    }
}
