package com.adriana.newscompanion.service.tts;

import android.annotation.SuppressLint;
import android.content.Context;

import java.net.HttpURLConnection;

import android.os.Handler;
import android.os.Looper;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import androidx.core.content.ContextCompat;

import com.adriana.newscompanion.data.entry.Entry;
import com.adriana.newscompanion.data.entry.EntryRepository;
import com.adriana.newscompanion.data.feed.Feed;
import com.adriana.newscompanion.data.feed.FeedRepository;
import com.adriana.newscompanion.data.playlist.PlaylistRepository;
import com.adriana.newscompanion.data.repository.TranslationRepository;
import com.adriana.newscompanion.data.sharedpreferences.SharedPreferencesRepository;
import com.adriana.newscompanion.service.util.TextUtil;
import com.adriana.newscompanion.ui.webview.WebViewListener;

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

@Singleton
public class TtsExtractor {
    private String webViewExpectedUrl = null;

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
    private final TranslationRepository translationRepository;
    private Runnable watchdogRunnable;
    private int loadCounter = 0;
    private final Object aiLock = new Object();
    private long webViewSafeId = -1;

    @SuppressLint("SetJavaScriptEnabled")
    @Inject
    public TtsExtractor(@ApplicationContext Context context, TtsPlaylist ttsPlaylist, EntryRepository entryRepository, FeedRepository feedRepository, PlaylistRepository playlistRepository, TextUtil textUtil, SharedPreferencesRepository sharedPreferencesRepository, TranslationRepository translationRepository) {
        this.context = context;
        this.ttsPlaylist = ttsPlaylist;
        this.entryRepository = entryRepository;
        this.feedRepository = feedRepository;
        this.playlistRepository = playlistRepository;
        this.textUtil = textUtil;
        this.sharedPreferencesRepository = sharedPreferencesRepository;
        this.translationRepository = translationRepository;
        initWebView();
    }

    private void initWebView() {
        ContextCompat.getMainExecutor(context).execute(() -> {
            if (webView != null) {
                webView.destroy();
            }
            webView = new WebView(context);
            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
            webView.setWebViewClient(new WebClient());
            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setDomStorageEnabled(true);
            Log.d(TAG, "WebView Engine Re-Initialized.");
        });
    }

    private void extractWithHttp(Entry entry) {
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
                    entryRepository.updateContent("FAILED", currentIdInProgress);
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
                        String contentWithTitle = currentTitle + delimiter + finalFullText;
                        if (currentIdInProgress == -1) {
                            return;
                        }

                        long safeId = entry.getId();

                        entryRepository.updateOriginalHtml(doc.html(), safeId);
                        entryRepository.updateHtml(doc.html(), safeId);
                        entryRepository.updateContent(contentWithTitle, safeId);
                        webViewExpectedUrl = null;
                        runAiPipelineNow();
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
        webViewSafeId = entry.getId();
        webViewExpectedUrl = entry.getLink();
        String cookie = android.webkit.CookieManager.getInstance().getCookie(entry.getLink());
        Log.e("COOKIE_DEBUG", "Cookie for " + entry.getLink() + " = " + cookie);
        Log.e("COOKIE_BEFORE_LOAD", "Cookie = " +
                CookieManager.getInstance().getCookie(entry.getLink()));
        ContextCompat.getMainExecutor(context).execute(() -> {

            String url = entry.getLink();

            Log.e("COOKIE_FORCE", "Applying cookie to WebView: " + cookie);

            if (cookie != null) {
                CookieManager.getInstance().setCookie(url, cookie);
            }

            webView.loadUrl(url);
        });
        startWatchdog(45000);
    }

