package my.mmu.rssnewsreader.data.feed;

import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import my.mmu.rssnewsreader.data.entry.Entry;
import my.mmu.rssnewsreader.data.entry.EntryRepository;
import my.mmu.rssnewsreader.data.history.History;
import my.mmu.rssnewsreader.data.history.HistoryRepository;
import my.mmu.rssnewsreader.service.rss.RssFeed;
import my.mmu.rssnewsreader.service.rss.RssItem;
import my.mmu.rssnewsreader.service.rss.RssReader;
import my.mmu.rssnewsreader.service.rss.RssWorkManager;
import my.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import my.mmu.rssnewsreader.service.tts.TtsExtractor;

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
import my.mmu.rssnewsreader.service.util.TextUtil;
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

    public void addNewFeed(RssFeed feed) {
        // --- THIS IS THE NEW, SMARTER LOGIC BASED ON YOUR EXCELLENT SUGGESTION ---

        // 1. Get a CLEANER sample of text by using titles and paragraph text.
        StringBuilder sampleText = new StringBuilder();
        for (int i = 0; i < Math.min(5, feed.getRssItems().size()); i++) { // Look at first 5 items
            RssItem item = feed.getRssItems().get(i);

            // Add the title, which is usually clean text.
            if (item.getTitle() != null) {
                sampleText.append(item.getTitle()).append(". ");
            }

            // Parse the description HTML and get text only from paragraphs.
            if (item.getDescription() != null) {
                String pText = Jsoup.parse(item.getDescription()).select("p").text();
                if (pText != null && !pText.isEmpty()) {
                    sampleText.append(pText).append(" ");
                }
            }
        }

        // If there's no clean text to analyze, we fall back to a safe default.
        if (sampleText.toString().trim().isEmpty()) {
            Log.w(TAG, "No clean sample text found for language ID. Saving with default 'en'.");
            saveFeedAndEntries(feed, "en");
            return;
        }

        // 2. Run the automatic detection on the much cleaner sample text.
        Disposable languageDetectionDisposable = textUtil.identifyLanguageRx(sampleText.toString())
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .subscribe(
                        identifiedLanguage -> {
                            // 3. After detection, check if the result is valid and useful.
                            if (identifiedLanguage != null && !identifiedLanguage.equalsIgnoreCase("und") && !identifiedLanguage.trim().isEmpty()) {
                                // If it is, use the detected language.
                                Log.d(TAG, "Proactively identified language for feed '" + feed.getLink() + "' as: " + identifiedLanguage);
                                saveFeedAndEntries(feed, identifiedLanguage);
                            } else {
                                // If the result is "und" or null, it's a failure. Fall back to the safe default.
                                Log.w(TAG, "Language detection resulted in '" + identifiedLanguage + "'. Defaulting to 'en' for feed: " + feed.getLink());
                                saveFeedAndEntries(feed, "en");
                            }
                        },
                        error -> {
                            // 4. On any other error, also fall back to a safe default.
                            Log.e(TAG, "Proactive language ID failed for feed " + feed.getLink() + ". Defaulting to 'en'.", error);
                            saveFeedAndEntries(feed, "en");
                        }
                );

        disposables.add(languageDetectionDisposable);
    }

    // This is the helper method, now with corrected threading logic.
    private void saveFeedAndEntries(RssFeed feed, String languageCode) {
        // This part correctly runs on a background thread.
        String imageUrl = "https://www.google.com/s2/favicons?sz=64&domain_url=" + feed.getLink();
        Feed newFeed = new Feed(feed.getTitle(), feed.getLink(), feed.getDescription(), imageUrl, languageCode);

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

        // --- THIS IS THE FIX for the threading issue that was preventing content downloads ---
        // After all background DB work is done, we post the commands to start
        // the next workers back onto the main Android thread.

        if (entryRepository.hasEmptyContentEntries()) {
            new Handler(Looper.getMainLooper()).post(() -> {
                Log.d(TAG, "Dispatching call to extractAllEntries() to the main thread.");
                ttsExtractorProvider.get().extractAllEntries();
            });
        } else {
            Log.d(TAG, "No entries to extract.");
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
                        public void onSubscribe(@NonNull Disposable d) {
                            Log.d(TAG, "markFeedAsPreloaded: Feed marked as preloaded" + feed.getTitle());
                        }

                        @Override
                        public void onComplete() {
                            Log.d(TAG, "markFeedAsPreloaded: Complete");
                        }

                        @Override
                        public void onError(@NonNull Throwable e) {
                            Log.e(TAG, "markFeedAsPreloaded: Error " + e.getMessage());
                        }
                    });
        } else {
            Log.w(TAG, "markFeedAsPreloaded: Feed not found for ID " + feedId);
        }
    }

    public String refreshEntries() {
        List<Feed> feeds = getAllStaticFeeds();
        ExecutorService executorService = Executors.newFixedThreadPool(4); // Use 4 threads for parallel fetching
        AtomicInteger counter = new AtomicInteger(0); // Use AtomicInteger for thread-safe increments

        for (Feed feed : feeds) {
            executorService.submit(() -> {
                try {
                    Log.d(TAG, "Fetching feed: " + feed.getLink());
                    RssReader rssReader = new RssReader(feed.getLink());
                    RssFeed rssFeed = rssReader.getFeed();

                    List<History> histories = new ArrayList<>();
                    for (RssItem rssItem : rssFeed.getRssItems()) {
                        Entry entry = new Entry(feed.getId(), rssItem.getTitle(), rssItem.getLink(), rssItem.getDescription(),
                                rssItem.getImageUrl(), rssItem.getCategory(), rssItem.getPubDate());
                        long insertedId = entryRepository.insert(feed.getId(), entry);
                        if (insertedId > 0) {
                            counter.incrementAndGet(); // Increment the counter atomically
                            entryRepository.updatePriority(1, insertedId);
                        } else {
                            histories.add(new History(entry.getFeedId(), new Date(), entry.getTitle(), entry.getLink()));
                        }
                    }

                    entryRepository.limitEntriesByFeedId(feed.getId());
                    if (!histories.isEmpty()) {
                        historyRepository.updateHistoriesByFeedId(feed.getId(), histories);
                    }
                    Log.d(TAG, "Successfully fetched and processed feed: " + feed.getTitle());
                } catch (Exception e) {
                    Log.e(TAG, "Error fetching or processing feed: " + feed.getTitle(), e);
                }
            });
        }

        executorService.shutdown();
        try {
            executorService.awaitTermination(10, TimeUnit.MINUTES); // Wait for all threads to finish
        } catch (InterruptedException e) {
            Log.e(TAG, "Error awaiting termination of executor service.", e);
        }

        entryRepository.requeueMissingEntries();
        return "New entries: " + counter.get(); // Use AtomicInteger's get method
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
