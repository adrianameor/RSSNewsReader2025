package com.adriana.newscompanion.service.tts;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.ContextCompat;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import com.adriana.newscompanion.data.entry.Entry;
import com.adriana.newscompanion.data.entry.EntryRepository;
import com.adriana.newscompanion.data.feed.FeedRepository;
import com.adriana.newscompanion.data.playlist.PlaylistRepository;
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
    private long currentIdInProgress;
    private boolean extractionInProgress;
    private int delayTime;
    private TtsPlayerListener ttsCallback;
    private TtsPlaylist ttsPlaylist;
    private WebViewListener webViewCallback;
    private Date playlistDate;
    public final String delimiter = "--####--";
    private final List<Long> failedIds = new ArrayList<>();
    private final HashMap<Long, Integer> retryCountMap = new HashMap<>();
    private final int MAX_RETRIES = 5;
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
        Log.d(TAG, "extractAllEntries called | extractionInProgress = " + extractionInProgress);

        if (extractionInProgress && currentIdInProgress == -1) {
            Log.w(TAG, "Recovery: extractionInProgress = true but currentIdInProgress == -1 → Resetting flag.");
            extractionInProgress = false;
        }

        Entry entry = entryRepository.getEmptyContentEntry();

        if (entry == null && !failedIds.isEmpty()) {
            long retryId = failedIds.remove(0);
            int attempts = retryCountMap.getOrDefault(retryId, 0);

            if (attempts < MAX_RETRIES) {
                retryCountMap.put(retryId, attempts + 1);
                Log.d(TAG, "Retrying failed article ID: " + retryId + " | Attempt " + (attempts + 1));
                entry = entryRepository.getEntryById(retryId);
            } else {
                Log.w(TAG, "Max retries reached for article ID: " + retryId);
                extractAllEntries();
                return;
            }
        }

        if (entry != null) {
            Log.d(TAG, "Next entry: id=" + entry.getId() + ", title=" + entry.getTitle() + ", priority=" + entry.getPriority());
            if (!extractionInProgress) {
                Log.d(TAG, "extracting...");
                extractionInProgress = true;
                currentIdInProgress = entry.getId();
                currentLink = entry.getLink();
                currentTitle = entry.getTitle();
                delayTime = feedRepository.getDelayTimeById(entry.getFeedId());
                ContextCompat.getMainExecutor(context).execute(new Runnable() {
                    @Override
                    public void run() {
                        webView.loadUrl(currentLink);
                        Log.d("Test url",currentLink);
                    }
                });
                lastExtractStart = System.currentTimeMillis();

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (extractionInProgress && System.currentTimeMillis() - lastExtractStart > 30000) {
                        Log.w(TAG, "[Timeout] Extraction stuck >30s, resetting manually");
                        failedIds.add(currentIdInProgress);
                        currentIdInProgress = -1;
                        extractionInProgress = false;
                        extractAllEntries();
                    }
                }, 30000);
            }
        }else {
            Log.d(TAG, "No entry returned by getEmptyContentEntry()");
        }
    }

    private void translateHtml(String html, String content, final long currentIdInProgress, String currentTitle) {
        String sourceLanguage = textUtil.identifyLanguageRx(content).blockingGet();
        String targetLanguage = sharedPreferencesRepository.getDefaultTranslationLanguage();
        setCurrentLanguage(targetLanguage, false);

        if (currentTitle != null && !sourceLanguage.equalsIgnoreCase(targetLanguage)) {
            Log.d(TAG, "translateHtml: Translating ID " + currentIdInProgress + " from " + sourceLanguage + " to " + targetLanguage);

            // 1. Create two separate background jobs: one for the title, one for the body.
            Single<String> titleTranslationJob = textUtil.translateText(sourceLanguage, targetLanguage, currentTitle);
            Single<String> bodyTranslationJob = translateBodyRobustly(html, sourceLanguage, targetLanguage, currentIdInProgress);

            // 2. Use Single.zip to run both jobs and wait until BOTH are complete.
            disposables.add(Single.zip(
                            titleTranslationJob,
                            bodyTranslationJob,
                            // 3. This 'BiFunction' runs only when both jobs have succeeded.
                            (translatedTitle, translatedBodyHtml) -> {
                                Log.d("TranslationDebug", "TTSExtractor: Translation successful for ID: " + currentIdInProgress);
                                Log.d("TranslationDebug", "TTSExtractor: Received Translated Title: '" + translatedTitle + "'");

                                // 4. Save the results to the database together.
                                entryRepository.updateTranslatedTitle(translatedTitle, currentIdInProgress);
                                entryRepository.updateHtml(translatedBodyHtml, currentIdInProgress);

                                // 5. Extract and save plain text for search and summary.
                                org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(translatedBodyHtml);
                                String translatedBodyText = textUtil.extractHtmlContent(doc.body().html(), delimiter);
                                entryRepository.updateTranslatedSummary(translatedBodyText, currentIdInProgress);
                                entryRepository.updateTranslated(translatedTitle + delimiter + translatedBodyText, currentIdInProgress);

                                return true; // Return a value to satisfy the BiFunction
                            })
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                            success -> {
                                // This runs after everything has been saved.
                                Log.d(TAG, "Successfully translated and saved article ID: " + currentIdInProgress);
                                setCurrentLanguage(targetLanguage, true);
                                if (!sharedPreferencesRepository.hasTranslationToggle(currentIdInProgress)) {
                                    sharedPreferencesRepository.setIsTranslatedView(currentIdInProgress, true);
                                }
                            },
                            error -> {
                                // This runs if either the title or body translation fails.
                                Log.e(TAG, "Failed to zip and translate article ID: " + currentIdInProgress, error);
                                if (!failedIds.contains(currentIdInProgress)) {
                                    failedIds.add(currentIdInProgress);
                                }
                            }
                    )
            );
        }
    }

    // This is the corrected helper method that performs the robust "chunking" translation.
    private Single<String> translateBodyRobustly(String originalHtml, String sourceLang, String targetLang, long entryId) {
        return Single.create(emitter -> {
            try {
                Document doc = Jsoup.parse(originalHtml);
                List<Element> elementsToTranslate = new ArrayList<>();
                // Select all major text-containing block elements.
                for (Element element : doc.select("p, h2, h3, h4, h5, h6, li, blockquote, figcaption, td, th")) {
                    if (!element.text().trim().isEmpty()) {
                        elementsToTranslate.add(element);
                    }
                }

                if (elementsToTranslate.isEmpty()) {
                    // If there are no structured elements, try to translate the whole body as a last resort.
                    Log.w(TAG, "No structured text elements found for chunking on entry ID " + entryId + ". Attempting whole body translation.");
                    // THIS IS THE FIX: Use the correct 'textUtil' field from the class.
                    Disposable fallbackDisposable = textUtil.translateHtml(doc.body().html(), sourceLang, targetLang, progress -> {})
                            .subscribe(emitter::onSuccess, emitter::onError);
                    disposables.add(fallbackDisposable); // Manage the disposable
                    return;
                }

                // Create a list of translation jobs, one for each small chunk.
                List<Single<String>> translationSingles = new ArrayList<>();
                for (Element element : elementsToTranslate) {
                    translationSingles.add(
                            // THIS IS THE FIX: Use the correct 'textUtil' field from the class.
                            textUtil.translateHtml(element.html(), sourceLang, targetLang, progress -> {})
                                    .subscribeOn(Schedulers.io()) // Allow each small job to run in parallel.
                    );
                }

                // Zip all the small jobs together.
                // THIS IS THE FIX: Capture the disposable to fix the warning.
                Disposable zipDisposable = Single.zip(translationSingles, translatedChunks -> {
                            // This runs after all chunks have been successfully translated.
                            for (int i = 0; i < translatedChunks.length; i++) {
                                // Replace the original content of each element with its translated version.
                                elementsToTranslate.get(i).html((String) translatedChunks[i]);
                            }
                            // Return the fully re-assembled HTML of the article body.
                            return doc.body().html();
                        })
                        .subscribe(
                                emitter::onSuccess, // Pass the final, re-assembled HTML to the outer Single.
                                emitter::onError    // Or, if any chunk fails, pass the error.
                        );

                // Add the disposable to our manager.
                disposables.add(zipDisposable);

            } catch (Exception e) {
                emitter.onError(e); // Catch any initial Jsoup parsing errors.
            }
        });
    }

    public void setCallback(TtsPlayerListener callback) {
        this.ttsCallback = callback;
    }

    public void setCallback(WebViewListener callback) {
        this.webViewCallback = callback;
    }

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

            boolean loop = true;
            while (loop) {
                index += 1;
                priority += 1;
                if (index < playlist.size()) {
                    long id = playlist.get(index);
                    entryRepository.updatePriority(priority, id);
                } else {
                    loop = false;
                }
            }
        }
        extractAllEntries();
    }

    public class WebClient extends WebViewClient {

        private final Handler handler = new Handler();

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            view.loadUrl(url);
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            Log.d(TAG, "[onPageFinished] triggered for: " + url);
            super.onPageFinished(view, url);
            if (extractionInProgress && webView.getProgress() == 100) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        webView.evaluateJavascript("(function() {return document.getElementsByTagName('html')[0].outerHTML;})();", new ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(final String value) {
                                Log.d(TAG, "Receiving value...");
                                JsonReader reader = new JsonReader(new StringReader(value));
                                reader.setLenient(true);
                                boolean stopExtracting = false;
                                StringBuilder content = new StringBuilder();
                                try {
                                    if (reader.peek() == JsonToken.STRING) {
                                        String html = reader.nextString();
                                        boolean isTranslated = sharedPreferencesRepository.getIsTranslatedView(currentIdInProgress);
                                        if (html != null) {
                                            Readability4JExtended readability4J = new Readability4JExtended(currentLink, html);
                                            Article article = readability4J.parse();

                                            // --- THIS IS THE FIX ---
                                            // We no longer add the delimiter here. We only add the title.
                                            if (currentTitle != null && !currentTitle.isEmpty()) {
                                                content.append(currentTitle);
                                            }

                                            if (article.getContentWithUtf8Encoding() != null) {
                                                Document doc = Jsoup.parse(article.getContentWithUtf8Encoding());
                                                // ... (all your existing doc.select()... code is correct and remains unchanged)
                                                doc.select("img").removeAttr("width");
                                                doc.select("img").removeAttr("height");
                                                doc.select("img").removeAttr("sizes");
                                                doc.select("img").removeAttr("srcset");
                                                doc.select("h1").remove();
                                                doc.select("img").attr("style", "border-radius: 5px; width: 100%; margin-left:0");
                                                doc.select("figure").attr("style", "width: 100%; margin-left:0");
                                                doc.select("iframe").attr("style", "width: 100%; margin-left:0");

                                                List<String> tags = Arrays.asList("h2", "h3", "h4", "h5", "h6", "p", "td", "pre", "th", "li", "figcaption", "blockquote", "section");
                                                for (Element element : doc.getAllElements()) {
                                                    if (tags.contains(element.tagName())) {
                                                        boolean sameContent = false;
                                                        for (Element child : element.children()) {
                                                            if (tags.contains(child.tagName())) {
                                                                sameContent = true;
                                                            }
                                                        }
                                                        if (!sameContent) {
                                                            String text = element.text().trim();
                                                            if (!text.isEmpty() && text.length() > 1) {
                                                                // NEW, CORRECTED LOGIC:
                                                                // Always add a delimiter *before* adding a new piece of content.
                                                                // This ensures it's only ever between pieces of text.
                                                                if (content.length() > 0) {
                                                                    content.append(delimiter);
                                                                }
                                                                content.append(text);
                                                            } else {
                                                                element.remove();
                                                            }
                                                        }
                                                    }
                                                }
                                                // --- END OF FIX ---

                                                entryRepository.updateHtml(doc.html(), currentIdInProgress);

                                                if (entryRepository.getOriginalHtmlById(currentIdInProgress) == null) {
                                                    entryRepository.updateOriginalHtml(doc.html(), currentIdInProgress);
                                                    entryRepository.updateContent(content.toString(), currentIdInProgress);
                                                }

                                                if (sharedPreferencesRepository.getAutoTranslate()) {
                                                    translateHtml(doc.html(), content.toString(), currentIdInProgress, currentTitle);
                                                }

                                                if (content.toString().isEmpty()) {
                                                    stopExtracting = true;
                                                }

                                                if (currentIdInProgress == ttsPlaylist.getPlayingId()) {
                                                    if (ttsCallback != null) {
                                                        String lang = currentLanguage != null ? currentLanguage : "en";

                                                        Entry entry = entryRepository.getEntryById(currentIdInProgress);
                                                        String contentToRead;

                                                        if (isTranslated && entry != null && entry.getTranslated() != null && !entry.getTranslated().trim().isEmpty()) {
                                                            contentToRead = entry.getTranslated();
                                                            Log.d(TAG, "[TtsExtractor] Using translated content for TTS");
                                                        } else {
                                                            contentToRead = entry != null ? entry.getContent() : "";
                                                            Log.d(TAG, "[TtsExtractor] Using original content for TTS");
                                                        }

                                                        ttsCallback.extractToTts(contentToRead, lang);
                                                        ttsCallback = null;
                                                    }
                                                } else {
                                                    Log.d(TAG, "not playing this ID");
                                                }
                                            } else {
                                                Log.d(TAG, "Empty content");
                                            }
                                        } else {
                                            if (webViewCallback != null) {
                                                webViewCallback.makeSnackbar("Failed to retrieve the html");
                                            }
                                            Log.d(TAG, "No html found!");
                                        }
                                    } else {
                                        Log.e(TAG, "[onReceiveValue] Unexpected JSON token");
                                        if (webViewCallback != null) {
                                            webViewCallback.makeSnackbar("Extraction failed");
                                        }
                                        Log.d(TAG, "Error peeking reader!");
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "[onReceiveValue] Exception during extraction", e);
                                    failedIds.add(currentIdInProgress);
                                    Log.d(TAG, e.getMessage() != null ? e.getMessage() : "Unknown exception");
                                    e.printStackTrace();
                                } finally {
                                    Log.d(TAG, "[onReceiveValue] Finally block: resetting flags for ID = " + currentIdInProgress);

                                    if (stopExtracting || content.toString().isEmpty()) {
                                        Log.w(TAG, "Extraction failed for ID: " + currentIdInProgress);
                                        if (!failedIds.contains(currentIdInProgress)) {
                                            failedIds.add(currentIdInProgress);
                                        }
                                    }

                                    if (webViewCallback != null) {
                                        Log.d(TAG, "Extraction complete. Notifying UI via finishedSetup()");
                                        webViewCallback.finishedSetup();
                                        webViewCallback = null;
                                    }

                                    currentIdInProgress = -1;
                                    extractionInProgress = false;
                                    // Call extractAllEntries() without delay from the main thread to ensure proper continuation
                                    new Handler(Looper.getMainLooper()).post(TtsExtractor.this::extractAllEntries);
                                }
                            }
                        });
                    }
                }, delayTime * 1000L);
            } else {
                Log.d(TAG, "loading WebView");
            }
        }
    }

    public void setCurrentLanguage(String lang, boolean lock) {
        Log.d("TtsExtractor", "[setCurrentLanguage] REQUESTED lang = " + lang + ", lock = " + lock + " | current = " + currentLanguage + ", isLocked = " + isLockedByTtsPlayer);

        Log.d("TtsExtractor", Log.getStackTraceString(new Throwable()));

        if (!isLockedByTtsPlayer || lock) {
            Log.d("TtsExtractor", "Language set to: " + lang + " | lock=" + lock);
            this.currentLanguage = lang;
            isLockedByTtsPlayer = lock;
        } else {
            Log.d("TtsExtractor", "Ignored language override to: " + lang + " due to lock");
        }

        Log.d("TtsExtractor", "Language set to: " + lang + " | lock=" + lock + " | isLocked=" + isLockedByTtsPlayer);
    }

    public List<Long> stringToLongList(String genreIds) {
        List<Long> list = new ArrayList<>();

        String[] array = genreIds.split(",");

        for (String s : array) {
            if (!s.isEmpty()) {
                list.add(Long.parseLong(s));
            }
        }
        return list;
    }

    public boolean isLocked() {
        return isLockedByTtsPlayer;
    }

    public String getCurrentLanguage() {
        return currentLanguage;
    }
}