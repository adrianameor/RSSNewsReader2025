package com.adriana.newscompanion.data.feed;

import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import com.adriana.newscompanion.data.entry.Entry;
import com.adriana.newscompanion.data.entry.EntryRepository;
import com.adriana.newscompanion.data.history.History;
import com.adriana.newscompanion.data.history.HistoryRepository;
import com.adriana.newscompanion.service.rss.RssFeed;
import com.adriana.newscompanion.service.rss.RssItem;
import com.adriana.newscompanion.service.rss.RssReader;
import com.adriana.newscompanion.service.rss.RssWorkManager;
import com.adriana.newscompanion.data.sharedpreferences.SharedPreferencesRepository;
import com.adriana.newscompanion.service.tts.TtsExtractor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;
import javax.inject.Provider;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.annotations.NonNull;
import io.reactivex.rxjava3.core.CompletableObserver;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import com.adriana.newscompanion.service.util.TextUtil;
import com.adriana.newscompanion.service.util.LanguageUtil;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;

import org.jsoup.Jsoup;

public class FeedRepository {

    private static final String TAG = "FeedRepository";
    private FeedDao feedDao;
    private EntryRepository entryRepository;
    private HistoryRepository historyRepository;
    private MutableLiveData<Boolean> isLoading = new MutableLiveData<>();
    private RssWorkManager rssWorkManager;
    private SharedPreferencesRepository preferencesRepository;
    private final Provider<TtsExtractor> ttsExtractorProvider;
    private final TextUtil textUtil;
    private final CompositeDisposable disposables = new CompositeDisposable();

    @Inject
    public FeedRepository(FeedDao feedDao, EntryRepository entryRepository, HistoryRepository historyRepository, RssWorkManager rssWorkManager, SharedPreferencesRepository sharedPreferencesRepository,  Provider<TtsExtractor> ttsExtractorProvider, TextUtil textUtil) {
        this.feedDao = feedDao;
        this.entryRepository = entryRepository;
        this.historyRepository = historyRepository;
        this.rssWorkManager = rssWorkManager;
        this.preferencesRepository = sharedPreferencesRepository;
        this.ttsExtractorProvider = ttsExtractorProvider;
        this.textUtil = textUtil;
    }

    public List<Feed> getAllStaticFeeds() {
        return feedDao.getAllStaticFeeds();
    }
    public Flowable<List<Feed>> getAllFeeds() {
        return feedDao.getAllFeeds();
    }
    public MutableLiveData<Boolean> getIsLoading() {
        return isLoading;
    }
    public void insert(Feed feed) {
        feedDao.insert(feed);
    }
    public long getFeedIdByLink(String link) {
        return feedDao.getIdByLink(link);
    }

