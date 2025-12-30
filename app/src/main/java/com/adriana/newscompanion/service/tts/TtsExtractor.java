package com.adriana.newscompanion.service.tts;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.webkit.WebViewClient;

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
    private long lastExtractStart = 0;

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

        ContextCompat.getMainExecutor(context).execute(new Runnable() {
            @Override
            public void run() {
                webView = new WebView(context);
                webView.setWebViewClient(new WebClient());
                webView.clearCache(true);
                webView.getSettings().setJavaScriptEnabled(true);
                webView.getSettings().setDomStorageEnabled(true);
            }
        });
    }

    public void extractAllEntries() {
        Log.d(TAG, "extractAllEntries | InProgress: " + extractionInProgress + " | ID: " + currentIdInProgress);

        if (extractionInProgress && currentIdInProgress == -1) {
            extractionInProgress = false;
        }

        Entry entry = entryRepository.getEmptyContentEntry();

        if (entry == null && !failedIds.isEmpty()) {
            long retryId = failedIds.remove(0);
            int attempts = retryCountMap.getOrDefault(retryId, 0);

            if (attempts < MAX_RETRIES) {
                retryCountMap.put(retryId, attempts + 1);
                Log.d(TAG, "Retrying stuck article ID: " + retryId + " (Attempt " + (attempts + 1) + ")");
                entry = entryRepository.getEntryById(retryId);
            } else {
                Log.w(TAG, "Max retries reached for ID: " + retryId + ". Giving up.");
                extractAllEntries();
                return;
            }
        }

        if (entry != null) {
            if (!extractionInProgress) {
                extractionInProgress = true;
                currentIdInProgress = entry.getId();
                currentLink = entry.getLink();
                currentTitle = entry.getTitle();
                delayTime = feedRepository.getDelayTimeById(entry.getFeedId());
                
                Log.d(TAG, "--- STARTING EXTRACTION ---");
                Log.d(TAG, "ID: " + currentIdInProgress + " | Title: " + currentTitle);
                Log.d(TAG, "URL: " + currentLink);
                
                ContextCompat.getMainExecutor(context).execute(() -> webView.loadUrl(currentLink));
                lastExtractStart = System.currentTimeMillis();

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (extractionInProgress && System.currentTimeMillis() - lastExtractStart > 45000) {
                        Log.e(TAG, "!!! WATCHDOG TIMEOUT !!! Article ID: " + currentIdInProgress + " took >45s. Resetting.");
                        failedIds.add(currentIdInProgress);
                        currentIdInProgress = -1;
                        extractionInProgress = false;
                        extractAllEntries();
                    }
                }, 45000);
            }
        } else {
            Log.d(TAG, "All work finished. Triggering AI Pipeline.");
            triggerAiPipelineChain();
        }
    }

    private void triggerAiPipelineChain() {
        WorkManager workManager = WorkManager.getInstance(context);
        String uniqueWorkName = "AI_CONTENT_PIPELINE";
        WorkContinuation continuation = null;

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
            Log.d(TAG, "AI Pipeline enqueued.");
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
            
            // Requirement 4.3: Redirect Check
            if (url != null && currentLink != null) {
                Uri expected = Uri.parse(currentLink);
                Uri actual = Uri.parse(url);
                if (!expected.getHost().equalsIgnoreCase(actual.getHost())) {
                    Log.w(TAG, "[Intelligent Check] Redirect Detected! Target: " + actual.getHost() + " | Expected: " + expected.getHost());
                }
            }

            if (extractionInProgress && webView.getProgress() == 100) {
                handler.postDelayed(() -> webView.evaluateJavascript("(function() {return document.getElementsByTagName('html')[0].outerHTML;})();", value -> {
                    JsonReader reader = new JsonReader(new StringReader(value));
                    reader.setLenient(true);
                    StringBuilder contentTextCollector = new StringBuilder();
                    try {
                        if (reader.peek() == JsonToken.STRING) {
                            String html = reader.nextString();
                            if (html != null) {
                                Readability4JExtended readability4J = new Readability4JExtended(currentLink, html);
                                Article article = readability4J.parse();
                                
                                String pageTitle = article.getTitle();
                                Log.d(TAG, "[Intelligent Check] Page Title: " + pageTitle + " | RSS Title: " + currentTitle);

                                if (article.getContentWithUtf8Encoding() != null) {
                                    Document doc = Jsoup.parse(article.getContentWithUtf8Encoding());
                                    
                                    doc.select("img").removeAttr("width").removeAttr("height").removeAttr("sizes").removeAttr("srcset");
                                    doc.select("h1").remove();
                                    doc.select("img").attr("style", "border-radius: 5px; width: 100%; margin-left:0");
                                    doc.select("figure").attr("style", "width: 100%; margin-left:0");
                                    doc.select("iframe").attr("style", "width: 100%; margin-left:0");

                                    List<String> tags = Arrays.asList("h2", "h3", "h4", "h5", "h6", "p", "td", "pre", "th", "li", "figcaption", "blockquote", "section");
                                    for (Element element : doc.getAllElements()) {
                                        if (tags.contains(element.tagName())) {
                                            boolean hasChild = false;
                                            for (Element child : element.children()) if (tags.contains(child.tagName())) hasChild = true;
                                            if (!hasChild) {
                                                String text = element.text().trim();
                                                if (text.length() > 1) {
                                                    if (contentTextCollector.length() > 0) contentTextCollector.append(delimiter);
                                                    contentTextCollector.append(text);
                                                } else element.remove();
                                            }
                                        }
                                    }
                                    
                                    // Requirement 4.1: Heuristic Quality Check
                                    String finalFullText = contentTextCollector.toString();
                                    Log.d(TAG, "[Heuristic] Extracted Text Length: " + finalFullText.length());
                                    
                                    if (finalFullText.length() < 250) {
                                        Log.w(TAG, "[Heuristic] ALERT: Content suspicious or incomplete (Too short). ID: " + currentIdInProgress);
                                        // Potential failure point - we won't mark as complete yet to trigger a retry
                                        failedIds.add(currentIdInProgress);
                                    } else {
                                        entryRepository.updateHtml(doc.html(), currentIdInProgress);
                                        if (entryRepository.getOriginalHtmlById(currentIdInProgress) == null) {
                                            entryRepository.updateOriginalHtml(doc.html(), currentIdInProgress);
                                            entryRepository.updateContent(finalFullText, currentIdInProgress);
                                        }
                                        Log.d(TAG, "✓ SUCCESS: ID " + currentIdInProgress + " processed successfully.");
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "!!! EXTRACTION ERROR !!! ID: " + currentIdInProgress, e);
                        failedIds.add(currentIdInProgress);
                    } finally {
                        if (webViewCallback != null) {
                            webViewCallback.finishedSetup();
                            webViewCallback = null;
                        }
                        currentIdInProgress = -1;
                        extractionInProgress = false;
                        new Handler(Looper.getMainLooper()).post(TtsExtractor.this::extractAllEntries);
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
