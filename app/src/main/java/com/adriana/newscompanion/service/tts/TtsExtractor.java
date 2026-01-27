package com.adriana.newscompanion.service.tts;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import java.net.HttpURLConnection;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import androidx.core.content.ContextCompat;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkContinuation;
import androidx.work.WorkManager;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import com.adriana.newscompanion.data.entry.Entry;
import com.adriana.newscompanion.data.entry.EntryRepository;
import com.adriana.newscompanion.data.feed.FeedRepository;
import com.adriana.newscompanion.data.playlist.PlaylistRepository;
import com.adriana.newscompanion.data.sharedpreferences.SharedPreferencesRepository;
import com.adriana.newscompanion.service.util.TextUtil;
import com.adriana.newscompanion.ui.webview.WebViewListener;
import com.adriana.newscompanion.worker.AiCleaningWorker;
import com.adriana.newscompanion.worker.AiSummarizationWorker;
import com.adriana.newscompanion.worker.TranslationWorker;

import net.dankito.readability4j.Article;
import net.dankito.readability4j.extended.Readability4JExtended;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;

@Singleton
public class TtsExtractor {

    private final CompositeDisposable disposables = new CompositeDisposable();
    private final String TAG = TtsExtractor.class.getSimpleName();
    private String currentLanguage;
    private boolean isLockedByTtsPlayer = false;
    private final Context context;
    private final EntryRepository entryRepository;
    private final FeedRepository feedRepository;
    private final PlaylistRepository playlistRepository;
    private final TextUtil textUtil;
    private final SharedPreferencesRepository sharedPreferencesRepository;
    private WebView webView;
    private String currentLink;
    private String currentTitle;
    private long currentIdInProgress = -1;
    private boolean extractionInProgress;
    private int delayTime;
    private TtsPlayerListener ttsCallback;
    private TtsPlaylist ttsPlaylist;
    private WebViewListener webViewCallback;
    private Date playlistDate;
    public final String delimiter = "--####--";
    private final List<Long> failedIds = new ArrayList<>();
    private final HashMap<Long, Integer> retryCountMap = new HashMap<>();
    private final int MAX_RETRIES = 3;
    private final HashSet<Long> feedsRequiringWebView = new HashSet<>();

    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private Runnable watchdogRunnable;
    private int loadCounter = 0;

    @SuppressLint("SetJavaScriptEnabled")
    @Inject
    public TtsExtractor(@ApplicationContext Context context, TtsPlaylist ttsPlaylist, EntryRepository entryRepository, FeedRepository feedRepository, PlaylistRepository playlistRepository, TextUtil textUtil, SharedPreferencesRepository sharedPreferencesRepository) {
        this.context = context;
        this.ttsPlaylist = ttsPlaylist;
        this.entryRepository = entryRepository;
        this.feedRepository = feedRepository;
        this.playlistRepository = playlistRepository;
        this.textUtil = textUtil;
        this.sharedPreferencesRepository = sharedPreferencesRepository;

        initWebView();
    }

    private void initWebView() {
        ContextCompat.getMainExecutor(context).execute(() -> {
            if (webView != null) {
                webView.destroy();
            }
            webView = new WebView(context);
            webView.setWebViewClient(new WebClient());
            webView.clearCache(true);
            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setDomStorageEnabled(true);
            Log.d(TAG, "WebView Engine Re-Initialized.");
        });
    }

    private void extractWithHttp(Entry entry) {
        // Start watchdog for HTTP extraction (30 seconds timeout)
        startWatchdog(30000);

        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                java.net.URL url = new java.net.URL(entry.getLink());
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(30000);
                connection.connect();

                int responseCode = connection.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Log.e(TAG, "HTTP Error " + responseCode + " for URL: " + entry.getLink());
                    failedIds.add(currentIdInProgress);
                    resetFlagsAndContinue();
                    return;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder htmlBuilder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    htmlBuilder.append(line);
                }
                reader.close();

                String html = htmlBuilder.toString();
                Readability4JExtended readability4J = new Readability4JExtended(entry.getLink(), html);
                Article article = readability4J.parse();

