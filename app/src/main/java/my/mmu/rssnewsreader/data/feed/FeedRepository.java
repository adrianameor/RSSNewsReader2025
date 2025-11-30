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
        // --- THIS IS THE NEW, SMARTER LOGIC ---

        // 1. Get the language code provided by the RSS feed's data (and clean it).
        String feedLanguage = feed.getLanguage();
        if (feedLanguage != null && feedLanguage.contains("-")) {
            feedLanguage = feedLanguage.substring(0, feedLanguage.indexOf("-")).toLowerCase();
        }
        final String finalFeedLanguage = feedLanguage; // Make it final for use in lambda

        // 2. Always get a sample of text to run automatic detection.
        StringBuilder sampleText = new StringBuilder();
        for (int i = 0; i < Math.min(5, feed.getRssItems().size()); i++) {
            RssItem item = feed.getRssItems().get(i);
            if (item.getDescription() != null) {
                sampleText.append(item.getDescription()).append(" ");
            }
        }

        // If there's no text, we can't do anything smart. Save with what the feed gave us or a default.
        if (sampleText.toString().trim().isEmpty()) {
            Log.w(TAG, "No sample text for language ID. Saving with feed-provided language or 'en'.");
            saveFeedAndEntries(feed, (finalFeedLanguage != null && !finalFeedLanguage.isEmpty() ? finalFeedLanguage : "en"));
            return;
        }

        // 3. Run the automatic detection.
        Disposable languageDetectionDisposable = textUtil.identifyLanguageRx(sampleText.toString())
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .subscribe(
                        identifiedLanguage -> {
                            // 4. THIS IS THE SMARTER LOGIC: Compare the results.
                            String finalLanguageToSave;

                            boolean isFeedLangUseful = finalFeedLanguage != null && !finalFeedLanguage.trim().isEmpty() && !finalFeedLanguage.equalsIgnoreCase("und");
                            boolean isDetectedLangUseful = identifiedLanguage != null && !identifiedLanguage.trim().isEmpty() && !identifiedLanguage.equalsIgnoreCase("und");

                            // Heuristic: If the feed claims to be English, but our detection finds something
                            // else specific, we will trust our detection. This handles your exact problem.
                            if (isFeedLangUseful && finalFeedLanguage.equals("en") && isDetectedLangUseful && !identifiedLanguage.equals("en")) {
                                Log.d(TAG, "Conflict detected. Feed says 'en', but content is detected as '" + identifiedLanguage + "'. TRUSTING DETECTION.");
                                finalLanguageToSave = identifiedLanguage;
                            }
                            // Heuristic: If the feed provides a useful language that isn't English, trust it
                            // (as it's likely more specific than our detection, e.g., ms-my vs id).
                            else if (isFeedLangUseful && !finalFeedLanguage.equals("en")) {
                                Log.d(TAG, "Detected language is '" + identifiedLanguage + "'. Trusting the specific language provided by the feed: '" + finalFeedLanguage + "'");
                                finalLanguageToSave = finalFeedLanguage;
                            }
                            // Heuristic: If the feed language is not useful, but detection is, use detection.
                            else if (isDetectedLangUseful) {
                                Log.d(TAG, "Feed language not provided or not useful. Using detected language: '" + identifiedLanguage + "'");
                                finalLanguageToSave = identifiedLanguage;
                            }
                            // Ultimate fallback in case nothing is useful.
                            else {
                                Log.w(TAG, "Could not determine language from feed or detection. Defaulting to 'en'.");
                                finalLanguageToSave = "en";
                            }

                            // 5. Save with the final, decided language.
                            saveFeedAndEntries(feed, finalLanguageToSave);
                        },
                        error -> {
                            // On error, fall back to trusting the feed's language or 'en'.
                            Log.e(TAG, "Proactive language ID failed. Falling back to feed-provided language.", error);
                            saveFeedAndEntries(feed, (finalFeedLanguage != null && !finalFeedLanguage.isEmpty() ? finalFeedLanguage : "en"));
                        }
                );

        disposables.add(languageDetectionDisposable);
    }

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

        // --- THIS IS THE FIX for the threading issue ---
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