    public void update(Feed feed) {
        feedDao.update(feed)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new CompletableObserver() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {
                        Log.d(TAG, "update onSubscribe: called");
                    }

                    @Override
                    public void onComplete() {
                        Log.d(TAG, "update onComplete: called");
                        isLoading.postValue(false);
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        Log.d(TAG, "update onError: " + e.getMessage());
                    }
                });
    }

    public void delete(Feed feed) {
        entryRepository.deleteByFeedId(feed.getId());
        feedDao.delete(feed)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new CompletableObserver() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {
                        Log.d(TAG, "delete onSubscribe: called");
                    }

                    @Override
                    public void onComplete() {
                        Log.d(TAG, "delete onComplete: called");
                        if (getFeedCount() == 0 && rssWorkManager.isWorkScheduled()) {
                            rssWorkManager.dequeueRssWorker();
                        }
                        isLoading.postValue(false);
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        Log.d(TAG, "delete onError: " + e.getMessage());
                    }
                });
        historyRepository.deleteByFeedId(feed.getId());
    }

    public int getFeedCount() {
        return feedDao.getFeedCount();
    }

    public void deleteAllFeeds() {
        feedDao.deleteAllFeeds()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new CompletableObserver() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {
                        Log.d(TAG, "deleteAllFeeds onSubscribe: called");
                    }

                    @Override
                    public void onComplete() {
                        Log.d(TAG, "deleteAllFeeds onComplete: called");
                        isLoading.postValue(false);
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        Log.d(TAG, "deleteAllFeeds onError: " + e.getMessage());
                    }
                });
    }

    public void addNewFeed(RssFeed feed, boolean requiresLogin) {
        // --- INTELLIGENT DETECTION: Don't trust "en" blindly from RSS ---
        String rssLanguage = feed.getLanguage();
        
        // If the feed says it's English, we STILL verify it because many Malay/Indo sites use "en" by mistake.
        boolean needsVerification = (rssLanguage == null || rssLanguage.trim().isEmpty() || rssLanguage.equalsIgnoreCase("und") || rssLanguage.equalsIgnoreCase("en"));

        if (!needsVerification) {
            Log.d(TAG, "Using language from RSS feed: " + rssLanguage + " for feed: " + feed.getLink());
            saveFeedAndEntries(feed, rssLanguage, requiresLogin);
            return;
        }
        
        Log.d(TAG, "RSS language is '" + rssLanguage + "'. Performing verification for: " + feed.getLink());

        StringBuilder sampleText = new StringBuilder();
        for (int i = 0; i < Math.min(5, feed.getRssItems().size()); i++) {
            RssItem item = feed.getRssItems().get(i);
            if (item.getTitle() != null) sampleText.append(item.getTitle()).append(". ");
            if (item.getDescription() != null) {
                String pText = Jsoup.parse(item.getDescription()).text();
                if (pText != null && !pText.isEmpty()) sampleText.append(pText).append(" ");
            }
        }

        if (sampleText.toString().trim().isEmpty()) {
            saveFeedAndEntries(feed, (rssLanguage != null) ? rssLanguage : "en", requiresLogin);
            return;
        }

        Disposable languageDetectionDisposable = textUtil.identifyLanguageRx(sampleText.toString())
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .subscribe(
                        identifiedLanguage -> {
                            if (identifiedLanguage != null && !identifiedLanguage.equalsIgnoreCase("und")) {
                                Log.d(TAG, "Verification Success: Detected '" + identifiedLanguage + "' for feed: " + feed.getLink());
                                saveFeedAndEntries(feed, identifiedLanguage, requiresLogin);
                            } else {
                                Log.w(TAG, "Verification ambiguous. Defaulting to: " + rssLanguage);
                                saveFeedAndEntries(feed, (rssLanguage != null) ? rssLanguage : "en", requiresLogin);
                            }
                        },
                        error -> {
                            saveFeedAndEntries(feed, (rssLanguage != null) ? rssLanguage : "en", requiresLogin);
                        }
                );

        disposables.add(languageDetectionDisposable);
    }

    private void saveFeedAndEntries(RssFeed feed, String languageCode, boolean requiresLogin) {
        String normalizedLanguage = LanguageUtil.normalizeLanguageCode(languageCode);
        Log.d(TAG, "Saving feed with normalized language: " + normalizedLanguage);

        String imageUrl = "https://www.google.com/s2/favicons?sz=64&domain_url=" + feed.getLink();
        Feed newFeed = new Feed(feed.getTitle(), feed.getLink(), feed.getDescription(), imageUrl, normalizedLanguage);
        newFeed.setRequiresLogin(requiresLogin);
        if (requiresLogin) newFeed.setAuthenticated(true);

        feedDao.insert(newFeed);
        long feedId = feedDao.getIdByLink(feed.getLink());

        List<Entry> entriesToPreload = new ArrayList<>();
        for (RssItem rssItem : feed.getRssItems()) {
            Entry entry = new Entry(feedId, rssItem.getTitle(), rssItem.getLink(), rssItem.getDescription(), rssItem.getImageUrl(), rssItem.getCategory(), rssItem.getPubDate());
            long insertedId = entryRepository.insert(feedId, entry);
            if (insertedId > 0 && rssItem.getPriority() > 0) {
                entry.setPriority(rssItem.getPriority());
                entriesToPreload.add(entry);
            }
        }

        if (!entriesToPreload.isEmpty()) {
            entryRepository.preloadEntries(entriesToPreload);
        }
        markFeedAsPreloaded(feedId);
        // In saveFeedAndEntries(), after markFeedAsPreloaded(feedId):
        if (requiresLogin) {
            // New auth feed — validate immediately so we don't start extracting blindly
            Executors.newSingleThreadExecutor().execute(() -> validateSingleFeed(newFeed));
        }
        entryRepository.requeueMissingEntries();

        if (entryRepository.hasEmptyContentEntries()) {
            new Handler(Looper.getMainLooper()).post(() -> {
                Log.d(TAG, "Dispatching call to extractAllEntries() to the main thread.");

                TtsExtractor extractor = ttsExtractorProvider.get(); // ✅ DEFINE IT HERE
                Log.e("INSTANCE_CHECK", "FeedRepo Extractor: " + extractor.hashCode());

                extractor.extractAllEntries(); // ✅ use it
            });
        }

        if (!rssWorkManager.isWorkScheduled()) {
            new Handler(Looper.getMainLooper()).post(() -> {
                Log.d(TAG, "Dispatching call to enqueueRssWorker() to the main thread.");
                rssWorkManager.enqueueRssWorker();
            });
        }
    }

    public EntryRepository getEntryRepository() {
        return entryRepository;
    }

    public SharedPreferencesRepository getSharedPreferencesRepository() {
        return preferencesRepository;
    }

    public void markFeedAsPreloaded(long feedId) {
        Feed feed = feedDao.getFeedById(feedId);
        if (feed != null && !feed.isPreloaded()) {
            feed.setPreloaded(true);
            feedDao.update(feed)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new CompletableObserver() {
                        @Override
                        public void onSubscribe(@NonNull Disposable d) {}
                        @Override
                        public void onComplete() {}
                        @Override
                        public void onError(@NonNull Throwable e) {}
                    });
        }
    }

    public Feed getFeedById(long id) {
        return feedDao.getFeedById(id);
    }

    public String refreshEntries() {
        List<Feed> feeds = getAllStaticFeeds();
        ExecutorService executorService = Executors.newFixedThreadPool(4); 
        AtomicInteger counter = new AtomicInteger(0);

        for (Feed feed : feeds) {
            executorService.submit(() -> {
                try {
                    RssReader rssReader = new RssReader(feed.getLink());
                    RssFeed rssFeed = rssReader.getFeed();

                    List<History> histories = new ArrayList<>();
                    for (RssItem rssItem : rssFeed.getRssItems()) {
                        Entry entry = new Entry(feed.getId(), rssItem.getTitle(), rssItem.getLink(), rssItem.getDescription(),
                                rssItem.getImageUrl(), rssItem.getCategory(), rssItem.getPubDate());
                        long insertedId = entryRepository.insert(feed.getId(), entry);
                        if (insertedId > 0) {
                            counter.incrementAndGet();
                            entryRepository.updatePriority(1, insertedId);
                        } else {
                            histories.add(new History(entry.getFeedId(), new Date(), entry.getTitle(), entry.getLink()));
                        }
                    }

                    entryRepository.limitEntriesByFeedId(feed.getId());
                    if (!histories.isEmpty()) {
                        historyRepository.updateHistoriesByFeedId(feed.getId(), histories);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error processing feed: " + feed.getTitle(), e);
                }
            });
        }

        executorService.shutdown();
        try {
            executorService.awaitTermination(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {}

        entryRepository.requeueMissingEntries();
        return "New entries: " + counter.get();
    }


    public int getDelayTimeById(long id) {
        return feedDao.getDelayTimeById(id);
    }

    public void updateDelayTimeById(long id, int delayTime) {
        feedDao.updateDelayTimeById(id, delayTime);
    }

    public void updateTitleDescLanguage(String title, String desc, String language, String link) {
        feedDao.updateTitleDescLanguage(title, desc, language, link);
    }

    public float getTtsSpeechRateById(long id) {
        return feedDao.getTtsSpeechRateById(id);
    }

    public void updateTtsSpeechRateById(long id, float ttsSpeechRate) {
        feedDao.updateTtsSpeechRateById(id, ttsSpeechRate);
    }

    public boolean checkFeedExist(String link) {
        return feedDao.getIdByLink(link) != 0;
    }

    private final OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)   // CRITICAL: don't auto-follow 302s
            .build();

    /**
     * Called at app startup and after "Load App State".
     * Validates all feeds that requiresLogin, updates DB status,
     * does NOT block the calling thread (use on IO executor).
     */
    public void validateAuthenticatedFeeds() {
        Executors.newSingleThreadExecutor().execute(() -> {
            List<Feed> authFeeds = feedDao.getAuthRequiredFeeds();
            for (Feed feed : authFeeds) {
                validateSingleFeed(feed);
            }
        });
    }

    private void validateSingleFeed(Feed feed) {
        String cookieString = CookieManager.getInstance().getCookie(feed.getLink());
        if (cookieString == null || cookieString.isEmpty()) {
            feedDao.updateAuthStatus(feed.getId(), false, Feed.AUTH_STATUS_EXPIRED);
            Log.w(TAG, "validateSingleFeed: no cookies for " + feed.getTitle() + " → EXPIRED");
            return;
        }

        // Derive the base domain from the feed's RSS link
        // e.g. "https://www.malaysiakini.com/rss/news" → "https://www.malaysiakini.com"
        String baseUrl;
        try {
            java.net.URL parsed = new java.net.URL(feed.getLink());
            baseUrl = parsed.getProtocol() + "://" + parsed.getHost();
        } catch (Exception e) {
            Log.e(TAG, "validateSingleFeed: bad URL for " + feed.getTitle());
            return; // can't validate, leave status unchanged
        }

        String userAgent = preferencesRepository.getCachedUserAgent();

        Request request = new Request.Builder()
                .url(baseUrl)
                .header("Cookie", cookieString)
                .header("User-Agent", userAgent != null ? userAgent : "Mozilla/5.0")
                .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            int code = response.code();
            String location = response.header("Location", "");

            // A 302 redirect to a login URL = session expired
            boolean redirectsToLogin = (location != null) &&
                    (location.contains("login") ||
                            location.contains("signin") ||
                            location.contains("auth") ||
                            location.contains("account"));

            if (code == 302 && redirectsToLogin) {
                feedDao.updateAuthStatus(feed.getId(), false, Feed.AUTH_STATUS_EXPIRED);
                Log.w(TAG, "validateSingleFeed: 302→login → EXPIRED for " + feed.getTitle());
            } else if (code == 302) {
                // 302 but not to a login page (e.g. redirect to www.domain.com)
                // Follow one more level manually
                validateSingleFeedFollowRedirect(feed, location, cookieString, userAgent);
            } else if (code == 200) {
                // 200 OK — check if the body looks like a login page
                String bodySnippet = "";
                if (response.body() != null) {
                    // Read only first 4KB to keep it fast
                    byte[] bytes = new byte[4096];
                    int read = response.body().byteStream().read(bytes);
                    if (read > 0) bodySnippet = new String(bytes, 0, read).toLowerCase();
                }
                boolean looksLikeLoginPage =
                        bodySnippet.contains("type=\"password\"") ||
                                bodySnippet.contains("id=\"password\"") ||
                                bodySnippet.contains("name=\"password\"");

                if (looksLikeLoginPage) {
                    feedDao.updateAuthStatus(feed.getId(), false, Feed.AUTH_STATUS_EXPIRED);
                    Log.w(TAG, "validateSingleFeed: 200 but login form detected → EXPIRED for " + feed.getTitle());
                } else {
                    feedDao.updateAuthStatus(feed.getId(), true, Feed.AUTH_STATUS_VALID);
                    Log.d(TAG, "validateSingleFeed: 200 OK → VALID for " + feed.getTitle());
                }
            } else if (code == 401 || code == 403) {
                feedDao.updateAuthStatus(feed.getId(), false, Feed.AUTH_STATUS_EXPIRED);
                Log.w(TAG, "validateSingleFeed: HTTP " + code + " → EXPIRED for " + feed.getTitle());
            } else {
                // Other codes (500, etc.) — network/server issue, leave status unchanged
                Log.w(TAG, "validateSingleFeed: HTTP " + code + " → leaving status unchanged for " + feed.getTitle());
            }
        } catch (IOException e) {
            Log.e(TAG, "validateSingleFeed: network error for " + feed.getTitle() + ": " + e.getMessage());
            // Network error — do NOT mark as expired; leave current status
        }
    }

    private void validateSingleFeedFollowRedirect(Feed feed, String redirectUrl, String cookieString, String userAgent) {
        if (redirectUrl == null || redirectUrl.isEmpty()) return;
        try {
            Request request = new Request.Builder()
                    .url(redirectUrl)
                    .header("Cookie", cookieString)
                    .header("User-Agent", userAgent != null ? userAgent : "Mozilla/5.0")
                    .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                    .build();
            try (Response response = okHttpClient.newCall(request).execute()) {
                int code = response.code();
                String location = response.header("Location", "");
                boolean redirectsToLogin = (location != null) &&
                        (location.contains("login") || location.contains("signin") || location.contains("auth"));
                if ((code == 302 && redirectsToLogin) || code == 401 || code == 403) {
                    feedDao.updateAuthStatus(feed.getId(), false, Feed.AUTH_STATUS_EXPIRED);
                } else if (code == 200) {
                    feedDao.updateAuthStatus(feed.getId(), true, Feed.AUTH_STATUS_VALID);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "validateSingleFeedFollowRedirect error: " + e.getMessage());
        }
    }

    /**
     * Call this from WebViewActivity after successful login is detected.
     */
    public void markSessionValid(long feedId) {
        feedDao.updateAuthStatus(feedId, true, Feed.AUTH_STATUS_VALID);
        Log.d(TAG, "markSessionValid: feedId=" + feedId);
    }

    public void markSessionExpired(long feedId) {
        feedDao.updateAuthStatus(feedId, false, Feed.AUTH_STATUS_EXPIRED);
    }

    public Flowable<List<Feed>> getExpiredAuthFeedsFlowable() {
        return feedDao.getExpiredAuthFeedsFlowable();
    }
}
