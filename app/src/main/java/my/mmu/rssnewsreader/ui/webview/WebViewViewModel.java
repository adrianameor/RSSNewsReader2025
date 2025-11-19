package my.mmu.rssnewsreader.ui.webview;

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
import my.mmu.rssnewsreader.data.entry.Entry;
import my.mmu.rssnewsreader.data.entry.EntryRepository;
// Adriana start
import my.mmu.rssnewsreader.data.repository.TranslationRepository;
// ad end
import my.mmu.rssnewsreader.model.EntryInfo;

import java.text.SimpleDateFormat;
import java.util.Date;

@HiltViewModel
public class WebViewViewModel extends ViewModel {

    // Adriana start
    private final EntryRepository entryRepository;
    // inject the repository
    // we will add the Translation repository to the WebViewViewModel's constructor
    // Hilt will automatically provide the instance we created
    private final TranslationRepository translationRepository;
    private final CompositeDisposable disposables = new CompositeDisposable();
    // Adriana end

    private final MutableLiveData<String> originalHtmlLiveData = new MutableLiveData<>();
    private final MutableLiveData<String> translatedHtmlLiveData = new MutableLiveData<>();
    private final MutableLiveData<String> translatedTextReady = new MutableLiveData<>();
    private final MutableLiveData<Boolean> loadingState = new MutableLiveData<>();
    private final MutableLiveData<Long> entryIdTrigger = new MutableLiveData<>();

    // Adriana: Start of New Code for Translation ---
    // We will add new MutableLiveData objects to the ViewModel to represent
    // the state of the translation process

    // Notepad for the Brain: Is the translator busy?
    // _isTranslating: a boolean to indicate when a translation is in progress (for showing a loading indicator)
    private final MutableLiveData<Boolean> _isTranslating = new MutableLiveData<>(false);
    public LiveData<Boolean> isTranslating() {
        return _isTranslating;
    }

    // Notepad for the Brain: What is the finished translation?
    // _translatedArticleContent: will hold the successfully translated text
    private final MutableLiveData<String> _translatedArticleContent = new MutableLiveData<>();
    public LiveData<String> getTranslatedArticleContent() {
        return _translatedArticleContent;
    }

    // Notepad for the Brain: Did something go wrong?
    // _translationError: will hold the any error messages if the translation fails
    private final MutableLiveData<String> _translationError = new MutableLiveData<>();
    public LiveData<String> getTranslationError() {
        return _translationError;
    }

    // ---Adriana: End of New Code for Translation ---
    public LiveData<Boolean> getLoadingState() {
        return loadingState;
    }

    public void setLoadingState(boolean isLoading) {
        loadingState.postValue(isLoading);
    }

    /* This is the old constructor. We comment it out for reference.
    @Inject
    public WebViewViewModel(EntryRepository entryRepository) {
        this.entryRepository = entryRepository;
    }
    */
    // Adriana Start
    // This is the new constructor. We give the Brain the new translator tool.
    @Inject
    public WebViewViewModel(EntryRepository entryRepository, TranslationRepository translationRepository) {
        this.entryRepository = entryRepository;
        this.translationRepository = translationRepository;
    }

    // New Instruction for the Brain: "Translate this text!"
    public void translateArticle(String text, String sourceLang, String targetLang) {
        // 1. Write "Yes" on the "Is Translating?" notepad.
        _isTranslating.setValue(true);

        // 2. Give the work to the translator to do in a separate room.
        disposables.add(translationRepository.translate(text, sourceLang, targetLang)
                .subscribeOn(Schedulers.io()) // The separate room (a background thread)
                .observeOn(AndroidSchedulers.mainThread()) // Bring the result to the main desk (the UI thread)
                .subscribe(
                        // 3. On Success: Write the result on the notepad.
                        translatedText -> {
                            _isTranslating.setValue(false); // Update status to "No"
                            _translatedArticleContent.setValue(translatedText);
                        },
                        // 4. On Failure: Write the error on the notepad.
                        error -> {
                            _isTranslating.setValue(false); // Update status to "No"
                            _translationError.setValue(error.getMessage());
                        }
                ));
    }

    // Clean up the subscriptions when the ViewModel is no longer needed to prevent memory leaks.
    @Override
    protected void onCleared() {
        super.onCleared();
        disposables.clear();
    }
    // Adriana end

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
                // adriana start
                "        src: url(\\\"file:///android_res/font/open_sans.ttf\\\")\n" +
                // adriana end
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
}
