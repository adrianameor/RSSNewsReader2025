package com.adriana.newscompanion.service.tts;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkContinuation;
import androidx.work.WorkManager;

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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
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

    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36";

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



    @Inject
    public TtsExtractor(@ApplicationContext Context context, TtsPlaylist ttsPlaylist, EntryRepository entryRepository, FeedRepository feedRepository, PlaylistRepository playlistRepository, TextUtil textUtil, SharedPreferencesRepository sharedPreferencesRepository) {
        this.context = context;
        this.ttsPlaylist = ttsPlaylist;
        this.entryRepository = entryRepository;
        this.feedRepository = feedRepository;
        this.playlistRepository = playlistRepository;
        this.textUtil = textUtil;
        this.sharedPreferencesRepository = sharedPreferencesRepository;
    }

    public void extractAllEntries() {
        if (extractionInProgress) return;

        Entry entry = entryRepository.getEmptyContentEntry();

        if (entry == null && !failedIds.isEmpty()) {
            long retryId = failedIds.get(0); // peek first
            int attempts = retryCountMap.getOrDefault(retryId, 0);
            if (attempts < MAX_RETRIES) {
                failedIds.remove(0);
                retryCountMap.put(retryId, attempts + 1);
                entry = entryRepository.getEntryById(retryId);
            } else {
                // Permanent failure: mark as processed with empty content
                failedIds.remove(0);
                retryCountMap.remove(retryId);
                entryRepository.updateContent("", retryId);
                Log.w(TAG, "Permanently failed extraction for ID: " + retryId);
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

            Log.d(TAG, "Processing Article ID: " + currentIdInProgress);
            if (currentLink.contains("video")) {
                entryRepository.updateContent("", currentIdInProgress);
                Log.d(TAG, "Skipped video URL: " + currentLink);
                resetFlagsAndContinue();
                return;
            }
            new Thread(() -> {
                try {
                    URL url = new URL(currentLink);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setRequestProperty("User-Agent", USER_AGENT);
                    connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(15000);
                    connection.setInstanceFollowRedirects(true);

                    int responseCode = connection.getResponseCode();
                    Log.d(TAG, "Fetching: " + currentLink + " | Status: " + responseCode);

                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                        StringBuilder htmlBuilder = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            htmlBuilder.append(line);
                        }
                        reader.close();

                        String html = htmlBuilder.toString();
                        if (html != null && !html.isEmpty()) {
                            Readability4JExtended readability4J = new Readability4JExtended(currentLink, html);
                            Article article = readability4J.parse();
                            if (article.getContentWithUtf8Encoding() != null) {
                                Document doc = Jsoup.parse(article.getContentWithUtf8Encoding());
                                doc.select("h1").remove();
                                doc.select("img").attr("style", "border-radius: 5px; width: 100%; margin-left:0");
                                doc.select("figure").attr("style", "width: 100%; margin-left:0");
                                doc.select("iframe").attr("style", "width: 100%; margin-left:0");

                                List<String> tags = Arrays.asList("h2", "h3", "h4", "h5", "h6", "p", "td", "pre", "th", "li", "figcaption", "blockquote", "section");
                                StringBuilder contentCollector = new StringBuilder();
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
                            } else {
                                failedIds.add(currentIdInProgress);
                            }
                        } else {
                            failedIds.add(currentIdInProgress);
                        }
                    } else {
                        Log.e(TAG, "HTTP Error " + responseCode + " for URL: " + currentLink);
                        failedIds.add(currentIdInProgress);
                    }
                    connection.disconnect();
                } catch (Exception e) {
                    Log.e(TAG, "Error processing " + currentLink + ": " + e.getMessage());
                    failedIds.add(currentIdInProgress);
                } finally {
                    resetFlagsAndContinue();
                }
            }).start();
        } else {
            Log.d(TAG, "No more articles in queue.");
            triggerAiPipelineChain();
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
