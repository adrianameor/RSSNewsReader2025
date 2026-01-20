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
import android.os.Handler;
import android.os.Looper;
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
        entryRepository.requeueMissingEntries();

        if (entryRepository.hasEmptyContentEntries()) {
            new Handler(Looper.getMainLooper()).post(() -> {
                Log.d(TAG, "Dispatching call to extractAllEntries() to the main thread.");
                ttsExtractorProvider.get().extractAllEntries();
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
}
