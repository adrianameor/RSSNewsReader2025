package com.adriana.newscompanion.ui.webview;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;
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

import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;

import com.adriana.newscompanion.R;
import com.adriana.newscompanion.data.playlist.Playlist;
import com.adriana.newscompanion.data.playlist.PlaylistRepository;
import com.adriana.newscompanion.data.sharedpreferences.SharedPreferencesRepository;
import com.adriana.newscompanion.model.EntryInfo;
import com.adriana.newscompanion.service.tts.TtsExtractor;
import com.adriana.newscompanion.service.tts.TtsPlayer;
import com.adriana.newscompanion.service.tts.TtsPlaylist;
import com.adriana.newscompanion.service.tts.TtsService;
import com.adriana.newscompanion.databinding.ActivityWebviewBinding;
import com.adriana.newscompanion.service.util.TextUtil;
import com.adriana.newscompanion.ui.feed.ReloadDialog;
import com.adriana.newscompanion.data.entry.Entry;
import com.adriana.newscompanion.data.entry.EntryRepository;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;



import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import com.adriana.newscompanion.data.repository.TranslationRepository;

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
    private Snackbar translationSnackbar;

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
    @Inject  // ADD THIS LINE
    PlaylistRepository playlistRepository;  // ADD THIS FIELD

    private boolean isLoadingFromNewIntent = false; // Flag to prevent MediaBrowser interference
    
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        
        long newEntryId = intent.getLongExtra("entry_id", -1);
        String newEntryTitle = intent.getStringExtra("entry_title");
        boolean isFromSkipAction = intent.getBooleanExtra("from_skip", false);
        
        Log.e("BLACKBOX_DEBUG", "========================================");
        Log.e("BLACKBOX_DEBUG", "onNewIntent CALLED");
        Log.e("BLACKBOX_DEBUG", "New entry_id: " + newEntryId);
        Log.e("BLACKBOX_DEBUG", "New entry_title: " + newEntryTitle);
        Log.e("BLACKBOX_DEBUG", "isFromSkipAction: " + isFromSkipAction);
        Log.e("BLACKBOX_DEBUG", "Old currentId: " + currentId);
        Log.e("BLACKBOX_DEBUG", "isReadingMode: " + isReadingMode);
        Log.e("BLACKBOX_DEBUG", "========================================");
        
        // Set flag to prevent MediaBrowser from interfering
        isLoadingFromNewIntent = true;
        
        // Update the activity's intent to the new one
        setIntent(intent);
        
        // If this is from a skip action, DON'T stop the MediaBrowser or TTS
        // The TtsService is already handling the playback
        if (!isFromSkipAction) {
            // Stop any ongoing TTS playback from the previous article
            if (ttsPlayer != null && ttsPlayer.isSpeaking()) {
                ttsPlayer.stopTtsPlayback();
                Log.e("BLACKBOX_DEBUG", "Stopped TTS playback");
            }
            
            // Stop media browser if in play mode
            if (!isReadingMode && mMediaBrowserHelper != null) {
                Log.e("BLACKBOX_DEBUG", "Stopping MediaBrowser");
                mMediaBrowserHelper.getTransportControls().stop();
                mMediaBrowserHelper.onStop();
            }
        } else {
            Log.e("BLACKBOX_DEBUG", "Skip action detected - keeping MediaBrowser and TTS running");
        }
        
        // Clear the WebView to prepare for new content
        if (webView != null) {
            webView.clearHistory();
            webView.loadUrl("about:blank");
            Log.e("BLACKBOX_DEBUG", "Cleared WebView");
        }
        
        // Reset state variables
        isInitialLoad = true;
        clearHistory = true;
        
        Log.e("BLACKBOX_DEBUG", "About to call loadEntryContent()");
        // Reload the content with the new article data
        loadEntryContent();
        Log.e("BLACKBOX_DEBUG", "After loadEntryContent(), currentId is now: " + currentId);
        
        // Only reinitialize MediaBrowser if NOT from skip action
        if (!isReadingMode && !isFromSkipAction) {
            Log.e("BLACKBOX_DEBUG", "Recreating MediaBrowser connection");
            // Recreate the media browser connection with the new article
            mMediaBrowserHelper = new MediaBrowserConnection(this);
            mMediaBrowserHelper.registerCallback(new MediaBrowserListener());
            mMediaBrowserHelper.onStart();
        } else if (isFromSkipAction) {
            Log.e("BLACKBOX_DEBUG", "Skipping MediaBrowser recreation - using existing connection");
        }
        
        // Clear the flag after a short delay to allow content to load
        webView.postDelayed(() -> {
            isLoadingFromNewIntent = false;
            Log.e("BLACKBOX_DEBUG", "Cleared isLoadingFromNewIntent flag");
        }, 1000);
        
        Log.e("BLACKBOX_DEBUG", "onNewIntent COMPLETED");
    }

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

    private void initializeTranslationObservers() {
        // This observer now handles the new persistent Snackbar with a Cancel button.
        webViewViewModel.isTranslating().observe(this, isTranslating -> {
            if (isTranslating) {
                // --- THIS IS THE FIX ---
                // 1. Create a persistent snackbar with the "Translating..." message.
                translationSnackbar = Snackbar.make(findViewById(R.id.webView_view), "Translating... please wait", Snackbar.LENGTH_INDEFINITE);

                // 2. Add a "Cancel" action button that calls the ViewModel's cancel method.
                translationSnackbar.setAction("Cancel", v -> {
                    webViewViewModel.cancelTranslation();
                });

                // 3. Show the snackbar.
                translationSnackbar.show();
                // --- END OF FIX ---

                // Also show the visual loading bar
                loading.setVisibility(View.VISIBLE);
                loading.setIndeterminate(true);

            } else {
                // When translation is finished, cancelled, or has an error, dismiss the snackbar.
                if (translationSnackbar != null && translationSnackbar.isShown()) {
                    translationSnackbar.dismiss();
                }

                // Also hide the visual loading bar
                loading.setIndeterminate(false);
                loading.setVisibility(View.GONE);
            }
        });

        // This observer for the final result remains unchanged.
        webViewViewModel.getTranslationResult().observe(this, result -> {
            if (result == null) return;

            makeSnackbar("Translation successful!");
            isTranslatedView = true;
            sharedPreferencesRepository.setIsTranslatedView(currentId, true);
            toggleTranslationButton.setVisible(true);
            toggleTranslationButton.setTitle("Show Original");

            if (getSupportActionBar() != null) {
                getSupportActionBar().setTitle(result.finalTitle);
            }
            loadHtmlIntoWebView(result.finalHtml, result.finalTitle);

            // --- FIX FOR ISSUE 1: Ensure TTS uses correct language after translation ---
            String targetLang = sharedPreferencesRepository.getDefaultTranslationLanguage();
            String plainTextContent = textUtil.extractHtmlContent(result.finalHtml, "--####--");
            String fullContentForTts = result.finalTitle + "--####--" + plainTextContent;

            if (!fullContentForTts.trim().isEmpty()) {
                Log.d(TAG, "Manual translation finished. Updating TTS with language: " + targetLang);
                
                // 1. Stop any current TTS playback
                if (ttsPlayer.isSpeaking()) {
                    ttsPlayer.stopTtsPlayback();
                }
                
                // 2. Set the language in the extractor with lock
                ttsExtractor.setCurrentLanguage(targetLang, true);
                
                // 3. Extract the new content with the correct language
                boolean extractSuccess = ttsPlayer.extract(currentId, feedId, fullContentForTts, targetLang);
                
                // 4. Setup TTS engine with the new language
                if (extractSuccess) {
                    ttsPlayer.setupTts();
                    Log.d(TAG, "TTS reinitialized with translated content in language: " + targetLang);
                }
            }
            // --- END OF FIX ---
        });

        // This observer for error dialogs remains unchanged.
        webViewViewModel.getTranslationError().observe(this, error -> {
            if (error != null && !error.isEmpty()) {
                new AlertDialog.Builder(this)
                        .setTitle("Translation Error")
                        .setMessage(error)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        });

        // This observer for simple status messages remains unchanged.
        webViewViewModel.getSnackbarMessage().observe(this, message -> {
            if (message != null && !message.isEmpty()) {
                makeSnackbar(message);
                webViewViewModel.clearSnackbar();
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
        if (entryId == -1) { makeSnackbar("Error: No article ID provided."); finish(); return; }
        currentId = entryId;
        
        Log.e("BLACKBOX_DEBUG", "+++++++++++++++++++++++++++++++++++++++");
        Log.e("BLACKBOX_DEBUG", "loadEntryContent CALLED");
        Log.e("BLACKBOX_DEBUG", "Setting currentId to: " + currentId);
        Log.e("BLACKBOX_DEBUG", "+++++++++++++++++++++++++++++++++++++++");

        EntryInfo entryInfo = webViewViewModel.getEntryInfoById(currentId);
        if (entryInfo == null) { makeSnackbar("Error: Could not load article data."); finish(); return; }

        currentLink = entryInfo.getEntryLink();
        feedId = entryInfo.getFeedId();
        bookmark = entryInfo.getBookmark();
        
        // THIS IS THE FIX: Update the TtsPlaylist BEFORE the MediaBrowser connects
        // This ensures the MediaBrowser gets the correct article ID
        if (!isReadingMode) {
            Log.e("BLACKBOX_DEBUG", "Updating TtsPlaylist with currentId: " + currentId);
            ttsPlaylist.updatePlayingId(currentId);
        }

        String originalHtml = entryRepository.getOriginalHtmlById(currentId);

        // This handles the RED CIRCLE case.
        if (originalHtml == null || originalHtml.trim().isEmpty()) {
            Log.d(TAG, "No offline content found for entry " + currentId + ". Loading URL directly.");
            if (currentLink != null && !currentLink.isEmpty()) {
                webView.loadUrl(currentLink);
            } else {
                makeSnackbar("Error: No URL found for this article.");
                finish();
                return;
            }
            if (getSupportActionBar() != null) getSupportActionBar().setTitle(entryInfo.getEntryTitle());
            browserButton.setVisible(false);
            offlineButton.setVisible(false);
            toggleTranslationButton.setVisible(false);
            reloadButton.setVisible(true);
            highlightTextButton.setVisible(false);
            return;
        }

        // --- THIS IS THE FIX ---
        // For the initial load, the instruction from the Intent (from the article list)
        // is ALWAYS the highest priority. We ignore any old saved state.
        boolean isStartingInTranslatedViewFromIntent = getIntent().getBooleanExtra("is_translated", false);
        isTranslatedView = isStartingInTranslatedViewFromIntent;

        // We can still save this new state, so it's remembered if the user toggles it later.
        sharedPreferencesRepository.setIsTranslatedView(currentId, isTranslatedView);
        // --- END OF FIX ---

        String htmlToLoad;
        String titleToDisplay;
        String contentForTts;
        String langForTts;
        boolean translationExists = entryInfo.getHtml() != null && !entryInfo.getHtml().equals(originalHtml);

        // --- THIS IS THE FIX ---
        // We go back to the simpler logic. We trust that the 'content' and 'translated'
        // fields from the database are already correctly formatted by the extractor.
        if (isTranslatedView && translationExists) {
            htmlToLoad = entryInfo.getHtml();
            titleToDisplay = entryInfo.getTranslatedTitle();
            contentForTts = entryInfo.getTranslated(); // This field should contain "Title--####--Body"
            langForTts = sharedPreferencesRepository.getDefaultTranslationLanguage();
        } else {
            htmlToLoad = originalHtml;
            titleToDisplay = entryInfo.getEntryTitle();
            contentForTts = entryInfo.getContent(); // This field should contain "Title--####--Body"
            langForTts = entryInfo.getFeedLanguage();
        }
        // --- END OF FIX ---

        loadHtmlIntoWebView(htmlToLoad, titleToDisplay);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(titleToDisplay);
        }
        toggleTranslationButton.setVisible(translationExists);
        toggleTranslationButton.setTitle(isTranslatedView ? "Show Original" : "Show Translation");

        if (bookmark == null || bookmark.equals("N")) {
            bookmarkButton.setIcon(R.drawable.ic_bookmark_outline);
        } else {
            bookmarkButton.setIcon(R.drawable.ic_bookmark_filled);
        }

        if (langForTts == null || langForTts.trim().isEmpty()) {
            langForTts = "en"; // Safe fallback
        }
        if (contentForTts != null && !contentForTts.trim().isEmpty()) {
            ttsPlayer.extract(currentId, feedId, contentForTts, langForTts);
        }

        sharedPreferencesRepository.setCurrentReadingEntryId(currentId);
        initializePlaylistSystem();
        syncLoadingWithTts();
        
        // THIS IS THE FIX: Show the function buttons after loading content
        // Previously this was done in onMetadataChanged(), but now we ignore stale metadata
        if (!isReadingMode) {
            Log.e("BLACKBOX_DEBUG", "Showing function buttons in loadEntryContent()");
            functionButtons.setVisibility(View.VISIBLE);
            functionButtons.setAlpha(1.0f);
        }
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
                // The Activity no longer does the translation. It just tells the ViewModel to start.
                // The ViewModel will handle the progress bar and results via LiveData,
                // which the Activity is already observing in initializeTranslationObservers().
                webViewViewModel.translateArticle(currentId);
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
                EntryInfo entryInfoForExit = webViewViewModel.getLastVisitedEntry();
                String rebuiltHtml = rebuildHtml(entryInfoForExit);
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

                String translatedHtml = webViewViewModel.getHtmlById(currentId);
                String originalHtmlForToggle = webViewViewModel.getOriginalHtmlById(currentId);
                String htmlToLoad = isTranslatedView ? translatedHtml : originalHtmlForToggle;

                if (htmlToLoad != null && !htmlToLoad.trim().isEmpty()) {
                    toggleTranslationButton.setTitle(isTranslatedView ? "Show Original" : "Show Translation");
                    loadHtmlIntoWebView(htmlToLoad);

                    // --- FIX FOR ISSUE 2: Stop TTS and restart with correct content and language ---
                    // Remember if TTS was playing before toggle
                    boolean wasPlaying = ttsPlayer.isSpeaking();
                    
                    // Stop current TTS playback
                    if (wasPlaying) {
                        ttsPlayer.stopTtsPlayback();
                        Log.d(TAG, "Stopped TTS before toggle");
                    }
                    
                    if (isTranslatedView) {
                        // When switching TO translated view...
                        String translated = entry.getTranslated();
                        if (translated != null && !translated.trim().isEmpty()) {
                            // 1. Get the target language
                            String lang = sharedPreferencesRepository.getDefaultTranslationLanguage();
                            Log.d(TAG, "Toggle to translated: Using language: " + lang);
                            
                            // 2. Set the TTS engine's language with lock
                            ttsExtractor.setCurrentLanguage(lang, true);
                            
                            // 3. Extract the translated text
                            boolean extractSuccess = ttsPlayer.extract(entry.getId(), entry.getFeedId(), translated, lang);
                            
                            // 4. Setup TTS with the new language
                            if (extractSuccess) {
                                ttsPlayer.setupTts();
                                
                                // 5. Resume playback if it was playing before
                                if (wasPlaying && !isReadingMode) {
                                    ttsPlayer.speak();
                                    Log.d(TAG, "Resumed TTS playback with translated content");
                                }
                            }
                        }
                    } else {
                        // When switching TO original view...
                        String original = entry.getContent();
                        if (original != null && !original.trim().isEmpty()) {
                            // Identify the original language asynchronously
                            Disposable langDetectionDisposable = textUtil.identifyLanguageRx(original)
                                    .subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io())
                                    .observeOn(io.reactivex.rxjava3.android.schedulers.AndroidSchedulers.mainThread())
                                    .subscribe(
                                            originalLang -> {
                                                Log.d(TAG, "Toggle to original: Language identified as: " + originalLang);
                                                
                                                // 1. Set the TTS engine's language with lock
                                                ttsExtractor.setCurrentLanguage(originalLang, true);
                                                
                                                // 2. Extract the original text
                                                boolean extractSuccess = ttsPlayer.extract(entry.getId(), entry.getFeedId(), original, originalLang);
                                                
                                                // 3. Setup TTS with the new language
                                                if (extractSuccess) {
                                                    ttsPlayer.setupTts();
                                                    
                                                    // 4. Resume playback if it was playing before
                                                    if (wasPlaying && !isReadingMode) {
                                                        ttsPlayer.speak();
                                                        Log.d(TAG, "Resumed TTS playback with original content");
                                                    }
                                                }
                                            },
                                            error -> {
                                                // Fallback on error
                                                Log.e(TAG, "Could not re-identify original language", error);
                                                makeSnackbar("Could not identify language.");
                                            }
                                    );
                            compositeDisposable.add(langDetectionDisposable);
                        }
                    }
                    // --- END OF FIX ---

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

        // Load and set the playlist for navigation in reading mode
        String playlistIdString = playlistRepository.getLatestPlaylist();
        List<Long> playlistIds = new ArrayList<>();
        if (playlistIdString != null && !playlistIdString.isEmpty()) {
            String[] ids = playlistIdString.split(",");
            for (String idStr : ids) {
                if (!idStr.isEmpty()) {
                    try {
                        playlistIds.add(Long.parseLong(idStr));
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "Failed to parse ID from playlist string: " + idStr, e);
                    }
                }
            }
        }
        ttsPlaylist.setPlaylist(playlistIds, currentId);

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
            // 1. Log the raw text received from the TTS player
            Log.d("HIGHLIGHT_DEBUG", "Raw searchText from TTS: \'" + searchText + "\'");

            // 2. Normalize whitespace (multiple spaces/newlines to single space) as you suggested
            text = text.replaceAll("\\s+", " ");

            if (webViewViewModel.endsWithBreak(text)) {
                text = text.substring(0, text.length() - 1);
            }

            // 4. Log the final, cleaned text that we will ask the WebView to find
            Log.d("HIGHLIGHT_DEBUG", "Normalized text to search: \'" + text + "\'");
            final String finalText = text.trim();

            if (!finalText.isEmpty()) {
                ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> {
                    Log.d("HIGHLIGHT_DEBUG", "Executing WebView.findAllAsync for: \'" + finalText + "\'");
                    webView.findAllAsync(finalText);
                });
            }
            // --- END OF YOUR DEBUGGING CODE ---
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
        // --- THIS IS PART OF THE FIX ---
        // Add a flag to track if preparation has already been requested for this connection.
        private boolean isPreparationRequested = false;
        // ---

        private MediaBrowserConnection(Context context) {
            super(context, TtsService.class);
        }

        @Override
        protected void onChildrenLoaded(@NonNull String parentId, @NonNull List<MediaBrowserCompat.MediaItem> children) {
            super.onChildrenLoaded(parentId, children);

            final MediaControllerCompat mediaController = getMediaController();
            if (mediaController != null) {

                // --- THIS IS THE FIX ---
                // Add a guard to break the infinite loop. This block will now only run ONCE.
                if (isPreparationRequested) {
                    Log.e("LIFECYCLE_DEBUG", "onChildrenLoaded: Preparation already requested, ignoring subsequent call to prevent loop.");
                    return; // Do nothing on the second (and all subsequent) calls.
                }
                isPreparationRequested = true; // Set the flag so this only runs once.
                // --- END OF FIX ---

                // This logic is now safe because it will only execute one time.
                String entryIdString = String.valueOf(getIntent().getLongExtra("entry_id", -1));
                if (!"-1".equals(entryIdString)) {
                    Log.e("LIFECYCLE_DEBUG", "--- Client is connected. Sending onPrepareFromMediaId command for ID: " + entryIdString + " ---");
                    mediaController.getTransportControls().prepareFromMediaId(entryIdString, null);
                } else {
                    Log.e("LIFECYCLE_DEBUG", "Client connected but entryId is invalid.");
                }

                // We can still set these callbacks here.
                ttsPlayer.setWebViewCallback(WebViewActivity.this);
                ttsPlayer.setWebViewConnected(true);
            } else {
                Log.e("LIFECYCLE_DEBUG", "onChildrenLoaded: MediaController is NULL!");
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
                Log.e("BLACKBOX_DEBUG", "onMetadataChanged: metadata is NULL");
                return;
            }
            
            long metadataEntryId = Long.parseLong(metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID));
            long intentEntryId = getIntent().getLongExtra("entry_id", -1);
            
            Log.e("BLACKBOX_DEBUG", "----------------------------------------");
            Log.e("BLACKBOX_DEBUG", "onMetadataChanged CALLED");
            Log.e("BLACKBOX_DEBUG", "isLoadingFromNewIntent: " + isLoadingFromNewIntent);
            Log.e("BLACKBOX_DEBUG", "Metadata entry_id: " + metadataEntryId);
            Log.e("BLACKBOX_DEBUG", "Current currentId: " + currentId);
            Log.e("BLACKBOX_DEBUG", "Intent entry_id: " + intentEntryId);
            
            // THIS IS THE CRITICAL FIX: If the metadata doesn't match the Intent, IGNORE IT
            // This happens when the TtsService hasn't updated yet with the new article
            if (metadataEntryId != intentEntryId) {
                Log.e("BLACKBOX_DEBUG", "IGNORING onMetadataChanged - metadata ID (" + metadataEntryId + ") doesn't match intent ID (" + intentEntryId + ")");
                Log.e("BLACKBOX_DEBUG", "This is stale metadata from the previous article");
                Log.e("BLACKBOX_DEBUG", "----------------------------------------");
                return;
            }
            
            // Also ignore if we're loading from a new intent
            if (isLoadingFromNewIntent) {
                Log.e("BLACKBOX_DEBUG", "IGNORING onMetadataChanged because isLoadingFromNewIntent=true");
                Log.e("BLACKBOX_DEBUG", "----------------------------------------");
                return;
            }
            
            Log.e("BLACKBOX_DEBUG", "PROCESSING onMetadataChanged - IDs match, this is valid metadata");

            String titleFromIntent = getIntent().getStringExtra("entry_title");
            if (titleFromIntent != null && !titleFromIntent.isEmpty() && getSupportActionBar() != null) {
                Log.e("BLACKBOX_DEBUG", "Setting title from intent: " + titleFromIntent);
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
            // DON'T change currentId here - it's already set correctly from the Intent
            Log.e("BLACKBOX_DEBUG", "Keeping currentId as: " + currentId);
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
                Log.e("BLACKBOX_DEBUG", "Loading URL: " + currentLink);
                browserButton.setVisible(false);
                offlineButton.setVisible(true);
                showOfflineButton = false;
            } else if (htmlToLoad != null) {
                Log.e("BLACKBOX_DEBUG", "Loading HTML into WebView");
                loadHtmlIntoWebView(htmlToLoad);
                browserButton.setVisible(true);
                offlineButton.setVisible(false);
                showOfflineButton = false;
            } else {
                webView.loadUrl(currentLink);
                Log.e("BLACKBOX_DEBUG", "Fallback: loading URL: " + currentLink);
                browserButton.setVisible(false);
                showOfflineButton = true;
            }


            if (ttsPlayer.isWebViewConnected()) {
                ttsPlayer.setUiControlPlayback(true);
            }
            
            Log.e("BLACKBOX_DEBUG", "onMetadataChanged COMPLETED");
            Log.e("BLACKBOX_DEBUG", "----------------------------------------");

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







    // Add to WebViewActivity.onCreate() or loadEntryContent():
    private void initializePlaylistSystem() {
        long currentId = getIntent().getLongExtra("entry_id", -1);
        if (currentId != -1) {
            EntryInfo entryInfo = webViewViewModel.getEntryInfoById(currentId);
            if (entryInfo != null) {
                createOrUpdatePlaylist(currentId, entryInfo.getFeedId());
            }
        }
    }

    private void createOrUpdatePlaylist(long currentEntryId, long feedId) {
        // Check if we need a new playlist
        Date latestPlaylistDate = playlistRepository.getLatestPlaylistCreatedDate();
        boolean needsNewPlaylist = latestPlaylistDate == null ||
                System.currentTimeMillis() - latestPlaylistDate.getTime() > 3600000; // 1 hour

        if (needsNewPlaylist) {
            // Create intelligent playlist
            List<Long> playlistIds = new ArrayList<>();

            // 1. Current feed articles (30%)
            List<Long> feedArticles = entryRepository.getArticleIdsByFeedId(feedId, 15);
            playlistIds.addAll(feedArticles);

            // 2. Recently read articles (30%)
            List<Long> recentRead = entryRepository.getRecentlyReadIds(15);
            playlistIds.addAll(recentRead);

            // 3. Bookmarked articles (20%)
            List<Long> bookmarked = entryRepository.getBookmarkedIds(10);
            playlistIds.addAll(bookmarked);

            // 4. Random articles (20%)
            List<Long> random = entryRepository.getRandomArticleIds(10);
            playlistIds.addAll(random);

            // Ensure current article is included
            if (!playlistIds.contains(currentEntryId)) {
                playlistIds.add(0, currentEntryId); // Add at beginning
            }

            // Remove duplicates, limit size
            Set<Long> uniqueIds = new LinkedHashSet<>(playlistIds);
            List<Long> finalList = new ArrayList<>(uniqueIds);
            if (finalList.size() > 50) {
                finalList = finalList.subList(0, 50);
            }

            // Save
            String playlistString = TextUtils.join(",", finalList);
            Playlist playlist = new Playlist(new Date(), playlistString);
            playlist.setPlaylist(playlistString);
            playlist.setCreatedDate(new Date());
            playlistRepository.insert(playlist);
        }
    }
}