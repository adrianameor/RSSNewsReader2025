package com.adriana.newscompanion.service.tts;

import android.app.Notification;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.media.MediaBrowserServiceCompat;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import com.adriana.newscompanion.data.entry.Entry;
import com.adriana.newscompanion.data.entry.EntryRepository;
import com.adriana.newscompanion.data.playlist.PlaylistRepository;
import com.adriana.newscompanion.data.sharedpreferences.SharedPreferencesRepository;
import com.adriana.newscompanion.model.EntryInfo;
import com.adriana.newscompanion.ui.webview.WebViewListener;

@AndroidEntryPoint
public class TtsService extends MediaBrowserServiceCompat {
    private final CompositeDisposable disposables = new CompositeDisposable();
    private static final String TAG = "TtsService";

    @Inject
    TtsPlayer ttsPlayer;
    @Inject
    TtsPlaylist ttsPlaylist;
    @Inject
    SharedPreferencesRepository sharedPreferencesRepository;
    @Inject
    EntryRepository entryRepository;
    @Inject
    PlaylistRepository playlistRepository;
    private TtsNotification ttsNotification;
    private static MediaSessionCompat mediaSession;
    private MediaMetadataCompat preparedData;
    private boolean serviceInStartedState;
    private boolean isPreparing = false;
    private static MediaSessionCompat mediaSessionInstance;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        TtsMediaButtonReceiver.handleIntent(mediaSession, intent);
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "created");
        ttsPlayer.initTts(TtsService.this, new TtsPlayerListener(), callback);
        ttsNotification = new TtsNotification(this);

        mediaSession = new MediaSessionCompat(this, TAG);
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(callback);
        mediaSession.setMediaButtonReceiver(null);
        mediaSession.setActive(true);

        mediaSessionInstance = mediaSession;

        PlaybackStateCompat initialState = new PlaybackStateCompat.Builder()
                .setActions(
                        PlaybackStateCompat.ACTION_PLAY |
                                PlaybackStateCompat.ACTION_PAUSE |
                                PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                                PlaybackStateCompat.ACTION_FAST_FORWARD |
                                PlaybackStateCompat.ACTION_REWIND |
                                PlaybackStateCompat.ACTION_STOP
                )
                .setState(PlaybackStateCompat.STATE_PAUSED, 0, 1.0f)
                .build();
        mediaSession.setPlaybackState(initialState);

        Log.d("TTS", "MediaSession active?" +mediaSession);
        setSessionToken(mediaSession.getSessionToken());
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "destroyed");
        disposables.clear();
        ttsPlayer.stop();
        mediaSession.release();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        stopSelf();
    }

    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
        return new BrowserRoot("success", null);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        // We revert to the simplest possible implementation.
        // The responsibility of loading the playlist and preparing the data now belongs
        // to the onPrepareFromMediaId method, which is triggered by the client.
        // This method will now only return the media items AFTER they have been prepared.
        result.sendResult(ttsPlaylist.getMediaItems());
    }

    private final MediaSessionCompat.Callback callback = new MediaSessionCompat.Callback() {

        // The onPrepare method is now deprecated in our new flow, but we keep it to avoid crashes.
        @Override
        public void onPrepare() {
            Log.w(TAG, "onPrepare() was called directly, but this is deprecated. The client should call onPrepareFromMediaId instead.");
            // Do nothing.
        }

        @Override
        public void onPrepareFromMediaId(String mediaId, Bundle extras) {
            Log.e("LIFECYCLE_DEBUG", "--- 2. onPrepareFromMediaId called with ID: " + mediaId + " ---");

            // --- THIS IS THE FIX for the race condition ---
            if (isPreparing) {
                Log.w(TAG, "onPrepareFromMediaId called while already preparing. Ignoring request.");
                return;
            }
            isPreparing = true; // Lock: Set the flag immediately.
            // --- END OF FIX ---

            long entryId;
            try {
                entryId = Long.parseLong(mediaId);
            } catch (NumberFormatException e) {
                Log.e(TAG, "Invalid mediaId passed to onPrepareFromMediaId", e);
                isPreparing = false;
                return;
            }

            Disposable onPrepareDisposable = Completable.fromAction(() -> {
                        Log.e("LIFECYCLE_DEBUG", "3. onPrepareFromMediaId: Background task STARTED.");

                        // 1. Get the full playlist of IDs from the repository.
                        String playlistIdString = playlistRepository.getLatestPlaylist();
                        List<Long> playlistIds = new ArrayList<>();
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

                        // 2. Give the fresh, complete playlist to our TtsPlaylist instance.
                        ttsPlaylist.setPlaylist(playlistIds, entryId);

                        // 3. Now that the playlist is correctly initialized, get the metadata.
                        preparedData = ttsPlaylist.getCurrentMetadata();
                        Log.e("LIFECYCLE_DEBUG", "4. onPrepareFromMediaId: Background task FINISHED. preparedData is " + (preparedData == null ? "NULL" : "NOT NULL"));

                        if (preparedData == null) {
                            throw new IllegalStateException("PreparedData is null after loading, cannot prepare playback.");
                        }

                        // The rest of the preparation logic remains the same.
                        if (!ttsPlayer.isPausedManually()) {
                            ttsPlayer.setupMediaPlayer(false);
                        }
                        if (ttsPlayer.ttsIsNull()) {
                            ttsPlayer.initTts(TtsService.this, new TtsPlayerListener(), callback);
                        }

                        // First, try to get content and language from SharedPreferences (set by WebViewActivity)
                        String content = sharedPreferencesRepository.getCurrentTtsContent();
                        String languageToUse = sharedPreferencesRepository.getCurrentTtsLang();

                        // Fallback to DB content if not set in SharedPreferences
                        if (content == null || content.trim().isEmpty()) {
                            boolean isTranslatedView = sharedPreferencesRepository.getIsTranslatedView(entryId);
                            Entry entry = entryRepository.getEntryById(entryId);
                            if (entry == null) {
                                throw new IllegalStateException("Entry not found for ID: " + entryId);
                            }

                            String original = entry.getContent();
                            String translated = entry.getTranslated();
                            content = (isTranslatedView && translated != null && !translated.trim().isEmpty()) ? translated : original;

                            EntryInfo entryInfo = entryRepository.getEntryInfoById(entryId);
                            String feedLanguage = (entryInfo != null && entryInfo.getFeedLanguage() != null) ? entryInfo.getFeedLanguage() : "en";
                            String targetLanguage = sharedPreferencesRepository.getDefaultTranslationLanguage();
                            languageToUse = isTranslatedView ? targetLanguage : feedLanguage;
                        }

                        ttsPlayer.stopTtsPlayback();
                        ttsPlayer.extract(entryId, entryRepository.getEntryById(entryId).getFeedId(), content, languageToUse);
                    })
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(() -> {
                        Log.e("LIFECYCLE_DEBUG", "--- 5. onPrepareFromMediaId onComplete (Main Thread) ---");
                        isPreparing = false;
                        if (preparedData != null) {
                            if (!mediaSession.isActive()) {
                                mediaSession.setActive(true);
                            }
                            mediaSession.setMetadata(preparedData);
                            ttsPlayer.setTtsSpeechRate(Float.parseFloat(preparedData.getString("ttsSpeechRate")));
                            notifyChildrenChanged("success");
                        }

                        /*if (!ttsPlayer.isPausedManually()) {
                            ttsPlayer.speak();
                        }*/
                    }, error -> {
                        Log.e("LIFECYCLE_DEBUG", "--- X. onPrepareFromMediaId onError (Main Thread) ---", error);
                    isPreparing = false;
                    });

            disposables.add(onPrepareDisposable);
        }

        @Override
        public void onPlay() {
            Log.d(TAG, "onPlay called");
            if (!ttsPlayer.isPreparing()) {
                ttsPlayer.setPausedManually(false);
                ttsPlayer.setupMediaPlayer(false);
                play();
            }
        }

        @Override
        public void onPause() {
            Log.d(TAG, "onPause called");
            if (ttsPlayer != null) {
                ttsPlayer.setPausedManually(true);
                ttsPlayer.pauseTts();
                ttsPlayer.pauseMediaPlayer();
                updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
                ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> ttsPlayer.hideFakeLoading());
            }
        }

        @Override
        public void onStop() {
            Log.d(TAG, "onStop called");

            if (ttsPlayer != null) {
                ttsPlayer.stop();
                ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> ttsPlayer.hideFakeLoading());
            }

            //ttsPlaylist.updatePlayingId(0);
            //mediaSession.setActive(false);
            //stopSelf();
            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED);
        }

        @Override
        public void onSkipToNext() {
            Log.d(TAG, "onSkipToNext called");
            // 1. Stop any current playback.
            if (ttsPlayer != null) {
                ttsPlayer.resetStateForNewArticle();
                ttsPlayer.stopTtsPlayback();
            }
            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED);

            // 2. Try to move to the next item in the stateful playlist.
            if (ttsPlaylist.skipNext()) {
                // Reset paused state to allow automatic progression
                ttsPlayer.setPausedManually(false);
                // 3. If successful, call our NEW, lightweight helper method.
                prepareAndPlayCurrentTrack();
                
                // 4. Notify WebViewActivity to update its UI
                notifyWebViewActivityOfArticleChange();
            } else {
                Log.w(TAG, "Cannot skip next: at the end of the playlist.");
                updatePlaybackState(PlaybackStateCompat.STATE_STOPPED);
            }
        }

        @Override
        public void onSkipToPrevious() {
            Log.d(TAG, "onSkipToPrevious called");
            // The logic is identical for skipping previous.
            if (ttsPlayer != null) {
                ttsPlayer.resetStateForNewArticle();
                ttsPlayer.stopTtsPlayback();
            }
            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED);

            if (ttsPlaylist.skipPrevious()) {
                prepareAndPlayCurrentTrack();
                
                // Notify WebViewActivity to update its UI
                notifyWebViewActivityOfArticleChange();
            } else {
                Log.w(TAG, "Cannot skip previous: at the start of the playlist.");
                updatePlaybackState(PlaybackStateCompat.STATE_STOPPED);
            }
        }
        
        /**
         * Notifies the WebViewActivity to update its UI when the article changes.
         * This sends a new Intent with FLAG_ACTIVITY_SINGLE_TOP to trigger onNewIntent().
         */
        private void notifyWebViewActivityOfArticleChange() {
            long newEntryId = ttsPlaylist.getPlayingId();
            EntryInfo entryInfo = entryRepository.getEntryInfoById(newEntryId);
            
            if (entryInfo != null) {
                Intent intent = new Intent(TtsService.this, com.adriana.newscompanion.ui.webview.WebViewActivity.class);
                intent.putExtra("entry_id", newEntryId);
                intent.putExtra("entry_title", entryInfo.getEntryTitle());
                intent.putExtra("read", false); // Play mode
                intent.putExtra("from_skip", true); // Mark this as coming from skip action
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                
                Log.d(TAG, "Sending Intent to WebViewActivity for article: " + newEntryId + " (from skip action)");
                startActivity(intent);
            } else {
                Log.e(TAG, "Could not find EntryInfo for ID: " + newEntryId);
            }
        }

        private void prepareAndPlayCurrentTrack() {
            preparedData = ttsPlaylist.getCurrentMetadata();
            if (preparedData == null) {
                Log.e(TAG, "prepareAndPlayCurrentTrack failed: metadata was null.");
                return;
            }

            mediaSession.setMetadata(preparedData);
            long entryId = Long.parseLong(preparedData.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID));
            sharedPreferencesRepository.setCurrentReadingEntryId(entryId);

            // This is our single, reliable background task for track changes
            Disposable trackPreparationDisposable = Completable.fromAction(() -> {
                        // --- THIS IS THE FINAL, CORRECT FLOW ---
                        // 1. Get the content for the player.
                        // First, try to get content and language from SharedPreferences (set by WebViewActivity)
                        String content = sharedPreferencesRepository.getCurrentTtsContent();
                        String languageToUse = sharedPreferencesRepository.getCurrentTtsLang();

                        // Fallback to DB content if not set in SharedPreferences
                        if (content == null || content.trim().isEmpty()) {
                            boolean isTranslatedView = sharedPreferencesRepository.getIsTranslatedView(entryId);
                            Entry entry = entryRepository.getEntryById(entryId);
                            if (entry == null) {
                                throw new IllegalStateException("Entry not found for ID: " + entryId);
                            }
                            content = (isTranslatedView && entry.getTranslated() != null && !entry.getTranslated().trim().isEmpty())
                                    ? entry.getTranslated()
                                    : entry.getContent();
                            languageToUse = (isTranslatedView)
                                    ? sharedPreferencesRepository.getDefaultTranslationLanguage()
                                    : entryRepository.getEntryInfoById(entryId).getFeedLanguage();
                        }

                        // 2. Call the synchronous extract method on this background thread.
                        boolean success = ttsPlayer.extract(entryId, entryRepository.getEntryById(entryId).getFeedId(), content, languageToUse);
                        if (!success) {
                            throw new IllegalStateException("Extraction failed for entry ID: " + entryId);
                        }

                        // 3. Call the new synchronous setup method on this background thread.
                        ttsPlayer.setupTts();
                        // --- END OF BACKGROUND WORK ---
                    })
                    .subscribeOn(Schedulers.io()) // Run all background work on a single background thread.
                    .observeOn(AndroidSchedulers.mainThread()) // Switch to the main thread for the final command.
                    .subscribe(() -> {
                        // 4. Now that everything is prepared, tell the player to speak.
                        Log.d(TAG, "Track preparation complete, calling speak().");
                        if (!ttsPlayer.isPausedManually()) {
                            ttsPlayer.speak();
                        }
                    }, error -> {
                        Log.e(TAG, "Error during prepareAndPlayCurrentTrack", error);
                    });

            disposables.add(trackPreparationDisposable);
        }

        private void play() {
            // The main play button can now use the modern entry point if data isn't ready.
            if (preparedData == null) {
                onPrepareFromMediaId(String.valueOf(sharedPreferencesRepository.getCurrentReadingEntryId()), null);
            } else {
                ttsPlayer.play();
            }
        }

        @Override
        public void onFastForward() {
            Log.d(TAG, "onFastForward called");

            if (ttsPlayer != null) {
                ttsPlayer.fastForward();
                ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> ttsPlayer.hideFakeLoading());
            }

            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
        }

        @Override
        public void onRewind() {
            Log.d(TAG, "onRewind called");

            if (ttsPlayer != null) {
                ttsPlayer.fastRewind();
                ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> ttsPlayer.hideFakeLoading());
            }

            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
        }

        @Override
        public void onCustomAction(String action, Bundle extras) {
            super.onCustomAction(action, extras);
            Log.d(TAG, "onCustomAction: Action = " + action);
            switch (action) {
                case "autoPlay":
                    play();
                    break;
                case "playFromService":
                    if (preparedData == null) {
                        onPrepare();
                    } else {
                        if (!ttsPlayer.isUiControlPlayback()) {
                            ttsPlayer.play();
                        }
                    }
                    break;
                case "contentChangedSummary":
                    // Restart TTS with new content from SharedPreferences, reset progress
                    long currentEntryId = sharedPreferencesRepository.getCurrentReadingEntryId();
                    entryRepository.updateSentCount(0, currentEntryId);
                    String newContent = sharedPreferencesRepository.getCurrentTtsContent();
                    String newLang = sharedPreferencesRepository.getCurrentTtsLang();
                    if (newContent != null && !newContent.trim().isEmpty()) {
                        ttsPlayer.stopTtsPlayback();
                        boolean success = ttsPlayer.extract(currentEntryId, entryRepository.getEntryById(currentEntryId).getFeedId(), newContent, newLang);
                        if (success) {
                            ttsPlayer.setupTts();
                            if (!ttsPlayer.isPausedManually()) {
                                ttsPlayer.speak();
                            }
                        }
                    }
                    break;
                case "contentChangedTranslation":
                    // Restart TTS with new content from SharedPreferences, maintain sentence position
                    long currentEntryId2 = sharedPreferencesRepository.getCurrentReadingEntryId();
                    String newContent2 = sharedPreferencesRepository.getCurrentTtsContent();
                    String newLang2 = sharedPreferencesRepository.getCurrentTtsLang();
                    if (newContent2 != null && !newContent2.trim().isEmpty()) {
                        ttsPlayer.stopTtsPlayback();
                        boolean success = ttsPlayer.extract(currentEntryId2, entryRepository.getEntryById(currentEntryId2).getFeedId(), newContent2, newLang2);
                        if (success) {
                            // Set sentence counter to the saved sentence index, clamped to new content size
                            int savedSentenceIndex = sharedPreferencesRepository.getCurrentTtsSentencePosition();
                            int clampedSentenceIndex = Math.min(savedSentenceIndex, ttsPlayer.getSentences().size() - 1);
                            entryRepository.updateSentCount(clampedSentenceIndex, currentEntryId2);
                            ttsPlayer.setupTts();
                            if (!ttsPlayer.isPausedManually()) {
                                ttsPlayer.speak();
                            }
                        }
                    }
                    break;
                default:
                    Log.w(TAG, "Unhandled custom action: " + action);
            }
        }

        @Override
        public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
            Log.d("MediaSession", "Media button event received: " + mediaButtonIntent);
            return super.onMediaButtonEvent(mediaButtonIntent);
        }

        private void updatePlaybackState(int state) {
            PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder();
            stateBuilder.setActions(
                    PlaybackStateCompat.ACTION_PLAY |
                            PlaybackStateCompat.ACTION_PAUSE |
                            PlaybackStateCompat.ACTION_PLAY_PAUSE |
                            PlaybackStateCompat.ACTION_STOP |
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                            PlaybackStateCompat.ACTION_FAST_FORWARD |
                            PlaybackStateCompat.ACTION_REWIND
            );
            stateBuilder.setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f, SystemClock.elapsedRealtime());
            mediaSession.setPlaybackState(stateBuilder.build());

            if (ttsPlayer != null) {
                if (state == PlaybackStateCompat.STATE_BUFFERING || state == PlaybackStateCompat.STATE_CONNECTING) {
                    ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> ttsPlayer.showFakeLoading());
                } else {
                    ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> {
                        ttsPlayer.hideFakeLoading();

                        WebViewListener callback = ttsPlayer.getWebViewCallback();
                        if (callback != null) {
                            ContextCompat.getMainExecutor(getApplicationContext()).execute(callback::hideFakeLoading);
                        }
                    });
                }
            }
            Log.d("TTS", "PlaybackState updated to: " + state);
        }
    };

    public class TtsPlayerListener extends PlaybackStateListener {

        private final ServiceManager serviceManager;

        TtsPlayerListener() {
            serviceManager = new ServiceManager();
        }

        @Override
        public void
        onPlaybackStateChange(PlaybackStateCompat state) {
            mediaSession.setPlaybackState(state);

            switch (state.getState()) {
                case PlaybackStateCompat.STATE_PLAYING:
                    serviceManager.moveServiceToStartedState(state);
                    break;
                case PlaybackStateCompat.STATE_PAUSED:
                    serviceManager.updateNotificationForPause(state);
                    break;
                case PlaybackStateCompat.STATE_STOPPED:
                    serviceManager.moveServiceOutOfStartedState(state);
                    break;
            }
        }

        class ServiceManager {

            private final Intent intent = new Intent(TtsService.this, TtsService.class);

            private void moveServiceToStartedState(PlaybackStateCompat state) {
                Log.d(TAG, "notification to play");
                Notification notification = ttsNotification.getNotification(preparedData, state, getSessionToken());

                if (!serviceInStartedState) {
                    ContextCompat.startForegroundService(TtsService.this, intent);
                    startForeground(TtsNotification.TTS_NOTIFICATION_ID, notification);
                    serviceInStartedState = true;
                } else {
                    ttsNotification.getNotificationManager().notify(TtsNotification.TTS_NOTIFICATION_ID, notification);
                }
            }

            private void updateNotificationForPause(PlaybackStateCompat state) {

                Log.d(TAG, "notification to pause");

                if (Build.VERSION.SDK_INT < 31) {
                    stopForeground(false);
                }

                Notification notification = ttsNotification.getNotification(preparedData, state, getSessionToken());
                ttsNotification.getNotificationManager().notify(TtsNotification.TTS_NOTIFICATION_ID, notification);
            }

            private void moveServiceOutOfStartedState(PlaybackStateCompat state) {
                if (serviceInStartedState) {
                    Log.d(TAG, "notification destroyed");
                    ttsNotification.getNotificationManager().cancelAll();
                    stopForeground(true);
                    serviceInStartedState = false;
                }
            }
        }
    }
    public static MediaSessionCompat getMediaSession() {
        return mediaSession;
    }
}