                if (article.getContentWithUtf8Encoding() != null) {
                    Document doc = Jsoup.parse(article.getContentWithUtf8Encoding());
                    doc.select("h1").remove();
                    doc.select("img").attr("style", "border-radius: 5px; max-width: 100%; max-height: 600px; height: auto; width: auto; object-fit: contain; margin-left:0");
                    doc.select("figure").attr("style", "width: 100%; max-height: 650px; overflow: hidden; margin: 1em 0; margin-left:0");
                    doc.select("iframe").attr("style", "width: 100%; margin-left:0");

                    StringBuilder contentCollector = new StringBuilder();
                    List<String> tags = Arrays.asList("h2", "h3", "h4", "h5", "h6", "p", "td", "pre", "th", "li", "figcaption", "blockquote", "section");
                    for (Element element : doc.getAllElements()) {
                        if (tags.contains(element.tagName())) {
                            boolean hasTagChild = false;
                            for (Element child : element.children()) if (tags.contains(child.tagName())) hasTagChild = true;
                            if (!hasTagChild) {
                                String text = element.text().trim();
                                if (text.length() > 1) {
                                    if (contentCollector.length() > 0) contentCollector.append(delimiter);
                                    contentCollector.append(text);
                                } else element.remove();
                            }
                        }
                    }
                    String finalFullText = contentCollector.toString();
                    if (finalFullText.length() < 200 || finalFullText.toLowerCase().contains("please enable javascript")) {
                        // Fallback to WebView: Flag this feed as requiring WebView for future articles
                        com.adriana.newscompanion.data.feed.Feed feed = feedRepository.getFeedById(entry.getFeedId());
                        if (feed != null) {
                            feedsRequiringWebView.add(feed.getId());
                            Log.d(TAG, "Feed " + feed.getId() + " flagged for WebView extraction due to JavaScript-heavy content");
                        }
                        failedIds.add(currentIdInProgress);
                    } else {
                        entryRepository.updateHtml(doc.html(), currentIdInProgress);
                        if (entryRepository.getOriginalHtmlById(currentIdInProgress) == null) {
                            entryRepository.updateOriginalHtml(doc.html(), currentIdInProgress);
                            entryRepository.updateContent(finalFullText, currentIdInProgress);
                        }
                        Log.d(TAG, "✓ HTTP SUCCESS: ID " + currentIdInProgress + " finished.");
                    }
                } else {
                    failedIds.add(currentIdInProgress);
                }
            } catch (Exception e) {
                Log.e(TAG, "HTTP extraction error for ID " + currentIdInProgress + ": " + e.getMessage());
                failedIds.add(currentIdInProgress);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
                cancelWatchdog();
                if (webViewCallback != null) {
                    webViewCallback.finishedSetup();
                    webViewCallback = null;
                }
                resetFlagsAndContinue();
            }
        }).start();
    }

    private void extractWithWebView(Entry entry) {
        ContextCompat.getMainExecutor(context).execute(() -> webView.loadUrl(entry.getLink()));
        startWatchdog(45000);
    }

    public void extractAllEntries() {
        if (extractionInProgress) return;

        if (loadCounter >= 20) {
            Log.d(TAG, "Recycling WebView engine...");
            loadCounter = 0;
            initWebView();
            new Handler(Looper.getMainLooper()).postDelayed(this::extractAllEntries, 3000);
            return;
        }

        Entry entry = entryRepository.getEmptyContentEntry();

        if (entry == null && !failedIds.isEmpty()) {
            long retryId = failedIds.remove(0);
            int attempts = retryCountMap.getOrDefault(retryId, 0);
            if (attempts < MAX_RETRIES) {
                retryCountMap.put(retryId, attempts + 1);
                entry = entryRepository.getEntryById(retryId);
            } else {
                extractAllEntries();
                return;
            }
        }

        if (entry != null) {
            extractionInProgress = true;
            currentIdInProgress = entry.getId();
            currentLink = entry.getLink();
            currentTitle = entry.getTitle();
            delayTime = feedRepository.getDelayTimeById(entry.getFeedId());
            loadCounter++;

            // Router: Check if feed requires authentication or has been flagged for WebView
            com.adriana.newscompanion.data.feed.Feed feed = feedRepository.getFeedById(entry.getFeedId());
            if (feed != null && (feed.isAuthenticated() || feedsRequiringWebView.contains(feed.getId()))) {
                Log.d(TAG, "Processing Article ID: " + currentIdInProgress + " [" + loadCounter + "/20] - Using WebView (authenticated or flagged)");
                extractWithWebView(entry);
            } else {
                Log.d(TAG, "Processing Article ID: " + currentIdInProgress + " [" + loadCounter + "/20] - Using HTTP (non-authenticated)");
                extractWithHttp(entry);
            }
        } else {
            Log.d(TAG, "No more articles in queue.");
            triggerAiPipelineChain();
        }
    }

    private void startWatchdog(long timeout) {
        cancelWatchdog();
        watchdogRunnable = () -> {
            if (extractionInProgress) {
                Log.e(TAG, "!!! WATCHDOG TIMEOUT !!! ID: " + currentIdInProgress + ". Skipping.");
                failedIds.add(currentIdInProgress);
                resetFlagsAndContinue();
            }
        };
        watchdogHandler.postDelayed(watchdogRunnable, timeout);
    }

    private void cancelWatchdog() {
        if (watchdogRunnable != null) {
            watchdogHandler.removeCallbacks(watchdogRunnable);
            watchdogRunnable = null;
        }
    }

    private void resetFlagsAndContinue() {
        currentIdInProgress = -1;
        extractionInProgress = false;
        new Handler(Looper.getMainLooper()).post(this::extractAllEntries);
    }

    private void triggerAiPipelineChain() {
        if (!sharedPreferencesRepository.isSummarizationEnabled() && 
            !sharedPreferencesRepository.isAiCleaningEnabled() && 
            !sharedPreferencesRepository.getAutoTranslate()) {
            return;
        }

        WorkManager workManager = WorkManager.getInstance(context);
        String uniqueWorkName = "AI_CONTENT_PIPELINE";
        WorkContinuation continuation = null;

        // FIX: Using REPLACE policy ensures the pipeline restarts with current pending items
        if (sharedPreferencesRepository.isSummarizationEnabled()) {
            OneTimeWorkRequest task = new OneTimeWorkRequest.Builder(AiSummarizationWorker.class).build();
            continuation = workManager.beginUniqueWork(uniqueWorkName, ExistingWorkPolicy.REPLACE, task);
        }

        if (sharedPreferencesRepository.isAiCleaningEnabled()) {
            OneTimeWorkRequest task = new OneTimeWorkRequest.Builder(AiCleaningWorker.class).build();
            if (continuation == null) continuation = workManager.beginUniqueWork(uniqueWorkName, ExistingWorkPolicy.REPLACE, task);
            else continuation = continuation.then(task);
        }

        if (sharedPreferencesRepository.getAutoTranslate()) {
            OneTimeWorkRequest task = new OneTimeWorkRequest.Builder(TranslationWorker.class).build();
            if (continuation == null) continuation = workManager.beginUniqueWork(uniqueWorkName, ExistingWorkPolicy.REPLACE, task);
            else continuation = continuation.then(task);
        }

        if (continuation != null) {
            continuation.enqueue();
            Log.d(TAG, "AI Pipeline chain enqueued (REPLACE policy).");
        }
    }

    /**
     * Ensures that the specified feed has a language detected and saved.
     * This method is synchronous and thread-safe, detecting language only once per feed.
     * 
     * @param feedId The ID of the feed to check/detect language for
     * @return The detected or existing language code for the feed
     */
    public synchronized String ensureFeedLanguageDetected(long feedId) {
        try {
            com.adriana.newscompanion.data.feed.Feed feed = feedRepository.getFeedById(feedId);
            
            if (feed == null) {
                Log.e(TAG, "Feed not found for ID: " + feedId);
                return "en"; // Fallback to English
            }
            
            String feedLanguage = feed.getLanguage();
            
            // Check if language is already set and valid
            if (feedLanguage != null && !feedLanguage.isEmpty() && !feedLanguage.equalsIgnoreCase("und")) {
                Log.d(TAG, "Feed already has language set: " + feed.getTitle() + " (" + feedLanguage + ")");
                return feedLanguage;
            }
            
            // Language not set, need to detect it
            Log.d(TAG, "Detecting language for feed: " + feed.getTitle());
            
            // Get a sample entry from this feed to detect language
            Entry sampleEntry = entryRepository.getFirstEntryByFeedId(feedId);
            
            if (sampleEntry == null || sampleEntry.getContent() == null || sampleEntry.getContent().isEmpty()) {
                Log.w(TAG, "No content available for language detection, defaulting to 'en'");
                feed.setLanguage("en");
                feedRepository.update(feed);
                return "en";
            }
            
            // Use the first 500 characters for language detection
            String sampleText = sampleEntry.getContent();
            if (sampleText.length() > 500) {
                sampleText = sampleText.substring(0, 500);
            }
            
            // Detect language synchronously (blocking call)
            String detectedLanguage = textUtil.identifyLanguageRx(sampleText)
                    .blockingGet();
            
            if (detectedLanguage != null && !detectedLanguage.equalsIgnoreCase("und")) {
                // Save the detected language to the feed
                feed.setLanguage(detectedLanguage);
                feedRepository.update(feed);
                Log.d(TAG, "Detected and saved language '" + detectedLanguage + "' for feed: " + feed.getTitle());
                return detectedLanguage;
            } else {
                // Fallback to English if detection fails
                feed.setLanguage("en");
                feedRepository.update(feed);
                Log.d(TAG, "Language detection failed for feed: " + feed.getTitle() + ", defaulting to 'en'");
                return "en";
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error detecting language for feed ID " + feedId + ": " + e.getMessage(), e);
            return "en"; // Fallback to English on error
        }
    }

    public void setCallback(TtsPlayerListener callback) { this.ttsCallback = callback; }
    public void setCallback(WebViewListener callback) { this.webViewCallback = callback; }

    public void prioritize() {
        Date newPlaylistDate = playlistRepository.getLatestPlaylistCreatedDate();
        if (playlistDate == null || !playlistDate.equals(newPlaylistDate)) {
            playlistDate = newPlaylistDate;
            entryRepository.clearPriority();
            List<Long> playlist = stringToLongList(playlistRepository.getLatestPlaylist());
            long lastId = entryRepository.getLastVisitedEntryId();
            int index = playlist.indexOf(lastId);
            int priority = 1;
            entryRepository.updatePriority(priority, lastId);
            while (index + 1 < playlist.size()) {
                index++;
                priority++;
                entryRepository.updatePriority(priority, playlist.get(index));
            }
        }
        extractAllEntries();
    }

    public class WebClient extends WebViewClient {
        private final Handler handler = new Handler();
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) { view.loadUrl(url); return true; }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            if (extractionInProgress && webView.getProgress() == 100) {
                handler.postDelayed(() -> webView.evaluateJavascript("(function() {return document.getElementsByTagName('html')[0].outerHTML;})();", value -> {
                    JsonReader reader = new JsonReader(new StringReader(value));
                    reader.setLenient(true);
                    StringBuilder contentCollector = new StringBuilder();
                    try {
                        if (reader.peek() == JsonToken.STRING) {
                            String html = reader.nextString();
                            if (html != null) {
                                Readability4JExtended readability4J = new Readability4JExtended(currentLink, html);
                                Article article = readability4J.parse();
                                if (article.getContentWithUtf8Encoding() != null) {
                                    Document doc = Jsoup.parse(article.getContentWithUtf8Encoding());
                                    doc.select("h1").remove();
                                    doc.select("img").attr("style", "border-radius: 5px; max-width: 100%; max-height: 600px; height: auto; width: auto; object-fit: contain; margin-left:0");
                                    doc.select("figure").attr("style", "width: 100%; max-height: 650px; overflow: hidden; margin: 1em 0; margin-left:0");
                                    doc.select("iframe").attr("style", "width: 100%; margin-left:0");

                                    List<String> tags = Arrays.asList("h2", "h3", "h4", "h5", "h6", "p", "td", "pre", "th", "li", "figcaption", "blockquote", "section");
                                    for (Element element : doc.getAllElements()) {
                                        if (tags.contains(element.tagName())) {
                                            boolean hasTagChild = false;
                                            for (Element child : element.children()) if (tags.contains(child.tagName())) hasTagChild = true;
                                            if (!hasTagChild) {
                                                String text = element.text().trim();
                                                if (text.length() > 1) {
                                                    if (contentCollector.length() > 0) contentCollector.append(delimiter);
                                                    contentCollector.append(text);
                                                } else element.remove();
                                            }
                                        }
                                    }
                                    String finalFullText = contentCollector.toString();
                                    if (finalFullText.length() < 200 || finalFullText.toLowerCase().contains("please enable javascript")) {
                                        failedIds.add(currentIdInProgress);
                                    } else {
                                        entryRepository.updateHtml(doc.html(), currentIdInProgress);
                                        if (entryRepository.getOriginalHtmlById(currentIdInProgress) == null) {
                                            entryRepository.updateOriginalHtml(doc.html(), currentIdInProgress);
                                            entryRepository.updateContent(finalFullText, currentIdInProgress);
                                        }
                                        Log.d(TAG, "✓ SUCCESS: ID " + currentIdInProgress + " finished.");
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        failedIds.add(currentIdInProgress);
                    } finally {
                        cancelWatchdog();
                        if (webViewCallback != null) {
                            webViewCallback.finishedSetup();
                            webViewCallback = null;
                        }
                        resetFlagsAndContinue();
                    }
                }), delayTime * 1000L);
            }
        }
    }

    public void setCurrentLanguage(String lang, boolean lock) {
        if (!isLockedByTtsPlayer || lock) {
            this.currentLanguage = lang;
            isLockedByTtsPlayer = lock;
        }
    }

    public List<Long> stringToLongList(String genreIds) {
        List<Long> list = new ArrayList<>();
        String[] array = genreIds.split(",");
        for (String s : array) if (!s.isEmpty()) list.add(Long.parseLong(s));
        return list;
    }

    public boolean isLocked() { return isLockedByTtsPlayer; }
    public String getCurrentLanguage() { return currentLanguage; }
}
