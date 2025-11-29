package my.mmu.rssnewsreader.ui.webview;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import kotlin.reflect.jvm.internal.impl.metadata.ProtoBuf;
import kotlin.reflect.jvm.internal.impl.name.SpecialNames;
import my.mmu.rssnewsreader.R;
import my.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import my.mmu.rssnewsreader.model.EntryInfo;
import my.mmu.rssnewsreader.service.tts.TtsExtractor;
import my.mmu.rssnewsreader.service.tts.TtsPlayer;
import my.mmu.rssnewsreader.service.tts.TtsPlaylist;
import my.mmu.rssnewsreader.service.tts.TtsService;
import my.mmu.rssnewsreader.databinding.ActivityWebviewBinding;
import my.mmu.rssnewsreader.service.util.TextUtil;
import my.mmu.rssnewsreader.ui.feed.ReloadDialog;
import my.mmu.rssnewsreader.data.entry.Entry;
import my.mmu.rssnewsreader.data.entry.EntryRepository;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;



import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import my.mmu.rssnewsreader.data.repository.TranslationRepository;

@AndroidEntryPoint
public class WebViewActivity extends AppCompatActivity implements WebViewListener {
    private final static String TAG = "WebViewActivity";
    private LiveData<Entry> autoTranslationObserver;
    private Observer<Entry> checkAutoTranslated;
    // Share
    private ActivityWebviewBinding binding;
    private WebViewViewModel webViewViewModel;
    private WebView webView;
    private LinearProgressIndicator loading;
    private MenuItem browserButton;
    private MenuItem offlineButton;
    private MenuItem reloadButton;
    private MenuItem bookmarkButton;
    private MenuItem translationButton;
    private MenuItem highlightTextButton;
    private MenuItem backgroundMusicButton;
    private String currentLink;
    private long currentId;
    private long feedId;
    private String html;
    private String content;
    private String bookmark;
    private boolean isPlaying;
    private boolean isReadingMode;
    private boolean showOfflineButton;
    private boolean clearHistory;
    private boolean isInitialLoad = true;

    private MenuItem toggleTranslationButton;
    private boolean isTranslatedView = true;
    private MaterialToolbar toolbar;

    // Translation
    private String targetLanguage;
    private String translationMethod;
    private TextUtil textUtil;
    private CompositeDisposable compositeDisposable;
    private LiveData<Entry> liveEntryObserver;

    // Reading Mode
    private MenuItem switchPlayModeButton;
    private LinearLayout functionButtonsReadingMode;

    // Playing Mode
    private MenuItem switchReadModeButton;
    private MaterialButton playPauseButton;
    private MaterialButton skipNextButton;
    private MaterialButton skipPreviousButton;
    private MaterialButton fastForwardButton;
    private MaterialButton rewindButton;
    private LinearLayout functionButtons;
    private MediaBrowserHelper mMediaBrowserHelper;
    private Set<Long> translatedArticleIds = new HashSet<>();

    @Inject
    TtsPlayer ttsPlayer;

    @Inject
    TtsPlaylist ttsPlaylist;

    @Inject
    TtsExtractor ttsExtractor;

    @Inject
    SharedPreferencesRepository sharedPreferencesRepository;

    @Inject
    EntryRepository entryRepository;

    @Inject
    TranslationRepository translationRepository;

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (webView.canGoBack()) {
                    browserButton.setVisible(true);
                    webView.goBack();
                } else {
                    finish();
                }
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private final MediaControllerCompat.Callback mediaControllerCallback =
            new MediaControllerCompat.Callback() {
                @Override
                public void onPlaybackStateChanged(@NonNull PlaybackStateCompat state) {
                    super.onPlaybackStateChanged(state);
                    isPlaying = state.getState() == PlaybackStateCompat.STATE_PLAYING;
                    updatePlayPauseButtonIcon(isPlaying);
                    Log.d(TAG, "Playback state changed: " + state.getState());
                }
            };

    private void showTranslationLanguageDialog(Context context) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Default Translation Language");

        CharSequence[] entries = getResources().getStringArray(R.array.defaultTranslationLanguage);
        CharSequence[] entryValues = getResources().getStringArray(R.array.defaultTranslationLanguage_values);

        builder.setItems(entries, (dialog, which) -> {
            makeSnackbar("Translating to " + entries[which]);
            String selectedValue = entryValues[which].toString();
            sharedPreferencesRepository.setDefaultTranslationLanguage(selectedValue);
            targetLanguage = selectedValue;
            // translate();
            // This triggers the new translation logic after a language is selected from the dialog
            handleOtherToolbarItems(R.id.translate);
            dialog.dismiss();
        });