    public void extractAllEntries() {
        if (extractionInProgress) return;

        if (loadCounter >= 100) {
            Log.d(TAG, "Recycling WebView engine...");
            loadCounter = 0;
            initWebView();
            new Handler(Looper.getMainLooper()).postDelayed(this::extractAllEntries, 3000);
            return;
        }

        Entry entry;

        while (true) {
            entry = entryRepository.getEmptyContentEntry();
            if (entry != null) {
                Log.e("DB_DEBUG", "ENTRY FOUND → ID=" + entry.getId()
                        + " | content=" + entry.getContent()
                        + " | html=" + entry.getHtml()
                        + " | originalHtml=" + entry.getOriginalHtml());
            } else {
                Log.e("DB_DEBUG", "❌ entry = NULL (NO EMPTY ENTRY FOUND)");
            } if (entry == null) {
                Log.e("PROOF", "❌ NO ENTRY FOUND (queue empty)");
                return; // 🔥 EXIT SAFELY (NO CRASH)
            }

            int attempts = retryCountMap.getOrDefault(entry.getId(), 0);

            if (attempts >= MAX_RETRIES) {
                Log.e("PIPELINE_TRACE", "❌ HARD SKIP ID: " + entry.getId());

                retryCountMap.remove(entry.getId());

                entryRepository.updateContent("", entry.getId());

                continue;
            }

            break;
        }

        Log.e("PROOF", "STEP 2: ENTRY FOUND ID = " + entry.getId());

        extractionInProgress = true;
        currentIdInProgress = entry.getId();
        Log.e("PIPELINE_TRACE", "🚀 START EXTRACTION ID = " + currentIdInProgress);

        currentLink = entry.getLink();
        currentTitle = entry.getTitle();

        delayTime = feedRepository.getDelayTimeById(entry.getFeedId());
        if (delayTime <= 0) delayTime = 3;

        Log.e("DELAY_DEBUG", "delayTime = " + delayTime + " for ID = " + entry.getId());

        loadCounter++;

// 🔥 ROUTER (UNCHANGED)
        com.adriana.newscompanion.data.feed.Feed feed = feedRepository.getFeedById(entry.getFeedId());

        if (feed != null && feed.isAuthenticated()) {
            Log.e("AUTH_DEBUG", "Feed marked authenticated BUT cookies may not exist");
            extractWithWebView(entry);
        }
        else if (feed != null && feedsRequiringWebView.contains(feed.getId())) {
            extractWithWebView(entry);
        }
        else {
            extractWithHttp(entry);
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
        Log.e("ENTRY_POINT", "prioritize CALLED");
        Date newPlaylistDate = playlistRepository.getLatestPlaylistCreatedDate();
        if (playlistDate == null || !playlistDate.equals(newPlaylistDate)) {
            playlistDate = newPlaylistDate;
            entryRepository.clearPriority();
            String raw = playlistRepository.getLatestPlaylist();

            if (raw == null || raw.isEmpty()) {
                Log.e("SAFE_GUARD", "⚠️ No playlist yet → skip prioritize()");
                return;
            }

            List<Long> playlist = stringToLongList(raw);
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

        private void checkContentReady(WebView view, int attempt) {
            if (attempt > 10) {
                Log.e("WEBVIEW_FAIL", "❌ Gave up waiting → force extract");
                extractHtml(view);
                return;
            }

            view.evaluateJavascript(
                    "(function() {" +
                            "  let article = document.querySelector('article');" +
                            "  return article ? article.innerText.length : 0;" +
                            "})();",
                    result -> {
                        try {
                            int length = Integer.parseInt(result.replace("\"", ""));

                            if (length > 400) {
                                Log.e("WEBVIEW_READY", "✅ READY → extract");
                                extractHtml(view);
                            } else {
                                Log.e("WEBVIEW_WAIT", "⏳ retry " + attempt);
                                new Handler(Looper.getMainLooper())
                                        .postDelayed(() -> checkContentReady(view, attempt + 1), 500);
                            }

                        } catch (Exception e) {
                            new Handler(Looper.getMainLooper())
                                    .postDelayed(() -> checkContentReady(view, attempt + 1), 500);
                        }
                    }
            );
        }

        private void extractHtml(WebView view) {
            view.evaluateJavascript(
                    "(function() {return document.getElementsByTagName('html')[0].outerHTML;})();",
                    value -> {
                        JsonReader reader = new JsonReader(new StringReader(value));
                        reader.setLenient(true);
                        StringBuilder contentCollector = new StringBuilder();
                        try {
                            if (reader.peek() == JsonToken.STRING) {
                                String html = reader.nextString();

                                Log.e("HTML_SIZE", "WebView HTML length = " + (html != null ? html.length() : 0));
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
                                                for (Element child : element.children())
                                                    if (tags.contains(child.tagName()))
                                                        hasTagChild = true;
                                                if (!hasTagChild) {
                                                    String text = element.text().trim();
                                                    if (text.length() > 1) {
                                                        if (contentCollector.length() > 0)
                                                            contentCollector.append(delimiter);
                                                        contentCollector.append(text);
                                                    } else element.remove();
                                                }
                                            }
                                        }
                                        String finalFullText = contentCollector.toString();
                                        if (finalFullText.length() < 200 || finalFullText.toLowerCase().contains("please enable javascript")) {
                                            entryRepository.updateContent("", currentIdInProgress);
                                        } else {
                                            String contentWithTitle = currentTitle + delimiter + finalFullText;

                                            // 🔥 ADD HERE
                                            if (currentIdInProgress == -1) {
                                                Log.e("SAFE_GUARD", "❌ Skip save: invalid ID");
                                                return;
                                            }

                                            if (webViewSafeId == -1) {
                                                Log.e("SAFE_GUARD", "❌ WebView save skipped (invalid ID)");
                                                return;
                                            }

                                            entryRepository.updateOriginalHtml(doc.html(), webViewSafeId);
                                            entryRepository.updateHtml(doc.html(), webViewSafeId);
                                            entryRepository.updateContent(contentWithTitle, webViewSafeId);

                                            Log.e("PIPELINE_TRACE", "✅ CONTENT SAVED ID = " + currentIdInProgress);
                                            Log.e("PIPELINE_TRACE", "🔥 trigger AI for ID = " + currentIdInProgress);
                                            runAiPipelineNow();
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
                    }
            );
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (url == null) return false;

            Log.e("WEBVIEW_NAV", "Loading URL = " + url);

            return false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            if (webViewExpectedUrl == null || !url.equals(webViewExpectedUrl)) {
                Log.e("WEBVIEW_DEBUG", "❌ IGNORE MISMATCH URL = " + url +
                        " | expected = " + webViewExpectedUrl);
                return;
            }
            if (extractionInProgress) {
                Log.e("WEBVIEW_DEBUG", "onPageFinished URL = " + url + " | currentId = " + currentIdInProgress);
                handler.postDelayed(() -> checkContentReady(view, 0), delayTime * 1000L);
            }
            Log.e(TAG, "🌐 WebView finished loading: " + url);
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

        if (genreIds == null || genreIds.isEmpty()) {
            Log.e("SAFE_GUARD", "⚠️ playlist is null or empty");
            return list;
        }

        String[] array = genreIds.split(",");

        for (String s : array) {
            if (!s.isEmpty()) list.add(Long.parseLong(s));
        }

        return list;
    }

    public boolean isLocked() { return isLockedByTtsPlayer; }
    public String getCurrentLanguage() { return currentLanguage; }

    private void runAiPipelineNow() {
        new Thread(() -> {
            synchronized (aiLock) {
                try {
                    boolean doSummarize = sharedPreferencesRepository.isSummarizationEnabled();
                    boolean doTranslate = sharedPreferencesRepository.getAutoTranslate();
                    boolean doClean = sharedPreferencesRepository.isAiCleaningEnabled();

                    while (true) {

                        Entry entry = entryRepository.getNextEntryForAiProcessing(
                                sharedPreferencesRepository.getCurrentReadingEntryId(),
                                doSummarize,
                                doClean,
                                doTranslate
                        );

                        if (entry == null) break;

                        String html;

                        if (entry.isAiCleaned() && entry.getHtml() != null) {
                            html = entry.getHtml();
                        } else {
                            html = entry.getOriginalHtml();
                        }
                        if (html == null || html.isEmpty()) continue;
                        int length = html.length();
                        boolean useSingleCall = length < 15000;

                        String plainText = textUtil.extractHtmlContent(html, delimiter);

                        String text = plainText;

                        boolean singleCallSuccess = false;

                        if (useSingleCall) {
                            try {
                                Log.e("AI_SINGLE", "🚀 USING SINGLE CALL ID = " + entry.getId());

                                String json = translationRepository.processMultiTask(
                                                html,
                                                doClean,
                                                doTranslate,
                                                doSummarize,
                                                sharedPreferencesRepository.getDefaultTranslationLanguage()
                                        ).timeout(90, java.util.concurrent.TimeUnit.SECONDS)
                                        .blockingGet();

                                Log.e("AI_JSON_RAW", json);

                                if (json != null && !json.isEmpty()) {

                                    json = json.trim();

                                    if (json.startsWith("```")) {
                                        json = json.replace("```json", "")
                                                .replace("```", "")
                                                .trim();
                                    }

                                    org.json.JSONObject obj = new org.json.JSONObject(json);

                                    if (doClean && obj.has("cleaned_html")) {
                                        String cleaned = obj.getString("cleaned_html");
                                        entryRepository.updateHtml(cleaned, entry.getId());

                                        String cleanedText = textUtil.extractHtmlContent(cleaned, delimiter);
                                        entryRepository.updateContent(entry.getTitle() + delimiter + cleanedText, entry.getId());

                                        entryRepository.markAsAiCleaned(entry.getId());
                                    }

                                    if (doSummarize && obj.has("summary")) {
                                        entryRepository.updateSummary(obj.getString("summary"), entry.getId());
                                        entryRepository.markAsAiSummarized(entry.getId(), false);
                                    }

                                    if (doTranslate && obj.has("translated_html")) {
                                        String translatedHtml = obj.getString("translated_html");

                                        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(translatedHtml);
                                        String translatedTitle = doc.select("h1").text();

                                        if (translatedTitle == null || translatedTitle.isEmpty()) {
                                            translatedTitle = translationRepository.translateText(
                                                    entry.getTitle(),
                                                    "auto",
                                                    sharedPreferencesRepository.getDefaultTranslationLanguage()
                                            ).blockingGet();
                                        }

                                        if (translatedTitle == null || translatedTitle.isEmpty()) {
                                            translatedTitle = entry.getTitle();
                                        }

                                        entryRepository.updateTranslatedText(
                                                translatedHtml, // 🔥 STORE FULL HTML
                                                entry.getId()
                                        );

                                        entryRepository.updateTranslatedTitle(translatedTitle, entry.getId());
                                    }

                                    boolean hasClean = !doClean || obj.has("cleaned_html");
                                    boolean hasSummary = !doSummarize || obj.has("summary");
                                    boolean hasTranslate = !doTranslate || obj.has("translated_html");

                                    singleCallSuccess = hasClean && hasSummary && hasTranslate;
                                }

                            } catch (Exception e) {
                                Log.e("AI_SINGLE_FAIL", "Fallback to old pipeline", e);
                            }
                        }

                        if (singleCallSuccess) {

                            // 🔥 RELOAD ENTRY AFTER DB UPDATE (THIS IS THE REAL FIX)
                            entry = entryRepository.getEntryById(entry.getId());

                            if (webViewCallback != null) {
                                webViewCallback.finishedSetup(); // 🔥 triggers UI refresh
                            }

                            continue;
                        }

                        // CLEAN
                        if (doClean) {
                            try {
                                String cleanedHtml = translationRepository
                                        .cleanArticleHtml(html)
                                        .blockingGet();

                                if (cleanedHtml != null && !cleanedHtml.isEmpty()) {

                                    if (cleanedHtml.contains("<p") && cleanedHtml.contains("</p>")) {
                                        entryRepository.updateHtml(cleanedHtml, entry.getId());
                                    } else {
                                        Log.e("CLEANING", "❌ INVALID HTML → SKIPPED");
                                    }

                                    String cleanedText = textUtil.extractHtmlContent(cleanedHtml, delimiter);
                                    String finalContent = entry.getTitle() + delimiter + cleanedText;

                                    entryRepository.updateContent(finalContent, entry.getId());
                                }

                            } catch (Exception ignored) {}

                            entryRepository.markAsAiCleaned(entry.getId());
                        }

                        if (doSummarize) {
                            try {
                                String result = translationRepository.summarizeText(
                                        text,
                                        sharedPreferencesRepository.getAiSummaryLength(),
                                        sharedPreferencesRepository.getDefaultTranslationLanguage()
                                ).blockingGet();

                                if (result != null && !result.isEmpty()) {
                                    entryRepository.updateSummary(result, entry.getId());
                                }

                            } catch (Exception ignored) {}

                            entryRepository.markAsAiSummarized(entry.getId(), false);
                        }

                        if (doTranslate) {
                            Log.e("TRANSLATE_DEBUG", "🔥 START ID = " + entry.getId());
                            Log.e("TRANSLATE_DEBUG", "TARGET = " + sharedPreferencesRepository.getDefaultTranslationLanguage());
                            try {
                                String translated = translationRepository.translateText(
                                        text,
                                        "auto",
                                        sharedPreferencesRepository.getDefaultTranslationLanguage()
                                ).blockingGet();

                                if (translated != null && !translated.isEmpty()) {
                                    String translatedTitle = translationRepository.translateText(
                                            entry.getTitle(),
                                            "auto",
                                            sharedPreferencesRepository.getDefaultTranslationLanguage()
                                    ).blockingGet();

                                    entryRepository.updateTranslatedTitle(translatedTitle, entry.getId());

                                    //String finalTranslated = translatedTitle + delimiter + translated;
                                    //entryRepository.updateTranslatedText(finalTranslated, entry.getId());
                                    entryRepository.updateTranslatedText(
                                            "<p>" + translated.replace(delimiter, "</p><p>") + "</p>",
                                            entry.getId()
                                    );
                                }
                                Log.e("TRANSLATE_DEBUG", "✅ DONE ID = " + entry.getId());
                                Log.e("TRANSLATE_DEBUG", "RESULT = " + translated);

                            } catch (Exception ignored) {}

                            // ✅ ALWAYS mark translation done (CRITICAL)
                            /*entryRepository.updateTranslatedText(
                                    entry.getTitle() + delimiter + text,
                                    entry.getId()
                            );*/
                        }
                        // 🔥 FORCE REFRESH FROM DB (CRITICAL)
                        entry = entryRepository.getEntryById(entry.getId());

                        Log.e("DEBUG_AI", "AFTER UPDATE ID = " + entry.getId()
                                + " | clean=" + entry.isAiCleaned()
                                + " | summary=" + entry.isAiSummarized()
                                + " | translated=" + entry.getTranslated());
                    }
                } catch (Exception e) {
                    Log.e("PIPELINE_TRACE", "Direct summarization failed", e);
                }
            }
        }).start();
    }
}
