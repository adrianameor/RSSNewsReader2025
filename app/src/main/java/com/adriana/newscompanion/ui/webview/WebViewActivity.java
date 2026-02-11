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
import com.adriana.newscompanion.model.PlaybackStateModel;
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
    private MenuItem summarizeButton;
    private String currentLink;
    private long currentId;
    private int currentPlaylistIndex = -1;
    private long feedId;
    private String html;
    private String content;
    private String bookmark;
    private boolean isPlaying;
    private boolean isReadingMode;
    private boolean showOfflineButton;
    private boolean clearHistory;
    private boolean isInitialLoad = true;
    private boolean isSummaryView = false;
    private String originalHtmlForSummary; // To store content when showing a summary

    private MenuItem toggleTranslationButton;
    private boolean isTranslatedView = true;
    private MaterialToolbar toolbar;

    private String targetLanguage;
    private String translationMethod;
    private TextUtil textUtil;
    private CompositeDisposable compositeDisposable;
    private LiveData<Entry> liveEntryObserver;

    private MenuItem switchPlayModeButton;
    private LinearLayout functionButtonsReadingMode;

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
    private Snackbar summarySnackbar;

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
    @Inject
    PlaylistRepository playlistRepository;

    private boolean isLoadingFromNewIntent = false;
    private long[] currentPlaylistCache;
    
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        
        long newEntryId = intent.getLongExtra("entry_id", -1);
        String newEntryTitle = intent.getStringExtra("entry_title");
        boolean isFromSkipAction = intent.getBooleanExtra("from_skip", false);

        isLoadingFromNewIntent = true;

        setIntent(intent);

        long[] newPlaylist = getPlaylistFromIntent(intent);

        if (!isFromSkipAction) {
            if (ttsPlayer != null && ttsPlayer.isSpeaking()) {
                ttsPlayer.stopTtsPlayback();
            }

            if (!isReadingMode && mMediaBrowserHelper != null) {
                mMediaBrowserHelper.getTransportControls().stop();
                mMediaBrowserHelper.onStop();
            }
        }

        if (webView != null) {
            webView.clearHistory();
            webView.loadUrl("about:blank");
        }

        isInitialLoad = true;
        clearHistory = true;

        if (!isReadingMode && newPlaylist != null) {
            List<Long> list = new ArrayList<>();
            for (long id : newPlaylist) list.add(id);
            ttsPlaylist.setPlaylist(list, intent.getLongExtra("entry_id", -1));
        }

        loadEntryContent();

        if (!isReadingMode && !isFromSkipAction) {
            mMediaBrowserHelper = new MediaBrowserConnection(this);
            mMediaBrowserHelper.registerCallback(new MediaBrowserListener());
            mMediaBrowserHelper.onStart();
        }

        webView.postDelayed(() -> {
            isLoadingFromNewIntent = false;
        }, 1000);
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
                    Log.e("UI_TRACE", "🎵 mediaControllerCallback.onPlaybackStateChanged fired: state=" + 
                        (state == null ? "null" : state.getState()));
                    super.onPlaybackStateChanged(state);
                    isPlaying = state.getState() == PlaybackStateCompat.STATE_PLAYING;
                    updatePlayPauseButtonIcon(isPlaying);
                    Log.d(TAG, "Playback state changed: " + state.getState());
                }
                
                @Override
                public void onSessionEvent(String event, Bundle extras) {
                    Log.e("UI_TRACE", "🔥 mediaControllerCallback.onSessionEvent RECEIVED");
                    Log.e("UI_TRACE", "   event = " + event);
                    Log.e("UI_TRACE", "   extras = " + (extras == null ? "null" : extras.keySet()));
                    super.onSessionEvent(event, extras);

                    if ("request_next_article".equals(event)) {
                        Log.e("UI_TRACE", "➡️ request_next_article in mediaControllerCallback");
                        handleNextArticle();
                    } else if ("request_previous_article".equals(event)) {
                        Log.e("UI_TRACE", "⬅️ request_previous_article in mediaControllerCallback");
                        handlePreviousArticle();
                    }
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
            handleOtherToolbarItems(R.id.translate);
            dialog.dismiss();
        });

        builder.show();
    }

    private void initializeTranslationObservers() {
        webViewViewModel.isTranslating().observe(this, isTranslating -> {
            if (isTranslating) {
                translationSnackbar = Snackbar.make(findViewById(R.id.webView_view), "Translating... please wait", Snackbar.LENGTH_INDEFINITE);

                translationSnackbar.setAction("Cancel", v -> {
                    webViewViewModel.cancelTranslation();
                });

                translationSnackbar.show();

                loading.setVisibility(View.VISIBLE);
                loading.setIndeterminate(true);

            } else {
                if (translationSnackbar != null && translationSnackbar.isShown()) {
                    translationSnackbar.dismiss();
                }

                loading.setIndeterminate(false);
                loading.setVisibility(View.GONE);
            }
        });

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

            String targetLang = sharedPreferencesRepository.getDefaultTranslationLanguage();
            String plainTextContent = textUtil.extractHtmlContent(result.finalHtml, "--####--");
            String fullContentForTts = result.finalTitle + "--####--" + plainTextContent;

            if (!fullContentForTts.trim().isEmpty()) {
                Log.d(TAG, "Manual translation finished. Updating TTS with language: " + targetLang);

                if (ttsPlayer.isSpeaking()) {
                    ttsPlayer.stopTtsPlayback();
                }

                ttsExtractor.setCurrentLanguage(targetLang, true);

                boolean extractSuccess = ttsPlayer.extract(currentId, feedId, fullContentForTts, targetLang);

                if (extractSuccess) {
                    ttsPlayer.setupTts();
                    Log.d(TAG, "TTS reinitialized with translated content in language: " + targetLang);
                }
            }
        });

        webViewViewModel.getTranslationError().observe(this, error -> {
            if (error != null && !error.isEmpty()) {
                new AlertDialog.Builder(this)
                        .setTitle("Translation Error")
                        .setMessage(error)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        });

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
        initializeSummarizationObservers();

        isReadingMode = getIntent().getBooleanExtra("read", false);

        if (ttsPlayer.isPlaying() && isReadingMode) {
            ttsPlayer.stop();
        }

        initializeUI();

        targetLanguage = sharedPreferencesRepository.getDefaultTranslationLanguage();
        translationMethod = sharedPreferencesRepository.getTranslationMethod();
        compositeDisposable = new CompositeDisposable();
        textUtil = new TextUtil(sharedPreferencesRepository, translationRepository);

        long[] currentPlaylist = getPlaylistFromIntent(getIntent());

        initializeToolbarListeners();
        initializeWebViewSettings();

        if (isReadingMode) {
            switchReadMode(currentPlaylist);
        } else {
            switchPlayMode(currentPlaylist);
        }
        
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
        summarizeButton = toolbar.getMenu().findItem(R.id.summarize);

        toggleTranslationButton.setVisible(false);

        highlightTextButton.setTitle(sharedPreferencesRepository.getHighlightText()
                ? R.string.highlight_text_turn_off : R.string.highlight_text_turn_on);
        backgroundMusicButton.setTitle(sharedPreferencesRepository.getBackgroundMusic()
                ? R.string.background_music_turn_off : R.string.background_music_turn_on);
    }

    private void loadHtmlIntoWebView(String html) {
        EntryInfo entryInfo = webViewViewModel.getEntryInfoById(currentId);
        String titleToDisplay;

        if (entryInfo != null) {
            if (isTranslatedView) {
                titleToDisplay = entryInfo.getTranslatedTitle();
                if (titleToDisplay == null || titleToDisplay.isEmpty()) {
                    titleToDisplay = entryInfo.getEntryTitle();
                }
            } else {
                titleToDisplay = entryInfo.getEntryTitle();
            }
        } else {
            titleToDisplay = "Title not available";
        }

        loadHtmlIntoWebView(html, titleToDisplay);
    }

    private void updateToggleTranslationVisibility() {
        EntryInfo entryInfo = webViewViewModel.getEntryInfoById(currentId);
        if (entryInfo == null || isSummaryView) {
            toggleTranslationButton.setVisible(false);
            return;
        }

        boolean translationExists = entryInfo.getTranslatedTitle() != null && !entryInfo.getTranslatedTitle().equals(entryInfo.getEntryTitle());
        toggleTranslationButton.setVisible(translationExists);

        if (translationExists) {
            toggleTranslationButton.setTitle(isTranslatedView ? "Show Original" : "Show Translation");
        }
    }

    private void initializePlaybackModes() {
        Log.w(TAG, "initializePlaybackModes() called - this should not happen!");
    }

    private void loadEntryContent() {
        long entryId = getIntent().getLongExtra("entry_id", -1);
        if (entryId == -1) { makeSnackbar("Error: No article ID provided."); finish(); return; }
        currentId = entryId;
        currentPlaylistIndex = ttsPlaylist.indexOf(currentId);

        EntryInfo entryInfo = webViewViewModel.getEntryInfoById(currentId);
        if (entryInfo == null) { makeSnackbar("Error: Could not load article data."); finish(); return; }

        currentLink = entryInfo.getEntryLink();
        feedId = entryInfo.getFeedId();
        bookmark = entryInfo.getBookmark();

        if (!isReadingMode) {
            ttsPlaylist.updatePlayingId(currentId);
        }

        String currentHtml = entryInfo.getHtml();
        String originalHtml = entryRepository.getOriginalHtmlById(currentId);

        if (currentHtml == null || currentHtml.trim().isEmpty()) {
            webView.loadUrl(currentLink);
            if (getSupportActionBar() != null) getSupportActionBar().setTitle(entryInfo.getEntryTitle());
            browserButton.setVisible(false);
            offlineButton.setVisible(false);
            toggleTranslationButton.setVisible(false);
            summarizeButton.setVisible(false);
            translationButton.setVisible(false);
            return;
        }

        boolean autoTranslateOn = sharedPreferencesRepository.getAutoTranslate();
        boolean translationExists = entryInfo.getTranslated() != null && !entryInfo.getTranslated().trim().isEmpty();
        boolean summaryExists = entryInfo.getSummary() != null && !entryInfo.getSummary().trim().isEmpty();

        if (isInitialLoad) {
            if (sharedPreferencesRepository.isSummarizationEnabled() && summaryExists) {
                isSummaryView = true;
            } else {
                isSummaryView = false;
                isTranslatedView = autoTranslateOn && translationExists;
            }
            isInitialLoad = false;
        }

        sharedPreferencesRepository.setIsTranslatedView(currentId, isTranslatedView);

        String htmlToLoad;
        String titleToDisplay;
        String contentForTts;
        String langForTts;

        if (isSummaryView) {
            String summaryText = entryInfo.getSummary();

            String baseTitle = (autoTranslateOn && entryInfo.getTranslatedTitle() != null && !entryInfo.getTranslatedTitle().isEmpty())
                    ? entryInfo.getTranslatedTitle()
                    : entryInfo.getEntryTitle();
            
            titleToDisplay = getString(R.string.summary_prefix) + ": " + baseTitle;

            String[] paragraphs = summaryText.split("\\n\\n");
            StringBuilder htmlSummary = new StringBuilder();
            for (String p : paragraphs) {
                if (!p.trim().isEmpty()) htmlSummary.append("<p>").append(p).append("</p>");
            }
            htmlToLoad = htmlSummary.toString();
            String processedSummary = summaryText.replace("\n\n", " --####-- ");
            contentForTts = titleToDisplay + " --####-- " + processedSummary;

            langForTts = entryInfo.isAiSummaryTranslated()
                    ? sharedPreferencesRepository.getDefaultTranslationLanguage()
                    : entryInfo.getFeedLanguage();

            loading.setVisibility(View.GONE);

            summarizeButton.setTitle("Show Full Article");
            browserButton.setVisible(false);
        } else {
            if (isTranslatedView && translationExists) {
                htmlToLoad = currentHtml;
                titleToDisplay = entryInfo.getTranslatedTitle();
                contentForTts = titleToDisplay + " --####-- " + entryInfo.getTranslated();
                langForTts = sharedPreferencesRepository.getDefaultTranslationLanguage();
            } else {
                htmlToLoad = originalHtml;
                titleToDisplay = entryInfo.getEntryTitle();
                contentForTts = titleToDisplay + " --####-- " + entryInfo.getContent();
                langForTts = entryInfo.getFeedLanguage();
            }
            summarizeButton.setTitle("Show Summary");
            browserButton.setVisible(true);
        }

        loadHtmlIntoWebView(htmlToLoad, titleToDisplay);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(titleToDisplay);
        }

        summarizeButton.setVisible(sharedPreferencesRepository.isSummarizationEnabled());

        translationButton.setVisible(!autoTranslateOn && !isSummaryView);

        updateToggleTranslationVisibility();

        if (bookmark == null || bookmark.equals("N")) {
            bookmarkButton.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_bookmark_outline));
        } else {
            bookmarkButton.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_bookmark_filled));
        }

        // ❌ REMOVED: UI must NEVER call ttsPlayer.extract()
        // Only Service calls extract() when user presses Play
        // This was the ROOT CAUSE of all TTS bugs!

        sharedPreferencesRepository.setCurrentTtsContent(contentForTts);
        sharedPreferencesRepository.setCurrentTtsLang(langForTts);

        sharedPreferencesRepository.setCurrentReadingEntryId(currentId);
        if (!isSummaryView) {
            syncLoadingWithTts();
        }

        if (!isReadingMode) {
            functionButtons.setVisibility(View.VISIBLE);
            functionButtons.setAlpha(1.0f);
        }
    }

    private void initializeSummarizationObservers() {
        webViewViewModel.isSummarizing().observe(this, isSummarizing -> {
            if (isSummarizing) {
                summarySnackbar = Snackbar.make(findViewById(R.id.webView_view), "Generating summary...", Snackbar.LENGTH_INDEFINITE);

                summarySnackbar.setAction(R.string.cancel, v -> {
                    webViewViewModel.cancelSummarization();
                });

                summarySnackbar.show();

                loading.setVisibility(View.VISIBLE);
                loading.setIndeterminate(true);

            } else {
                if (summarySnackbar != null && summarySnackbar.isShown()) {
                    summarySnackbar.dismiss();
                }

                loading.setIndeterminate(false);
                loading.setVisibility(View.GONE);
            }
        });

        webViewViewModel.getSummaryResult().observe(this, summary -> {
            if (summary != null && !summary.isEmpty()) {
                if (!isSummaryView) {
                    originalHtmlForSummary = entryRepository.getOriginalHtmlById(currentId);
                }

                EntryInfo originalEntryInfo = webViewViewModel.getEntryInfoById(currentId);

                boolean autoTranslateOn = sharedPreferencesRepository.getAutoTranslate();
                String baseTitle = (autoTranslateOn && originalEntryInfo != null && originalEntryInfo.getTranslatedTitle() != null && !originalEntryInfo.getTranslatedTitle().isEmpty())
                        ? originalEntryInfo.getTranslatedTitle()
                        : (originalEntryInfo != null ? originalEntryInfo.getEntryTitle() : "");
                
                String summaryTitle = getString(R.string.summary_prefix) + ": " + baseTitle;

                String[] paragraphs = summary.split("\\n\\n");
                StringBuilder htmlSummary = new StringBuilder();
                for (String p : paragraphs) {
                    if (!p.trim().isEmpty()) {
                        htmlSummary.append("<p>").append(p).append("</p>");
                    }
                }

                loadHtmlIntoWebView(htmlSummary.toString(), summaryTitle);
                isSummaryView = true;
                summarizeButton.setTitle("Show Full Article");
                toggleTranslationButton.setVisible(false);
                browserButton.setVisible(false);
            }
        });
    }

    private boolean handleSummarizeClick() {
        if (isSummaryView) {
            isSummaryView = false;
            loadEntryContent();
            if (!isReadingMode && mMediaBrowserHelper != null && mMediaBrowserHelper.getTransportControls() != null) {
                mMediaBrowserHelper.getTransportControls().sendCustomAction("contentChangedSummary", null);
            }
        } else {
            EntryInfo info = webViewViewModel.getEntryInfoById(currentId);
            if (info != null && info.getSummary() != null && !info.getSummary().trim().isEmpty()) {
                isSummaryView = true;
                loadEntryContent();
                if (!isReadingMode && mMediaBrowserHelper != null && mMediaBrowserHelper.getTransportControls() != null) {
                    mMediaBrowserHelper.getTransportControls().sendCustomAction("contentChangedSummary", null);
                }
            } else {
                webViewViewModel.summarizeArticle(currentId);
            }
        }
        return true;
    }

    private void updateToggleStateAndWebView(String originalHtml, String translatedHtml) {
        boolean hasOriginal = originalHtml != null && !originalHtml.trim().isEmpty();
        boolean hasTranslated = translatedHtml != null && !translatedHtml.trim().isEmpty();

        toggleTranslationButton.setVisible(hasOriginal && hasTranslated);
        toggleTranslationButton.setTitle(isTranslatedView ? "Show Original" : "Show Translation");

        String htmlToLoad = isTranslatedView ? translatedHtml : originalHtml;

        String titleToDisplay = (getSupportActionBar() != null && getSupportActionBar().getTitle() != null)
                ? getSupportActionBar().getTitle().toString()
                : "";

        Log.d(TAG, "LiveEntry - Current Mode: " + (isTranslatedView ? "Translated" : "Original"));
        Log.d(TAG, "LiveEntry - HTML to Load:\n" + htmlToLoad);

        if (htmlToLoad != null && !htmlToLoad.trim().isEmpty()) {
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
        Document doc = Jsoup.parse(html);
        doc.head().append(webViewViewModel.getStyle());

        EntryInfo entryInfo = webViewViewModel.getEntryInfoById(currentId);
        if (entryInfo != null && !doc.html().contains("class=\"entry-header\"")) {
            doc.selectFirst("body").prepend(
                    webViewViewModel.getHtml(
                            title,
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

            case R.id.summarize:
                return handleSummarizeClick();

            case R.id.translate:
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
                // Stop TTS and save current sentence index before switching
                if (!isReadingMode && ttsPlayer != null) {
                    ttsPlayer.stopTtsPlayback();
                    int currentSentence = ttsPlayer.getCurrentSentenceIndex();
                    sharedPreferencesRepository.setCurrentTtsSentencePosition(currentSentence);
                }
                loadEntryContent();
                if (!isReadingMode && mMediaBrowserHelper != null && mMediaBrowserHelper.getTransportControls() != null) {
                    mMediaBrowserHelper.getTransportControls().sendCustomAction("contentChangedTranslation", null);
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
                
                // Get current playlist from cache or Intent
                long[] playlist = currentPlaylistCache;
                if (playlist == null) {
                    playlist = getIntent().getLongArrayExtra("playlist_ids");
                }
                
                switchPlayMode(playlist);
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

                long[] playlist = currentPlaylistCache;
                if (playlist == null) {
                    playlist = getIntent().getLongArrayExtra("playlist_ids");
                }
                
                switchReadMode(playlist);
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
                Log.d(TAG, "WebChromeClient: onReceivedTitle called with '" + title + "'. IGNORING IT.");
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

    private long[] getPlaylistFromIntent(Intent intent) {
        if (intent != null && intent.hasExtra("playlist_ids")) {
            long[] playlistArray = intent.getLongArrayExtra("playlist_ids");
            if (playlistArray != null && playlistArray.length > 0) {
                currentPlaylistCache = playlistArray;

                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < playlistArray.length; i++) {
                    sb.append(playlistArray[i]);
                    if (i < playlistArray.length - 1) {
                        sb.append(",");
                    }
                }
                String newPlaylistString = sb.toString();

                String currentPlaylist = playlistRepository.getLatestPlaylist();
                if (currentPlaylist == null || !currentPlaylist.equals(newPlaylistString)) {
                    Playlist playlist = new Playlist(new Date(), newPlaylistString);
                    playlistRepository.insert(playlist);
                    Log.d(TAG, "✅ Playlist saved/updated in repository");
                } else {
                    Log.d(TAG, "ℹ️ Playlist unchanged, keeping existing");
                }
                
                Log.d(TAG, "✅ Playlist extracted from intent. Size: " + playlistArray.length);
                return playlistArray;
            }
        }
        return null;
    }

    /**
     * Builds complete playback state from current UI state.
     * This is the SINGLE SOURCE OF TRUTH for what should be played.
     * Service will use this data exactly as provided.
     */
    private PlaybackStateModel buildPlaybackState() {
        PlaybackStateModel state = new PlaybackStateModel();
        
        // Get playlist
        long[] playlistArray = getPlaylistFromIntent(getIntent());
        if (playlistArray == null || playlistArray.length == 0) {
            playlistArray = createSimpleFallbackPlaylist(currentId);
        }
        
        state.entryIds = new ArrayList<>();
        for (long id : playlistArray) {
            state.entryIds.add(id);
        }
        
        // Find current index
        state.currentIndex = state.entryIds.indexOf(currentId);
        if (state.currentIndex == -1) {
            state.currentIndex = 0;
        }
        
        state.currentEntryId = currentId;
        
        // Determine mode and content
        EntryInfo entryInfo = webViewViewModel.getEntryInfoById(currentId);
        if (entryInfo == null) {
            Log.e(TAG, "buildPlaybackState: EntryInfo is null for ID " + currentId);
            return null;
        }
        
        state.entryTitle = entryInfo.getEntryTitle();
        state.feedTitle = entryInfo.getFeedTitle();
        
        // Build content based on current view mode
        if (isSummaryView) {
            state.mode = PlaybackStateModel.PlaybackMode.SUMMARY;
            String summaryText = entryInfo.getSummary();
            if (summaryText == null || summaryText.trim().isEmpty()) {
                Log.e(TAG, "buildPlaybackState: Summary is empty");
                return null;
            }
            
            String baseTitle = (isTranslatedView && entryInfo.getTranslatedTitle() != null && !entryInfo.getTranslatedTitle().isEmpty()) 
                ? entryInfo.getTranslatedTitle() 
                : entryInfo.getEntryTitle();
            String titleWithPrefix = getString(R.string.summary_prefix) + ": " + baseTitle;
            String processedSummary = summaryText.replace("\n\n", " --####-- ");
            state.textToRead = titleWithPrefix + " --####-- " + processedSummary;
            
            state.language = entryInfo.isAiSummaryTranslated() 
                ? sharedPreferencesRepository.getDefaultTranslationLanguage()
                : entryInfo.getFeedLanguage();
                
        } else if (isTranslatedView && entryInfo.getTranslated() != null && !entryInfo.getTranslated().trim().isEmpty()) {
            state.mode = PlaybackStateModel.PlaybackMode.TRANSLATED;
            state.textToRead = entryInfo.getTranslatedTitle() + " --####-- " + entryInfo.getTranslated();
            state.language = sharedPreferencesRepository.getDefaultTranslationLanguage();
            
        } else {
            state.mode = PlaybackStateModel.PlaybackMode.FULL;
            String content = entryInfo.getContent();
            if (content == null || content.trim().isEmpty()) {
                Log.e(TAG, "buildPlaybackState: Content is empty");
                return null;
            }
            state.textToRead = entryInfo.getEntryTitle() + " --####-- " + content;
            state.language = entryInfo.getFeedLanguage();
        }
        
        // Ensure language is set
        if (state.language == null || state.language.isEmpty()) {
            state.language = "en";
        }
        
        Log.d(TAG, "✅ Built PlaybackStateModel:");
        Log.d(TAG, "   Mode: " + state.mode);
        Log.d(TAG, "   Playlist size: " + state.entryIds.size());
        Log.d(TAG, "   Current index: " + state.currentIndex);
        Log.d(TAG, "   Language: " + state.language);
        Log.d(TAG, "   Text length: " + state.textToRead.length());
        
        return state;
    }

    private void switchReadMode(long[] playlistIdsFromIntent) {
        functionButtonsReadingMode.setVisibility(View.VISIBLE);

        List<Long> playlistIds = new ArrayList<>();
        if (playlistIdsFromIntent != null && playlistIdsFromIntent.length > 0) {
            for (long id : playlistIdsFromIntent) {
                playlistIds.add(id);
            }
            Log.d(TAG, "✅ switchReadMode using playlist from intent. Size: " + playlistIds.size());
        } else {
            String playlistIdString = playlistRepository.getLatestPlaylist();
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
            Log.w(TAG, "⚠️ switchReadMode using fallback from repository. Size: " + playlistIds.size());
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

    private void handleNextArticle() {
        if (currentPlaylistIndex < 0) return;
        int nextIndex = currentPlaylistIndex + 1;
        if (nextIndex >= ttsPlaylist.size()) {
            makeSnackbar("This is the last article");
            return;
        }
        long nextEntryId = ttsPlaylist.get(nextIndex);
        currentPlaylistIndex = nextIndex;
        loadArticleForPlayback(nextEntryId);
    }

    private void handlePreviousArticle() {
        if (currentPlaylistIndex <= 0) {
            makeSnackbar("This is the first article");
            return;
        }
        int prevIndex = currentPlaylistIndex - 1;
        long prevEntryId = ttsPlaylist.get(prevIndex);
        currentPlaylistIndex = prevIndex;
        loadArticleForPlayback(prevEntryId);
    }

    private void navigateToEntry(long entryId) {
        Intent intent = new Intent(this, WebViewActivity.class);
        intent.putExtra("entry_id", entryId);
        intent.putExtra("from_skip", true);

        onNewIntent(intent);
    }

    /**
     * STEP 4: Load article for playback after skip button press.
     * Called when Service sends "request_next_article" or "request_previous_article" event.
     * UI loads the article and sends new PlaybackStateModel to Service.
     */
    private void loadArticleForPlayback(long entryId) {
        runOnUiThread(() -> {
            Log.e("UI_TRACE", "🧭 Navigating to article " + entryId + " via skip");

            // 1️⃣ Navigate UI (same as user click)
            navigateToEntry(entryId);

            // 2️⃣ AFTER navigation, send play command
            webView.post(() -> {
                PlaybackStateModel model = buildPlaybackState();
                if (model == null) {
                    Log.e("UI_TRACE", "❌ PlaybackStateModel is null after navigation");
                    return;
                }

                Bundle extras = new Bundle();
                extras.putParcelable("playback_state", model);

                MediaControllerCompat controller = mMediaBrowserHelper.getMediaController();
                if (controller != null) {
                    controller.getTransportControls()
                              .playFromMediaId(String.valueOf(entryId), extras);
                    Log.e("UI_TRACE", "▶️ Sent playFromMediaId for entry " + entryId);
                } else {
                    Log.e("UI_TRACE", "❌ MediaController is null");
                }
            });
        });
    }

    private long[] createSimpleFallbackPlaylist(long currentEntryId) {
        List<Long> fallbackList = new ArrayList<>();
        fallbackList.add(currentEntryId);

        for (int i = 1; i <= 2; i++) {
            long nextId = currentEntryId + i;
            if (entryRepository.checkIdExist(nextId)) {
                fallbackList.add(nextId);
            }
        }
        
        long[] result = new long[fallbackList.size()];
        for (int i = 0; i < fallbackList.size(); i++) {
            result[i] = fallbackList.get(i);
        }
        
        Log.d("TTS_QUEUE_FIX", "   📝 Created fallback playlist with " + result.length + " items: " + fallbackList.toString());
        return result;
    }

    private void switchPlayMode(long[] playlistIdsFromIntent) {
        Log.d("TTS_QUEUE_FIX", "🎮 switchPlayMode() called");
        
        webView.setWebViewClient(new WebClient());
        
        List<Long> playlistIds = new ArrayList<>();
        if (playlistIdsFromIntent != null && playlistIdsFromIntent.length > 0) {
            for (long id : playlistIdsFromIntent) {
                playlistIds.add(id);
            }
            Log.d("TTS_QUEUE_FIX", "   ✅ switchPlayMode using playlist from intent. Size: " + playlistIds.size());
        } else {
            String playlistIdString = playlistRepository.getLatestPlaylist();
            Log.d("TTS_QUEUE_FIX", "   📋 Fallback: Playlist from repository: " + (playlistIdString == null ? "NULL" : playlistIdString));
            
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
            Log.w("TTS_QUEUE_FIX", "   ⚠️ switchPlayMode using fallback from repository. Size: " + playlistIds.size());
        }

        if (!playlistIds.isEmpty()) {
            ttsPlaylist.setPlaylist(playlistIds, currentId);
            Log.d("TTS_QUEUE_FIX", "   📝 TtsPlaylist updated with " + playlistIds.size() + " items");
        }
        
        setupMediaPlaybackButtons();

        mMediaBrowserHelper = new MediaBrowserConnection(this);
        mMediaBrowserHelper.registerCallback(new MediaBrowserListener());

        switchReadModeButton.setVisible(true);
    }

    private void setupMediaPlaybackButtons() {
        Log.d("TTS_QUEUE_FIX", "🔧🔧🔧 DEBUG: setupMediaPlaybackButtons() CALLED");
        Log.d("TTS_QUEUE_FIX", "   Current Mode: " + (isReadingMode ? "READING" : "PLAY"));
        Log.d("TTS_QUEUE_FIX", "   Current ID at setup time: " + currentId);
        
        playPauseButton.setOnClickListener(view -> {
            Log.d("TTS_QUEUE_FIX", "▶️▶️▶️ DEBUG: Play/Pause button CLICKED!");

            long playEntryId = currentId;
            if (playEntryId == 0) {
                playEntryId = getIntent().getLongExtra("entry_id", -1);
                Log.w(TAG, "   ⚠️ currentId was 0, retrieved from intent: " + playEntryId);
            }
            
            if (playEntryId <= 0) {
                Log.e(TAG, "   ❌ Cannot play: invalid entry ID");
                makeSnackbar("Error: Invalid article ID");
                return;
            }
            
            MediaControllerCompat controller = mMediaBrowserHelper.getMediaController();
            if (controller == null) {
                Log.e(TAG, "❌ MediaController is null, cannot process play/pause click");
                return;
            }

            PlaybackStateCompat playbackState = controller.getPlaybackState();
            boolean isPlaying = playbackState != null && playbackState.getState() == PlaybackStateCompat.STATE_PLAYING;
            Log.d(TAG, "   Current playback state: " + (isPlaying ? "PLAYING" : "PAUSED/STOPPED"));

            if (isPlaying) {
                Log.d(TAG, "   Action: PAUSE");
                controller.getTransportControls().pause();
            } else {
                Log.d(TAG, "   Action: PLAY - Building complete playback state");
                
                // Build complete playback state from UI
                PlaybackStateModel playbackModel = buildPlaybackState();
                if (playbackModel == null) {
                    Log.e(TAG, "Failed to build playback state");
                    makeSnackbar("Error: Could not prepare playback");
                    return;
                }
                
                Bundle extras = new Bundle();
                extras.putParcelable("playback_state", playbackModel);
                
                Log.d(TAG, "   Sending PlaybackStateModel:");
                Log.d(TAG, "     - Mode: " + playbackModel.mode);
                Log.d(TAG, "     - Playlist size: " + playbackModel.entryIds.size());
                Log.d(TAG, "     - Current index: " + playbackModel.currentIndex);
                Log.d(TAG, "     - Language: " + playbackModel.language);
                Log.d(TAG, "     - Text length: " + playbackModel.textToRead.length());
                
                controller.getTransportControls().playFromMediaId(String.valueOf(currentId), extras);
                Log.d(TAG, "   🚀 Play command sent with PlaybackStateModel");
            }
        });

        skipNextButton.setOnClickListener(view -> {
            Log.d(TAG, "⏭️ Skip Next button clicked");
            mMediaBrowserHelper.getTransportControls().skipToNext();
        });
        
        skipPreviousButton.setOnClickListener(view -> {
            Log.d(TAG, "⏮️ Skip Previous button clicked");
            mMediaBrowserHelper.getTransportControls().skipToPrevious();
        });
        
        fastForwardButton.setOnClickListener(view -> {
            Log.d(TAG, "⏩ Fast Forward button clicked");
            mMediaBrowserHelper.getTransportControls().fastForward();
        });
        
        rewindButton.setOnClickListener(view -> {
            Log.d(TAG, "⏪ Rewind button clicked");
            mMediaBrowserHelper.getTransportControls().rewind();
        });
        
        Log.d(TAG, "🔧 setupMediaPlaybackButtons() completed - all listeners attached");
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
            Log.d("HIGHLIGHT_DEBUG", "Raw searchText from TTS: \'" + searchText + "\'");

            text = text.replaceAll("\\s+", " ");

            if (webViewViewModel.endsWithBreak(text)) {
                text = text.substring(0, text.length() - 1);
            }

            Log.d("HIGHLIGHT_DEBUG", "Normalized text to search: \'" + text + "\'");
            final String finalText = text.trim();

            if (!finalText.isEmpty()) {
                ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> {
                    if (isSummaryView) {
                        String[] paragraphs = finalText.split("--####--");
                        for (String paragraph : paragraphs) {
                            String trimmedPara = paragraph.trim();
                            if (!trimmedPara.isEmpty()) {
                                Log.d("HIGHLIGHT_DEBUG", "Searching paragraph: \'" + trimmedPara + "\'");
                                webView.findAllAsync(trimmedPara);
                            }
                        }
                    } else {
                        Log.d("HIGHLIGHT_DEBUG", "Executing WebView.findAllAsync for: \'" + finalText + "\'");
                        webView.findAllAsync(finalText);
                    }
                });
            }
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
            // ❌ REMOVED: Callback registration moved to MediaBrowserHelper.onConnected()
            // Registering here is too early - MediaController is null until MediaBrowser connects
            Log.e("UI_TRACE", "🎯 onResume: MediaBrowser.onStart() called, waiting for connection");
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

            String titleFromIntent = getIntent().getStringExtra("entry_title");

            String titleToDisplay = titleFromIntent;
            if (titleToDisplay == null || titleToDisplay.isEmpty()) {
                EntryInfo entryInfo = webViewViewModel.getEntryInfoById(currentId);
                if (entryInfo != null) {
                    titleToDisplay = entryInfo.getEntryTitle();
                }
            }

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

            String titleFromIntent = getIntent().getStringExtra("entry_title");

            String titleToDisplay = titleFromIntent;
            if (titleToDisplay == null || titleToDisplay.isEmpty()) {
                EntryInfo entryInfo = webViewViewModel.getEntryInfoById(currentId);
                if (entryInfo != null) {
                    titleToDisplay = entryInfo.getEntryTitle();
                }
            }

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
        private boolean isPreparationRequested = false;

        private MediaBrowserConnection(Context context) {
            super(context, TtsService.class);
        }

        @Override
        protected void onChildrenLoaded(@NonNull String parentId, @NonNull List<MediaBrowserCompat.MediaItem> children) {
            super.onChildrenLoaded(parentId, children);

            final MediaControllerCompat mediaController = getMediaController();
            if (mediaController == null) {
                Log.e("LIFECYCLE_DEBUG", "onChildrenLoaded: MediaController is NULL!");
                return;
            }

            if (isPreparationRequested) {
                return;
            }
            isPreparationRequested = true;

            String entryIdString = String.valueOf(getIntent().getLongExtra("entry_id", -1));
            if (!"-1".equals(entryIdString)) {
                // ONLY prepare metadata - DO NOT auto-play
                mediaController.getTransportControls().prepareFromMediaId(entryIdString, null);

                // Store playlist for later use when user clicks play button
                Log.d(TAG, "Session prepared for entry: " + entryIdString + ". Waiting for user to click play.");
            } else {
                Log.e("LIFECYCLE_DEBUG", "Client connected but entryId is invalid.");
            }

            ttsPlayer.setWebViewCallback(WebViewActivity.this);
            ttsPlayer.setWebViewConnected(true);
        }
    }

    private class MediaBrowserListener extends MediaControllerCompat.Callback {
        @Override
        public void onPlaybackStateChanged(PlaybackStateCompat state) {
            super.onPlaybackStateChanged(state);
            if (state == null) return;

            isPlaying = state.getState() == PlaybackStateCompat.STATE_PLAYING;
            updatePlayPauseButtonIcon(isPlaying);

            // Enable buttons when in a playable state
            if (state.getState() == PlaybackStateCompat.STATE_PLAYING ||
                    state.getState() == PlaybackStateCompat.STATE_PAUSED) {
                playPauseButton.setEnabled(true);
                functionButtons.setAlpha(1.0f);
                Log.d(TAG, "Playback state: " + state.getState() + ", buttons fully enabled");
            }

            // Reset to play icon when stopped
            if (state.getState() == PlaybackStateCompat.STATE_STOPPED) {
                playPauseButton.setIconResource(R.drawable.ic_play);
                playPauseButton.setEnabled(true);
                functionButtons.setAlpha(1.0f);
                Log.d(TAG, "Playback stopped, UI reset");
            }
        }
        
        @Override
        public void onExtrasChanged(Bundle extras) {
            super.onExtrasChanged(extras);
            if (extras != null) {
                String errorMessage = extras.getString("ERROR_MESSAGE");
                if (errorMessage != null) {
                    makeSnackbar(errorMessage);
                    Log.d(TAG, "Error message received from service: " + errorMessage);
                }
            }
        }

        @Override
        public void onSessionEvent(String event, Bundle extras) {
            Log.e("UI_TRACE", "📩 onSessionEvent RECEIVED");
            Log.e("UI_TRACE", "   event = " + event);
            Log.e("UI_TRACE", "   extras = " + (extras == null ? "null" : extras.keySet()));

            super.onSessionEvent(event, extras);

            if ("request_next_article".equals(event)) {
                Log.e("UI_TRACE", "➡️ request_next_article detected");
                handleNextArticle();
            } else if ("request_previous_article".equals(event)) {
                Log.e("UI_TRACE", "⬅️ request_previous_article detected");
                handlePreviousArticle();
            }
        }

        @Override
        public void onMetadataChanged(MediaMetadataCompat metadata) {
            if (metadata == null) return;

            long metadataEntryId = Long.parseLong(metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID));
            long intentEntryId = getIntent().getLongExtra("entry_id", -1);

            if (metadataEntryId != intentEntryId) return;
            if (isLoadingFromNewIntent) return;

            if (isSummaryView) {
                runOnUiThread(() -> loading.setVisibility(View.GONE)); // Hide it for summary
                updateToggleTranslationVisibility();
                return;
            }

            String titleFromIntent = getIntent().getStringExtra("entry_title");
            if (titleFromIntent != null && !titleFromIntent.isEmpty() && getSupportActionBar() != null) {
                getSupportActionBar().setTitle(titleFromIntent);
            }

            clearHistory = true;
            runOnUiThread(() -> {
                loading.setVisibility(View.VISIBLE);
                loading.setProgress(10);
            });
            functionButtons.setVisibility(View.VISIBLE);
            reloadButton.setVisible(false);
            bookmarkButton.setVisible(false);
            highlightTextButton.setVisible(false);
            showOfflineButton = false;

            content = metadata.getString("content");
            bookmark = metadata.getString("bookmark");
            currentLink = metadata.getString("link");
            feedId = metadata.getLong("feedId");

            if (bookmark == null || bookmark.equals("N")) {
                bookmarkButton.setIcon(R.drawable.ic_bookmark_outline);
            } else {
                bookmarkButton.setIcon(R.drawable.ic_bookmark_filled);
            }

            isTranslatedView = sharedPreferencesRepository.getIsTranslatedView(currentId);

            EntryInfo entryInfo = webViewViewModel.getEntryInfoById(currentId);
            boolean translationExists = entryInfo != null && entryInfo.getTranslated() != null && !entryInfo.getTranslated().trim().isEmpty();

            String htmlToLoad;
            if (isTranslatedView && translationExists) {
                htmlToLoad = webViewViewModel.getHtmlById(currentId);
            } else {
                htmlToLoad = webViewViewModel.getOriginalHtmlById(currentId);
            }

            if (htmlToLoad == null) {
                htmlToLoad = metadata.getString("html");
            }

            boolean isWebViewMode = sharedPreferencesRepository.getWebViewMode(currentId);

            if (isWebViewMode) {
                webView.loadUrl(currentLink);
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
                browserButton.setVisible(false);
                showOfflineButton = true;
            }

            updateToggleTranslationVisibility();

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