        builder.show();
    }

    /*private void doWhenTranslationFinish(EntryInfo entryInfo, String originalHtml, String translatedHtml) {
        loading.setVisibility(View.INVISIBLE);

        if (webViewViewModel.getOriginalHtmlById(currentId) == null && originalHtml != null) {
            webViewViewModel.updateOriginalHtml(originalHtml, currentId);
            entryRepository.updateOriginalHtml(originalHtml, currentId);
            Log.d(TAG, "Original HTML backed up from method parameter.");
        }

        Document doc = Jsoup.parse(translatedHtml);
        doc.head().append(webViewViewModel.getStyle());
        Objects.requireNonNull(doc.selectFirst("body"))
                .prepend(webViewViewModel.getHtml(
                        entryInfo.getEntryTitle(),
                        entryInfo.getFeedTitle(),
                        entryInfo.getEntryPublishedDate(),
                        entryInfo.getFeedImageUrl()
                ));
        String finalHtml = doc.html();

        webViewViewModel.updateHtml(finalHtml, currentId);
        entryRepository.updateHtml(finalHtml, currentId);

        String translatedContent = textUtil.extractHtmlContent(finalHtml, "--####--");
        webViewViewModel.updateTranslated(translatedContent, currentId);
        webViewViewModel.updateEntryTranslatedField(currentId, translatedContent);
        entryRepository.updateTranslatedText(translatedContent, currentId);

        webView.loadDataWithBaseURL("file///android_res/", finalHtml, "text/html", "UTF-8", null);

        toggleTranslationButton.setVisible(true);
        isTranslatedView = true;
        sharedPreferencesRepository.setIsTranslatedView(currentId, true);

        webViewViewModel.setTranslatedTextReady(currentId, translatedContent);

        Log.d(TAG, "FINAL translatedContent passed to TTS: " + translatedContent);
        Log.d(TAG, "FINAL currentId: " + currentId + ", isTranslatedView: " + isTranslatedView);
    }*/

    /*private void translate() {
        Log.d(TAG, "translate: html\n" + webViewViewModel.getHtmlById(currentId));
        makeSnackbar("Translation in progress");
        loading.setVisibility(View.VISIBLE);
        loading.setProgress(0);

        String content = webViewViewModel.getHtmlById(currentId);
        EntryInfo entryInfo = webViewViewModel.getEntryInfoById(currentId);
        if (entryInfo == null) {
            makeSnackbar("Entry info could not be loaded.");
            return;
        }

        if (webViewViewModel.getOriginalHtmlById(currentId) == null) {
            webViewViewModel.updateOriginalHtml(content, currentId);
            Log.d(TAG, "Original HTML backed up before translation.");
        }

        String feedLanguage = entryInfo.getFeedLanguage();
        String userConfiguredLang = sharedPreferencesRepository.getDefaultTranslationLanguage();

        textUtil.identifyLanguageRx(content).subscribe(
                identifiedLanguage -> {
                    String sourceLanguage = (userConfiguredLang != null && !userConfiguredLang.isEmpty())
                            ? feedLanguage : identifiedLanguage;

                    Log.d(TAG, "Translating from " + sourceLanguage + " to " + targetLanguage);
                    performTranslation(sourceLanguage, targetLanguage, content, entryInfo.getEntryTitle());
                },
                error -> {
                    Log.e(TAG, "Language identification failed, falling back to feedLanguage");
                    performTranslation(feedLanguage, targetLanguage, content, entryInfo.getEntryTitle());
                }
        );
    }*/

    /*private void performTranslation(String sourceLang, String targetLang, String html, String title) {
        Single<String> translationFlow;
        switch (translationMethod) {
            case "lineByLine":
                translationFlow = textUtil.translateHtmlLineByLine(sourceLang, targetLang, html, title, currentId, this::updateLoadingProgress);
                break;
            case "paragraphByParagraph":
                translationFlow = textUtil.translateHtmlByParagraph(sourceLang, targetLang, html, title, currentId, this::updateLoadingProgress);
                break;
            default:
                translationFlow = textUtil.translateHtmlAllAtOnce(sourceLang, targetLang, html, title, currentId, this::updateLoadingProgress);
        }

        final String originalHtml = html;

        translationFlow.subscribe(
                translatedHtml -> {
                    Log.d(TAG, "Translation completed");
                    doWhenTranslationFinish(webViewViewModel.getLastVisitedEntry(), originalHtml, translatedHtml);
                },
                throwable -> {
                    Log.e(TAG, "Translation failed", throwable);
                    loading.setVisibility(View.GONE);
                }
        );
    }*/

    private void initializeTranslationObservers() {
        // This watches the "Is Translating?" notepad from the ViewModel
        webViewViewModel.isTranslating().observe(this, isTranslating -> {
            if (isTranslating) {
                loading.setVisibility(View.VISIBLE);
                loading.setIndeterminate(true); // Shows a continuous loading animation
                makeSnackbar("Translating... please wait");
            } else {
                loading.setIndeterminate(false);
                loading.setVisibility(View.GONE);
            }
        });

        // This watches the "Finished Translation" notepad
        webViewViewModel.getTranslatedArticleContent().observe(this, translatedHtml -> {
            if (translatedHtml == null || translatedHtml.isEmpty()) {
                makeSnackbar("Translation returned empty content.");
                return;
            }

            // Save the new translated HTML to the database
            webViewViewModel.updateHtml(translatedHtml, currentId);

            // Also save the plain text version for the TTS player
            String translatedContent = textUtil.extractHtmlContent(translatedHtml, "--####--");
            webViewViewModel.updateTranslated(translatedContent, currentId);

            // Load the new HTML into the WebView
            loadHtmlIntoWebView(translatedHtml);

            // Update the UI state
            isTranslatedView = true;
            sharedPreferencesRepository.setIsTranslatedView(currentId, true);
            toggleTranslationButton.setVisible(true);
            toggleTranslationButton.setTitle("Show Original");
            makeSnackbar("Translation successful!");
        });

        // This watches the "Did something go wrong?" notepad
        webViewViewModel.getTranslationError().observe(this, error -> {
            if (error != null && !error.isEmpty()) {
                new AlertDialog.Builder(this)
                        .setTitle("Translation Error")
                        .setMessage(error)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);


        webViewViewModel = new ViewModelProvider(this).get(WebViewViewModel.class);
        initializeTranslationObservers();

        /*webViewViewModel.getTranslatedTextReady().observe(this, translatedText -> {
            if (!isReadingMode && isTranslatedView && translatedText != null && !translatedText.trim().isEmpty()) {
                Log.d(TAG, "TTS triggered after LiveData translation update");

                String lang = getLanguageForCurrentView(currentId, isTranslatedView, "en");

                ttsPlayer.extract(currentId, feedId, translatedText, lang);
                Log.d(TAG, "LiveData.observe fired, isTranslatedView = " + isTranslatedView);
            }
        });*/

        isReadingMode = getIntent().getBooleanExtra("read", false);

        if (ttsPlayer.isPlaying() && isReadingMode) {
            ttsPlayer.stop();
        }

        initializeUI();

        targetLanguage = sharedPreferencesRepository.getDefaultTranslationLanguage();
        translationMethod = sharedPreferencesRepository.getTranslationMethod();
        compositeDisposable = new CompositeDisposable();
        textUtil = new TextUtil(sharedPreferencesRepository, translationRepository);

        initializeToolbarListeners();
        initializeWebViewSettings();
        initializePlaybackModes();
        loadEntryContent();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();

        if (event.getAction() == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                        keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                        keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
                        keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
                        keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS)) {

            MediaSessionCompat mediaSession = TtsService.getMediaSession();
            if (mediaSession != null && mediaSession.isActive()) {
                MediaControllerCompat controller = mediaSession.getController();
                controller.dispatchMediaButtonEvent(event);
                return true;
            }
        }

        return super.dispatchKeyEvent(event);
    }

    private void initializeUI() {
        binding = ActivityWebviewBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        webView = binding.webview;
        loading = binding.loadingWebView;
        functionButtons = binding.functionButtons;
        functionButtonsReadingMode = binding.functionButtonsReading;

        playPauseButton = binding.playPauseButton;
        skipNextButton = binding.skipNextButton;
        skipPreviousButton = binding.skipPreviousButton;
        fastForwardButton = binding.fastForwardButton;
        rewindButton = binding.rewindButton;

        toolbar = binding.toolbar;
        browserButton = toolbar.getMenu().findItem(R.id.openInBrowser);
        offlineButton = toolbar.getMenu().findItem(R.id.exitBrowser);
        reloadButton = toolbar.getMenu().findItem(R.id.reload);
        bookmarkButton = toolbar.getMenu().findItem(R.id.bookmark);
        translationButton = toolbar.getMenu().findItem(R.id.translate);
        toggleTranslationButton = toolbar.getMenu().findItem(R.id.toggleTranslation);
        highlightTextButton = toolbar.getMenu().findItem(R.id.highlightText);
        backgroundMusicButton = toolbar.getMenu().findItem(R.id.toggleBackgroundMusic);
        switchReadModeButton = toolbar.getMenu().findItem(R.id.switchReadMode);
        switchPlayModeButton = toolbar.getMenu().findItem(R.id.switchPlayMode);

        toggleTranslationButton.setVisible(false);

        highlightTextButton.setTitle(sharedPreferencesRepository.getHighlightText()
                ? R.string.highlight_text_turn_off : R.string.highlight_text_turn_on);
        backgroundMusicButton.setTitle(sharedPreferencesRepository.getBackgroundMusic()
                ? R.string.background_music_turn_off : R.string.background_music_turn_on);
    }

    private void loadHtmlIntoWebView(String html) {
        // THIS IS THE FIX for the "Loading title..." bug.
        EntryInfo entryInfo = webViewViewModel.getEntryInfoById(currentId);
        String titleToDisplay;

        if (entryInfo != null) {
            if (isTranslatedView) {
                // If we want the translated view, try to get the translated title first.
                titleToDisplay = entryInfo.getTranslatedTitle();
                // If it's not ready, gracefully fall back to the original title.
                if (titleToDisplay == null || titleToDisplay.isEmpty()) {
                    titleToDisplay = entryInfo.getEntryTitle();
                }
            } else {
                // Otherwise, we are in the original view, so just use the original title.
                titleToDisplay = entryInfo.getEntryTitle();
            }
        } else {
            // Ultimate fallback if entryInfo itself is null
            titleToDisplay = "Title not available";
        }

        // Now, call the other method that does the real work, passing the correct title.
        loadHtmlIntoWebView(html, titleToDisplay);
    }

    private void updateToggleTranslationVisibility() {
        String originalHtml = webViewViewModel.getOriginalHtmlById(currentId);
        String translatedHtml = webViewViewModel.getHtmlById(currentId);

        if (originalHtml != null && translatedHtml != null && !originalHtml.equals(translatedHtml)) {
            toggleTranslationButton.setVisible(true);
        } else {
            toggleTranslationButton.setVisible(false);
        }

        Log.d(TAG, "ToggleTranslationButton visibility set to: " + (originalHtml != null && translatedHtml != null && !originalHtml.equals(translatedHtml)));
    }

    private void initializePlaybackModes() {
        if (isReadingMode) {
            switchReadMode();
        } else {
            switchPlayMode();
        }
    }

    private void loadEntryContent() {
        long entryId = getIntent().getLongExtra("entry_id", -1);
        if (entryId == -1) {
            makeSnackbar("Error: No article ID provided.");
            finish();
            return;
        }
        currentId = entryId;

        EntryInfo entryInfo = webViewViewModel.getEntryInfoById(currentId);
        if (entryInfo == null) {
            makeSnackbar("Error: Could not load article data.");
            finish();
            return;
        }

        currentLink = entryInfo.getEntryLink();
        feedId = entryInfo.getFeedId();
        bookmark = entryInfo.getBookmark();

        // --- THIS IS THE FIX ---
        // First, check if there is any offline content at all.
        String originalHtml = entryRepository.getOriginalHtmlById(currentId);
        if (originalHtml == null || originalHtml.trim().isEmpty()) {
            // This is a "RED CIRCLE" article. No offline content exists.
            Log.d(TAG, "No offline content found for entry " + currentId + ". Loading URL directly.");

            // Directly load the article's live URL into the WebView.
            if (currentLink != null && !currentLink.isEmpty()) {
                webView.loadUrl(currentLink);
            } else {
                // If there's no link, we can't do anything. Show an error.
                makeSnackbar("Error: No URL found for this article.");
                finish();
                return;
            }

            // Set up the UI for "browser mode"
            if (getSupportActionBar() != null) {
                getSupportActionBar().setTitle(entryInfo.getEntryTitle());
            }
            browserButton.setVisible(false); // We are already in the browser
            offlineButton.setVisible(false);  // Show the button to return to an (empty) offline view
            toggleTranslationButton.setVisible(false); // No offline content to translate
            reloadButton.setVisible(true);
            highlightTextButton.setVisible(false);

            // Stop further processing in this method to prevent the crash.
            return;
        }
        // --- END OF FIX ---


        // If we reach here, it means offline content EXISTS (yellow or green circle).
        // The rest of the original method can now run safely.
        Log.d("DEBUG_TRANSLATION", "--- Starting loadEntryContent for offline article ---");

        String titleFromIntent = getIntent().getStringExtra("entry_title");
        boolean isStartingInTranslatedView = getIntent().getBooleanExtra("is_translated", false);
        Log.d("DEBUG_TRANSLATION", "Received from Intent: is_translated = " + isStartingInTranslatedView);

        String titleToDisplay = (titleFromIntent != null && !titleFromIntent.isEmpty()) ? titleFromIntent : entryInfo.getEntryTitle();

        isTranslatedView = isStartingInTranslatedView;
        sharedPreferencesRepository.setIsTranslatedView(currentId, isTranslatedView);
        Log.d("DEBUG_TRANSLATION", "State set: isTranslatedView = " + isTranslatedView);

        String htmlToLoad;
        String contentForTts;

        if (isTranslatedView) {
            Log.d("DEBUG_TRANSLATION", "Branch: Trying to load TRANSLATED content.");
            htmlToLoad = entryRepository.getHtmlById(currentId);
            Log.d("DEBUG_TRANSLATION", "Result from getHtmlById (translated): " + (htmlToLoad == null ? "null" : "found " + htmlToLoad.length() + " chars"));

            if (htmlToLoad == null || htmlToLoad.trim().isEmpty()) {
                Log.w("DEBUG_TRANSLATION", "Translated HTML was not ready. Falling back to ORIGINAL content for this load.");
                htmlToLoad = originalHtml; // We already fetched this
                Log.d("DEBUG_TRANSLATION", "Result from getOriginalHtmlById (fallback): " + (htmlToLoad == null ? "null" : "found " + htmlToLoad.length() + " chars"));
            }
            contentForTts = entryRepository.getEntryById(currentId) != null ? entryRepository.getEntryById(currentId).getTranslated() : null;
        } else {
            Log.d("DEBUG_TRANSLATION", "Branch: Loading ORIGINAL content.");
            htmlToLoad = originalHtml; // Use the one we already fetched
            Log.d("DEBUG_TRANSLATION", "Result from getOriginalHtmlById: " + (htmlToLoad == null ? "null" : "found " + htmlToLoad.length() + " chars"));
            contentForTts = entryRepository.getEntryById(currentId) != null ? entryRepository.getEntryById(currentId).getContent() : null;
        }

        if (htmlToLoad == null || htmlToLoad.trim().isEmpty()) {
            Log.e("DEBUG_TRANSLATION", "CRITICAL: htmlToLoad is STILL null or empty after all checks. Loading error page.");
            htmlToLoad = "<html><body><h2>Content could not be loaded.</h2></body></html>";
        }

        Log.d("DEBUG_TRANSLATION", "Final decision: Loading HTML into WebView (" + htmlToLoad.length() + " chars).");
        loadHtmlIntoWebView(htmlToLoad, titleToDisplay);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(titleToDisplay);
        }

        boolean hasTranslatedVersionInDb = entryRepository.getHtmlById(currentId) != null && !entryRepository.getHtmlById(currentId).isEmpty();
        toggleTranslationButton.setVisible(hasTranslatedVersionInDb);
        toggleTranslationButton.setTitle(isTranslatedView ? "Show Original" : "Show Translation");

        if (bookmark == null || bookmark.equals("N")) {
            bookmarkButton.setIcon(R.drawable.ic_bookmark_outline);
        } else {
            bookmarkButton.setIcon(R.drawable.ic_bookmark_filled);
        }

        String lang = getLanguageForCurrentView(currentId, isTranslatedView, "en");
        ttsExtractor.setCurrentLanguage(lang, true);
        if (contentForTts != null && !contentForTts.trim().isEmpty()) {
            ttsPlayer.extract(currentId, feedId, contentForTts, lang);
        }

        sharedPreferencesRepository.setCurrentReadingEntryId(currentId);
        syncLoadingWithTts();
    }

    private void updateToggleStateAndWebView(String originalHtml, String translatedHtml) {
        boolean hasOriginal = originalHtml != null && !originalHtml.trim().isEmpty();
        boolean hasTranslated = translatedHtml != null && !translatedHtml.trim().isEmpty();

        toggleTranslationButton.setVisible(hasOriginal && hasTranslated);
        toggleTranslationButton.setTitle(isTranslatedView ? "Show Original" : "Show Translation");

        String htmlToLoad = isTranslatedView ? translatedHtml : originalHtml;

        // THIS IS THE FIX: Get the current title and pass it to the two-argument version of the helper.
        String titleToDisplay = (getSupportActionBar() != null && getSupportActionBar().getTitle() != null)
                ? getSupportActionBar().getTitle().toString()
                : "";

        Log.d(TAG, "LiveEntry - Current Mode: " + (isTranslatedView ? "Translated" : "Original"));
        Log.d(TAG, "LiveEntry - HTML to Load:\n" + htmlToLoad);

        if (htmlToLoad != null && !htmlToLoad.trim().isEmpty()) {
            // Call the TWO-argument version to prevent the title from being overwritten.
            loadHtmlIntoWebView(htmlToLoad, titleToDisplay);
        } else {
            Log.w(TAG, "Skipped loading empty html in updateToggleStateAndWebView()");
        }
    }

    private void observeAutoTranslation() {
        LiveData<Entry> observer = webViewViewModel.getEntryEntityById(currentId);
        Observer<Entry> checkAutoTranslated = new Observer<Entry>() {
            @Override
            public void onChanged(Entry entry) {
                if (entry != null && entry.getTranslated() != null) {

                    String originalHtmlFromDb = entryRepository.getOriginalHtmlById(currentId);
                    if (originalHtmlFromDb != null) {
                        webViewViewModel.updateOriginalHtml(originalHtmlFromDb, currentId);
                        Log.d(TAG, "Original HTML restored from DB.");
                    }

                    String translatedHtmlFromDb = entry.getHtml();
                    if (translatedHtmlFromDb != null) {
                        webViewViewModel.updateHtml(translatedHtmlFromDb, currentId);
                        Log.d(TAG, "Translated HTML synced from auto translation.");
                    }

                    toggleTranslationButton.setTitle(isTranslatedView ? "Show Original" : "Show Translation");

                    Log.d(TAG, "AutoTranslation - Final Original:\n" + webViewViewModel.getOriginalHtmlById(currentId));
                    Log.d(TAG, "AutoTranslation - Final Translated:\n" + webViewViewModel.getHtmlById(currentId));

                    webViewViewModel.triggerEntryRefresh(currentId);

                    observer.removeObserver(this);
                }
            }
        };
        observer.observeForever(checkAutoTranslated);
    }

    private void loadHtmlIntoWebView(String html, String title) {
        // THIS IS THE FIX: This method now accepts the correct title as an argument
        // and uses it when rebuilding the header, instead of fetching the wrong one.
        Document doc = Jsoup.parse(html);
        doc.head().append(webViewViewModel.getStyle());

        EntryInfo entryInfo = webViewViewModel.getEntryInfoById(currentId);
        if (entryInfo != null && !doc.html().contains("class=\"entry-header\"")) {
            doc.selectFirst("body").prepend(
                    webViewViewModel.getHtml(
                            title, // Use the correct title passed into this method
                            entryInfo.getFeedTitle(),
                            entryInfo.getEntryPublishedDate(),
                            entryInfo.getFeedImageUrl()
                    )
            );
        }

        webView.loadDataWithBaseURL("file///android_res/", doc.html(), "text/html", "UTF-8", null);

        webView.postDelayed(() -> {
            int scrollX = sharedPreferencesRepository.getScrollX(currentId);
            int scrollY = sharedPreferencesRepository.getScrollY(currentId);
            webView.scrollTo(scrollX, scrollY);
        }, 300);

        syncLoadingWithTts();
    }

    private boolean handleOtherToolbarItems(int itemId) {
        switch (itemId) {

            case R.id.translate:
                // --- THIS IS THE FIX ---
                // 1. First, check if a valid translation already exists in the database.
                String existingTranslatedHtml = entryRepository.getHtmlById(currentId);
                String originalHtml = entryRepository.getOriginalHtmlById(currentId);
                boolean hasBeenTranslated = existingTranslatedHtml != null && !existingTranslatedHtml.equals(originalHtml);

                if (hasBeenTranslated) {
                    // If it's already translated, just show a message and do nothing else.
                    makeSnackbar("Article has already been translated.");

                    // Ensure the UI is in the correct state just in case.
                    if (!isTranslatedView) {
                        isTranslatedView = true;
                        sharedPreferencesRepository.setIsTranslatedView(currentId, true);
                        toggleTranslationButton.setTitle("Show Original");
                        loadHtmlIntoWebView(existingTranslatedHtml);
                    }
                    return true; // Stop here, do not re-translate.
                }

                // 2. If we reach here, it means no translation exists. Proceed with the original logic to start a new one.
                if (targetLanguage == null || targetLanguage.isEmpty()) {
                    showTranslationLanguageDialog(this);
                    return true;
                }

                // Get the original, untranslated HTML to send to the new translator
                String originalHtmlToTranslate = webViewViewModel.getOriginalHtmlById(currentId);
                if (originalHtmlToTranslate == null || originalHtmlToTranslate.isEmpty()) {
                    originalHtmlToTranslate = webViewViewModel.getHtmlById(currentId);
                }

                if (originalHtmlToTranslate == null || originalHtmlToTranslate.isEmpty()) {
                    makeSnackbar("Article content is not available for translation.");
                    return true;
                }

                EntryInfo entryInfoForLanguage = webViewViewModel.getEntryInfoById(currentId);
                if (entryInfoForLanguage == null) {
                    makeSnackbar("Could not determine source language.");
                    return true;
                }
                String sourceLanguage = entryInfoForLanguage.getFeedLanguage();

                // Call the translate method in the ViewModel to start the process
                webViewViewModel.translateArticle(originalHtmlToTranslate, sourceLanguage, targetLanguage);
                return true;

            case R.id.zoomIn:
                adjustTextZoom(true);
                return true;

            case R.id.zoomOut:
                adjustTextZoom(false);
                return true;

            case R.id.bookmark:
                toggleBookmark();
                return true;

            case R.id.share:
                shareCurrentLink();
                return true;

            case R.id.openInBrowser:
                browserButton.setVisible(false);
                offlineButton.setVisible(true);
                sharedPreferencesRepository.setWebViewMode(currentId, true);
                webView.loadUrl(currentLink);
                hideFakeLoading();
                return true;

            case R.id.exitBrowser:
                sharedPreferencesRepository.setWebViewMode(currentId, false);
                EntryInfo entryInfo = webViewViewModel.getLastVisitedEntry();
                String rebuiltHtml = rebuildHtml(entryInfo);
                loadEntryContent();
                offlineButton.setVisible(false);
                browserButton.setVisible(true);
                hideFakeLoading();
                return true;

            case R.id.reload:
                ReloadDialog dialog = new ReloadDialog(this, feedId, R.string.reload_confirmation, R.string.reload_message);
                dialog.show(getSupportFragmentManager(), ReloadDialog.TAG);
                return true;

            case R.id.toggleBackgroundMusic:
                toggleBackgroundMusic();
                return true;

            case R.id.openTtsSetting:
                startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
                return true;

            case R.id.toggleTranslation:
                boolean currentMode = sharedPreferencesRepository.getIsTranslatedView(currentId);
                isTranslatedView = !currentMode;
                sharedPreferencesRepository.setIsTranslatedView(currentId, isTranslatedView);

                Entry entry = webViewViewModel.getEntryById(currentId);
                if (entry == null) {
                    makeSnackbar("Entry not found.");
                    return true;
                }

                entryInfo = webViewViewModel.getEntryInfoById(currentId);
                if (entryInfo == null) {
                    makeSnackbar("Feed language info not found.");
                    isTranslatedView = currentMode;
                    sharedPreferencesRepository.setIsTranslatedView(currentId, currentMode);
                    return true;
                }

                String translatedHtml = webViewViewModel.getHtmlById(currentId);
                String originalHtmlForToggle = webViewViewModel.getOriginalHtmlById(currentId);
                String htmlToLoad = isTranslatedView ? translatedHtml : originalHtmlForToggle;

                Log.d(TAG, "TOGGLE BUTTON PRESSED");
                Log.d(TAG, "Original HTML:\n" + originalHtmlForToggle);
                Log.d(TAG, "Translated HTML:\n" + translatedHtml);
                Log.d(TAG, "HTML loaded for toggle view:\n" + htmlToLoad);

                if (htmlToLoad != null && !htmlToLoad.trim().isEmpty()) {
                    toggleTranslationButton.setTitle(isTranslatedView ? "Show Original" : "Show Translation");
                    loadHtmlIntoWebView(htmlToLoad);

                    if (isTranslatedView) {
                        String translated = entry.getTranslated();
                        if (translated != null && !translated.trim().isEmpty()) {
                            Log.d(TAG, "ToggleTranslation: Broadcasting translatedTextReady again");
                            webViewViewModel.setTranslatedTextReady(currentId, translated);
                            String lang = getLanguageForCurrentView(currentId, isTranslatedView, "en");
                            ttsPlayer.extract(entry.getId(), entry.getFeedId(), translated, lang);
                        } else {
                            Log.w(TAG, "ToggleTranslation: translated content missing, skipping extract");
                        }
                    } else {
                        String original = entry.getContent();
                        String lang = getLanguageForCurrentView(currentId, isTranslatedView, "en");
                        if (original != null && !original.trim().isEmpty()) {
                            Log.d(TAG, "ToggleTranslation: Reading original content");
                            ttsPlayer.extract(entry.getId(), entry.getFeedId(), original, lang);
                        }
                    }
                } else {
                    makeSnackbar("No alternate version available.");
                    isTranslatedView = currentMode;
                    sharedPreferencesRepository.setIsTranslatedView(currentId, currentMode);
                }
                return true;

            default:
                return false;
        }
    }

    private String rebuildHtml(EntryInfo entryInfo) {
        String html = webViewViewModel.getHtmlById(entryInfo.getEntryId());

        Document doc = Jsoup.parse(html);
        doc.head().append(webViewViewModel.getStyle());

        Objects.requireNonNull(doc.selectFirst("body")).prepend(
                webViewViewModel.getHtml(
                        entryInfo.getEntryTitle(),
                        entryInfo.getFeedTitle(),
                        entryInfo.getEntryPublishedDate(),
                        entryInfo.getFeedImageUrl()
                )
        );

        return doc.html();
    }

    private void adjustTextZoom(boolean zoomIn) {
        int currentZoom = webView.getSettings().getTextZoom();
        int newZoom = zoomIn ? currentZoom + 10 : currentZoom - 10;
        webView.getSettings().setTextZoom(newZoom);
        sharedPreferencesRepository.setTextZoom(newZoom);
    }

    private void toggleBookmark() {
        if (bookmark == null || bookmark.equals("N")) {
            bookmarkButton.setIcon(R.drawable.ic_bookmark_filled);
            webViewViewModel.updateBookmark("Y", currentId);
            bookmark = "Y";
            makeSnackbar("Bookmark Complete");
        } else {
            bookmarkButton.setIcon(R.drawable.ic_bookmark_outline);
            webViewViewModel.updateBookmark("N", currentId);
            bookmark = "N";
            makeSnackbar("Bookmark Removed");
        }
    }

    private void shareCurrentLink() {
        Intent sendIntent = new Intent();
        sendIntent.setAction(Intent.ACTION_SEND);
        sendIntent.putExtra(Intent.EXTRA_TEXT, currentLink);
        sendIntent.setType("text/plain");

        Intent shareIntent = Intent.createChooser(sendIntent, null);
        startActivity(shareIntent);
    }

    private void toggleBackgroundMusic() {
        boolean backgroundMusic = sharedPreferencesRepository.getBackgroundMusic();
        sharedPreferencesRepository.setBackgroundMusic(!backgroundMusic);
        if (backgroundMusic) {
            ttsPlayer.stopMediaPlayer();
            backgroundMusicButton.setTitle(R.string.background_music_turn_on);
            makeSnackbar("Background music is turned off");
        } else {
            ttsPlayer.setupMediaPlayer(false);
            backgroundMusicButton.setTitle(R.string.background_music_turn_off);
            makeSnackbar("Background music is turned on");
        }
    }

    private void initializeToolbarListeners() {
        toolbar.setNavigationOnClickListener(view -> onBackPressed());

        toolbar.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.translate) {
                View translateView = toolbar.findViewById(itemId);
                if (translateView != null) {
                    translateView.setOnLongClickListener(v -> {
                        showTranslationLanguageDialog(translateView.getContext());
                        return true;
                    });
                }
            }

            if (itemId == R.id.switchPlayMode) {
                isReadingMode = false;
                functionButtonsReadingMode.setVisibility(View.INVISIBLE);
                switchPlayModeButton.setVisible(false);
                ttsExtractor.setCallback((WebViewListener) null);
                switchPlayMode();
                mMediaBrowserHelper.onStart();
                functionButtons.setVisibility(View.VISIBLE);
                functionButtons.setAlpha(1.0f);
                return true;

            } else if (itemId == R.id.switchReadMode) {
                isReadingMode = true;
                functionButtons.setVisibility(View.INVISIBLE);
                switchReadModeButton.setVisible(false);
                ttsPlayer.setWebViewCallback(null);
                mMediaBrowserHelper.getTransportControls().stop();
                mMediaBrowserHelper.onStop();
                webView.clearMatches();
                switchReadMode();
                return true;

            } else if (itemId == R.id.highlightText) {
                boolean isHighlight = sharedPreferencesRepository.getHighlightText();
                sharedPreferencesRepository.setHighlightText(!isHighlight);
                if (isHighlight) {
                    webView.clearMatches();
                    highlightTextButton.setTitle(R.string.highlight_text_turn_on);
                    Snackbar.make(findViewById(R.id.webView_view), "Highlight is turned off", Snackbar.LENGTH_SHORT).show();
                } else {
                    highlightTextButton.setTitle(R.string.highlight_text_turn_off);
                    Snackbar.make(findViewById(R.id.webView_view), "Highlight is turned on", Snackbar.LENGTH_SHORT).show();
                }
                return true;
            }

            return handleOtherToolbarItems(itemId);
        });
    }

    private void initializeWebViewSettings() {
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        // Enable pinch-to-zoom
        webView.getSettings().setSupportZoom(true);
        webView.getSettings().setBuiltInZoomControls(true);

        int textZoom = sharedPreferencesRepository.getTextZoom();
        if (textZoom != 0) {
            webView.getSettings().setTextZoom(textZoom);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            boolean isNight = sharedPreferencesRepository.getNight();
            webView.getSettings().setForceDark(isNight
                    ? WebSettings.FORCE_DARK_ON
                    : WebSettings.FORCE_DARK_OFF);
        }

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);

                int ttsProgress = ttsPlayer.getCurrentExtractProgress();

                int combinedProgress = Math.min(newProgress, ttsProgress);

                loading.setVisibility(View.VISIBLE);
                loading.setProgress(combinedProgress);
                if (combinedProgress >= 95 && (!ttsPlayer.isPreparing() || ttsPlayer.ttsIsNull())) {
                    loading.setVisibility(View.GONE);
                }
            }
            @Override
            public void onReceivedTitle(WebView view, String title) {
                // THIS IS THE FIX: We override this method to do nothing.
                // This stops the WebView from ever changing our Activity's title,
                // solving the "flicker" bug permanently.
                Log.d(TAG, "WebChromeClient: onReceivedTitle called with '" + title + "'. IGNORING IT.");
                // Intentionally do not call super.onReceivedTitle(view, title);
            }
        });
    }

    @Override
    public void showFakeLoading() {
        runOnUiThread(() -> {
            Log.d(TAG, "TTS is preparing, showing fake loading indicator.");
            loading.setProgress(0);
            loading.setVisibility(View.VISIBLE);
        });
    }

    @Override
    public void hideFakeLoading() {
        runOnUiThread(() -> {
            Log.d(TAG, "TTS is ready, hiding fake loading indicator.");
            loading.setVisibility(View.GONE);
        });
    }

    @Override
    public void updateLoadingProgress(int progress) {
        runOnUiThread(() -> {
            if (loading.getVisibility() != View.VISIBLE) {
                loading.setVisibility(View.VISIBLE);
            }
            loading.setProgress(progress);

            if (progress >= 100 && !ttsPlayer.isPreparing()) {
                loading.setVisibility(View.GONE);
            }
        });
    }

    public void syncLoadingWithTts() {
        runOnUiThread(() -> {
            int ttsProgress = ttsPlayer.getCurrentExtractProgress();
            int webProgress = webView.getProgress();
            int combinedProgress = Math.min(ttsProgress, webProgress);

            if (combinedProgress >= 100 && !ttsPlayer.isPreparing()) {
                loading.setProgress(100);
                loading.setVisibility(View.GONE);
                Log.d(TAG, "[syncLoadingWithTts] Forcibly hid loading.");
            } else {
                loading.setProgress(combinedProgress);
                loading.setVisibility(View.VISIBLE);
                Log.d(TAG, "[syncLoadingWithTts] Still loading... progress = " + combinedProgress);
            }
        });
    }

    private void switchReadMode() {
        functionButtonsReadingMode.setVisibility(View.VISIBLE);

        webView.setWebViewClient(new ReadingWebClient());

        binding.nextArticleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (ttsPlaylist.skipNext()) {
                    setupReadingWebView();
                } else {
                    Snackbar.make(findViewById(R.id.webView_view), "This is the last article", Snackbar.LENGTH_SHORT).show();
                }
            }
        });

        binding.previousArticleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (ttsPlaylist.skipPrevious()) {
                    setupReadingWebView();
                } else {
                    Snackbar.make(findViewById(R.id.webView_view), "This is the first article", Snackbar.LENGTH_SHORT).show();
                }
            }
        });

        setupReadingWebView();

        ttsPlayer.setupMediaPlayer(false);

        switchPlayModeButton.setVisible(true);
    }

    private void switchPlayMode() {
        webView.setWebViewClient(new WebClient());
        setupMediaPlaybackButtons();

        mMediaBrowserHelper = new MediaBrowserConnection(this);
        mMediaBrowserHelper.registerCallback(new MediaBrowserListener());

        switchReadModeButton.setVisible(true);
    }

    private void setupMediaPlaybackButtons() {
        playPauseButton.setOnClickListener(view -> {
            if (isPlaying) {
                mMediaBrowserHelper.getTransportControls().pause();
                Log.d(TAG, "switchPlayMode: pausing " + ttsPlaylist.getPlayingId());
            } else {
                mMediaBrowserHelper.getTransportControls().play();
                Log.d(TAG, "switchPlayMode: playing " + ttsPlaylist.getPlayingId());
            }
        });

        skipNextButton.setOnClickListener(view -> mMediaBrowserHelper.getTransportControls().skipToNext());
        skipPreviousButton.setOnClickListener(view -> mMediaBrowserHelper.getTransportControls().skipToPrevious());
        fastForwardButton.setOnClickListener(view -> mMediaBrowserHelper.getTransportControls().fastForward());
        rewindButton.setOnClickListener(view -> mMediaBrowserHelper.getTransportControls().rewind());
    }

    private void setupReadingWebView() {
        loading.setVisibility(View.VISIBLE);
        loading.setProgress(0);
        bookmarkButton.setVisible(false);
        loading.setProgress(0);
        translationButton.setVisible(false);
        showOfflineButton = false;

        MediaMetadataCompat metadata = ttsPlaylist.getCurrentMetadata();

        content = metadata.getString("content");
        bookmark = metadata.getString("bookmark");
        currentLink = metadata.getString("link");
        currentId = Long.parseLong(metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID));
        updateToggleTranslationVisibility();
        feedId = metadata.getLong("feedId");

        if (bookmark == null || bookmark.equals("N")) {
            bookmarkButton.setIcon(R.drawable.ic_bookmark_outline);
        } else {
            bookmarkButton.setIcon(R.drawable.ic_bookmark_filled);
        }

        boolean isWebViewMode = sharedPreferencesRepository.getWebViewMode(currentId);

        if (isWebViewMode) {
            webView.loadUrl(currentLink);
            Log.d(TAG, "Restoring web view mode: " + currentLink);
            browserButton.setVisible(false);
            offlineButton.setVisible(true);
            showOfflineButton = false;
        } else {
            String entryTitle = metadata.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE);
            String feedTitle = metadata.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE);
            long date = metadata.getLong("date");
            Date publishDate = new Date(date);
            String feedImageUrl = metadata.getString("feedImageUrl");

            isTranslatedView = sharedPreferencesRepository.getIsTranslatedView(currentId);
            String htmlToLoad = isTranslatedView
                    ? webViewViewModel.getHtmlById(currentId)
                    : webViewViewModel.getOriginalHtmlById(currentId);

            if (htmlToLoad == null) {
                htmlToLoad = metadata.getString("html");
            }

            if (htmlToLoad != null) {
                loadHtmlIntoWebView(htmlToLoad);
            }

            offlineButton.setVisible(false);
            reloadButton.setVisible(true);
            bookmarkButton.setVisible(true);
            translationButton.setVisible(true);
            browserButton.setVisible(true);
            highlightTextButton.setVisible(true);
        }
    }

    @Override
    public void highlightText(String searchText) {
        if (!isReadingMode && sharedPreferencesRepository.getHighlightText()) {
            String text = searchText.trim();
            if (webViewViewModel.endsWithBreak(text)) {
                text = text.substring(0, text.length() - 1);
            }
            Log.d(TAG, "Highlighted text: " + text);
            String finalText = text.trim();
            ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> webView.findAllAsync(finalText));
        }
    }

    @Override
    public void finishedSetup() {
        ContextCompat.getMainExecutor(getApplicationContext()).execute(new Runnable() {
            @Override
            public void run() {
                if (!isReadingMode) {
                    loading.setVisibility(View.INVISIBLE);
                    functionButtons.setVisibility(View.VISIBLE);
                    functionButtons.setAlpha(1.0f);
                }
                reloadButton.setVisible(true);
                bookmarkButton.setVisible(true);
                translationButton.setVisible(true);
                highlightTextButton.setVisible(true);
                if (showOfflineButton) {
                    offlineButton.setVisible(true);
                }
            }
        });
    }

    private String getLanguageForCurrentView(long entryId, boolean isTranslated, String defaultLang) {
        if (isTranslated) {
            return sharedPreferencesRepository.getDefaultTranslationLanguage();
        }

        EntryInfo info = webViewViewModel.getEntryInfoById(entryId);
        String lang = (info != null && info.getFeedLanguage() != null && !info.getFeedLanguage().trim().isEmpty())
                ? info.getFeedLanguage()
                : defaultLang;

        Log.d(TAG, "getLanguageForCurrentView: Using lang=" + lang + " for isTranslated=" + isTranslated);
        return lang;
    }

    @Override
    public void makeSnackbar(String message) {
        Snackbar.make(findViewById(R.id.webView_view), message, Snackbar.LENGTH_SHORT).show();
    }

    @Override
    public void reload() {
        if (currentId <= 0) {
            Log.w(TAG, "reload() aborted: invalid currentId");
            return;
        }

        Log.d(TAG, "Reload triggered for entryId: " + currentId);

        webViewViewModel.resetEntry(currentId);
        webViewViewModel.clearLiveEntryCache(currentId);
        if (getIntent().getBooleanExtra("forceOriginal", false)) {
            sharedPreferencesRepository.setIsTranslatedView(currentId, false);
        }

        if (!isReadingMode) {
            mMediaBrowserHelper.getTransportControls().stop();
        }

        finish();
        overridePendingTransition(0, 0);
        startActivity(getIntent());
        overridePendingTransition(0, 0);
    }

    @Override
    public void askForReload(long feedId) {
        ReloadDialog dialog = new ReloadDialog(this, feedId, R.string.reload_confirmation, R.string.reload_suggestion_message);
        dialog.show(getSupportFragmentManager(), ReloadDialog.TAG);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (autoTranslationObserver != null && checkAutoTranslated != null) {
            autoTranslationObserver.removeObserver(checkAutoTranslated);
        }

        if (isReadingMode) {
            switchPlayModeButton.setVisible(false);
            functionButtonsReadingMode.setVisibility(View.INVISIBLE);
        } else {
            functionButtons.setVisibility(View.INVISIBLE);
            switchReadModeButton.setVisible(false);
        }
        reloadButton.setVisible(false);
        bookmarkButton.setVisible(false);
        translationButton.setVisible(false);
        highlightTextButton.setVisible(false);
        compositeDisposable.dispose();
        textUtil.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (!isReadingMode) {
            mMediaBrowserHelper.onStart();

            MediaControllerCompat mediaController = mMediaBrowserHelper.getMediaController();
            if (mediaController != null) {
                MediaControllerCompat.setMediaController(this, mediaController);
            }
        }
    }

    @Override
    public void onStop() {
        if (isReadingMode) {
            ttsExtractor.setCallback((WebViewListener) null);
        } else {
            ttsPlayer.setWebViewCallback(null);
            mMediaBrowserHelper.onStop();
        }
        super.onStop();
    }

    @Override
    protected void onPause() {
        ttsPlayer.setWebViewConnected(false);
        ttsPlayer.setUiControlPlayback(false);

        if (webView != null && currentId != 0) {
            sharedPreferencesRepository.setScrollX(currentId, webView.getScrollX());
            sharedPreferencesRepository.setScrollY(currentId, webView.getScrollY());
            sharedPreferencesRepository.setIsTranslatedView(currentId, isTranslatedView);
        }

        MediaControllerCompat mediaController = mMediaBrowserHelper.getMediaController();
        if (mediaController != null) {
            mediaController.unregisterCallback(mediaControllerCallback);
            Log.d(TAG, "MediaController callback unregistered");
        }

        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ttsPlayer.setWebViewConnected(true);

        updatePlayPauseButtonIcon(ttsPlayer.isSpeaking() && !ttsPlayer.isPausedManually());

        Log.d(TAG, "onResume: isSpeaking=" + ttsPlayer.isSpeaking() + ", isPausedManually=" + ttsPlayer.isPausedManually());

        if (!isReadingMode) {
            mMediaBrowserHelper.onStart();
            MediaControllerCompat mediaController = mMediaBrowserHelper.getMediaController();
            if (mediaController != null) {
                mediaController.registerCallback(mediaControllerCallback);
            }
        }

    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Log.d(TAG, "onConfigurationChanged: orientation changed, activity not recreated.");
    }

    private class WebClient extends WebViewClient {

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            Log.d(TAG, "WebClient: onPageStarted - loadingWebView visible.");
            webViewViewModel.setLoadingState(true);
            if (clearHistory) {
                clearHistory = false;
                webView.clearHistory();
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);

            // Get the up-to-date title from the intent, which was sent by the previous screen.
            String titleFromIntent = getIntent().getStringExtra("entry_title");

            // Use a fallback to the original title if the intent extra is missing for any reason.
            String titleToDisplay = titleFromIntent;
            if (titleToDisplay == null || titleToDisplay.isEmpty()) {
                EntryInfo entryInfo = webViewViewModel.getEntryInfoById(currentId);
                if (entryInfo != null) {
                    titleToDisplay = entryInfo.getEntryTitle();
                }
            }

            // Set the title on the toolbar. This happens after the page is fully loaded,
            // preventing it from being overwritten.
            if (getSupportActionBar() != null && titleToDisplay != null) {
                Log.d(TAG, "onPageFinished (WebClient): Setting final toolbar title to: '" + titleToDisplay + "'");
                getSupportActionBar().setTitle(titleToDisplay);
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            view.loadUrl(url);
            return true;
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            super.onPageCommitVisible(view, url);
            Log.d(TAG, "WebClient: onPageCommitVisible - loadingWebView hidden.");
            webViewViewModel.setLoadingState(false);
            if (content != null) {
                if (currentId != ttsPlaylist.getPlayingId()) {
                    ttsPlaylist.updatePlayingId(currentId);
                    mMediaBrowserHelper.getTransportControls().sendCustomAction("autoPlay", null);
                }
                functionButtons.setVisibility(View.VISIBLE);
                functionButtons.setAlpha(1.0f);
                reloadButton.setVisible(true);
                bookmarkButton.setVisible(true);
                highlightTextButton.setVisible(true);
            } else {
                if (currentId != ttsPlaylist.getPlayingId()) {
                    ttsPlaylist.updatePlayingId(currentId);
                }
            }
        }
    }

    private class ReadingWebClient extends WebViewClient {

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            Log.d(TAG, "ReadingWebClient: onPageStarted - loadingWebView visible.");
            webViewViewModel.setLoadingState(true);
            ttsExtractor.setCallback(WebViewActivity.this);
            if (clearHistory) {
                clearHistory = false;
                webView.clearHistory();
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);

            // Get the up-to-date title from the intent, which was sent by the previous screen.
            String titleFromIntent = getIntent().getStringExtra("entry_title");

            // Use a fallback to the original title if the intent extra is missing for any reason.
            String titleToDisplay = titleFromIntent;
            if (titleToDisplay == null || titleToDisplay.isEmpty()) {
                EntryInfo entryInfo = webViewViewModel.getEntryInfoById(currentId);
                if (entryInfo != null) {
                    titleToDisplay = entryInfo.getEntryTitle();
                }
            }

            // Set the title on the toolbar. This happens after the page is fully loaded,
            // preventing it from being overwritten.
            if (getSupportActionBar() != null && titleToDisplay != null) {
                Log.d(TAG, "onPageFinished (ReadingWebClient): Setting final toolbar title to: '" + titleToDisplay + "'");
                getSupportActionBar().setTitle(titleToDisplay);
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            view.loadUrl(url);
            return true;
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            super.onPageCommitVisible(view, url);
            loading.setVisibility(View.INVISIBLE);
            Log.d(TAG, "ReadingWebClient: onPageCommitVisible - loadingWebView hidden.");
            webViewViewModel.setLoadingState(false);
        }
    }

    private class MediaBrowserConnection extends MediaBrowserHelper {
        private MediaBrowserConnection(Context context) {
            super(context, TtsService.class);
        }

        @Override
        protected void onChildrenLoaded(@NonNull String parentId, @NonNull List<MediaBrowserCompat.MediaItem> children) {
            super.onChildrenLoaded(parentId, children);

            final MediaControllerCompat mediaController = getMediaController();
            if (mediaController != null) {
                ttsPlayer.setWebViewCallback(WebViewActivity.this);
                ttsPlayer.setWebViewConnected(true);
                mediaController.getTransportControls().prepare();
            }
        }
    }

    private class MediaBrowserListener extends MediaControllerCompat.Callback {
        @Override
        public void onPlaybackStateChanged(PlaybackStateCompat state) {
            super.onPlaybackStateChanged(state);
            isPlaying = state != null && state.getState() == PlaybackStateCompat.STATE_PLAYING;
            updatePlayPauseButtonIcon(isPlaying);
        }

        @Override
        public void onMetadataChanged(MediaMetadataCompat metadata) {
            if (metadata == null) {
                return;
            }

            String titleFromIntent = getIntent().getStringExtra("entry_title");
            if (titleFromIntent != null && !titleFromIntent.isEmpty() && getSupportActionBar() != null) {
                Log.d(TAG, "onMetadataChanged: Fighting back against flicker. Resetting title to: " + titleFromIntent);
                getSupportActionBar().setTitle(titleFromIntent);
            }

            clearHistory = true;
            runOnUiThread(() -> {
                loading.setVisibility(View.VISIBLE);
                loading.setProgress(10);
            });
            functionButtons.setVisibility(View.VISIBLE);
            functionButtons.setAlpha(0.5f);
            reloadButton.setVisible(false);
            bookmarkButton.setVisible(false);
            highlightTextButton.setVisible(false);
            showOfflineButton = false;

            content = metadata.getString("content");
            bookmark = metadata.getString("bookmark");
            currentLink = metadata.getString("link");
            currentId = Long.parseLong(metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID));
            updateToggleTranslationVisibility();
            feedId = metadata.getLong("feedId");

            if (bookmark == null || bookmark.equals("N")) {
                bookmarkButton.setIcon(R.drawable.ic_bookmark_outline);
            } else {
                bookmarkButton.setIcon(R.drawable.ic_bookmark_filled);
            }

            isTranslatedView = sharedPreferencesRepository.getIsTranslatedView(currentId);
            String htmlToLoad = isTranslatedView
                    ? webViewViewModel.getHtmlById(currentId)
                    : webViewViewModel.getOriginalHtmlById(currentId);

            if (htmlToLoad == null) {
                htmlToLoad = metadata.getString("html");
            }

            boolean isWebViewMode = sharedPreferencesRepository.getWebViewMode(currentId);

            if (isWebViewMode) {
                webView.loadUrl(currentLink);
                Log.d(TAG, "Restoring web view mode: " + currentLink);
                browserButton.setVisible(false);
                offlineButton.setVisible(true);
                showOfflineButton = false;
            } else if (htmlToLoad != null) {
                loadHtmlIntoWebView(htmlToLoad);
                browserButton.setVisible(true);
                offlineButton.setVisible(false);
                showOfflineButton = false;
            } else {
                webView.loadUrl(currentLink);
                Log.d(TAG, "Fallback: loading live URL - " + currentLink);
                browserButton.setVisible(false);
                showOfflineButton = true;
            }


            if (ttsPlayer.isWebViewConnected()) {
                ttsPlayer.setUiControlPlayback(true);
            }

        }

        @Override
        public void onSessionDestroyed() {
            super.onSessionDestroyed();
        }
    }

    private void updatePlayPauseButtonIcon(boolean playing) {
        int iconRes = playing ? R.drawable.ic_pause : R.drawable.ic_play;
        playPauseButton.setIcon(ContextCompat.getDrawable(this, iconRes));
    }
}