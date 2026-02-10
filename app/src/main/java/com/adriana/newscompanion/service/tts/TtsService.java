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
import com.adriana.newscompanion.model.PlaybackStateModel;
import com.adriana.newscompanion.ui.webview.WebViewListener;
import java.util.Arrays;  // Add this line

@AndroidEntryPoint
public class TtsService extends MediaBrowserServiceCompat {
    private final CompositeDisposable disposables = new CompositeDisposable();
    private static final String TAG = "TtsService";
    private static final int MINIMUM_TTS_CONTENT_LENGTH = 100;

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
    @Inject
    TtsExtractor ttsExtractor;
    private TtsNotification ttsNotification;
    private static MediaSessionCompat mediaSession;
    private MediaMetadataCompat preparedData;
    private boolean serviceInStartedState;
    private boolean isPreparing = false;
    private static MediaSessionCompat mediaSessionInstance;
    
    // Queue state management
    private final List<Long> playbackQueue = new ArrayList<>();
    private int currentQueueIndex = -1;
    private boolean isQueueInitialized = false;
    private long[] currentPlaylistForNotification = null;
    
    // Cache last PlaybackStateModel for onPlay() callback
    private PlaybackStateModel lastPlaybackStateModel = null;

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
        Log.e("MEDIA_TRACE", "🎯 MediaSession CREATED");
        Log.e("MEDIA_TRACE", "🎯 MediaSession token = " + mediaSession.getSessionToken());
        
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
        result.sendResult(ttsPlaylist.getMediaItems());
    }

    private final MediaSessionCompat.Callback callback = new MediaSessionCompat.Callback() {

        @Override
        public void onPrepare() {
            Log.w(TAG, "onPrepare() was called directly, but this is deprecated.");
        }

        @Override
        public void onPrepareFromMediaId(String mediaId, Bundle extras) {
            Log.d(TAG, "onPrepareFromMediaId called with ID: " + mediaId);
            
            long entryId;
            try {
                entryId = Long.parseLong(mediaId);
            } catch (NumberFormatException e) {
                Log.e(TAG, "Invalid mediaId passed to onPrepareFromMediaId", e);
                return;
            }

            EntryInfo entryInfo = entryRepository.getEntryInfoById(entryId);
            if (entryInfo == null) {
                Log.e(TAG, "Could not load entry info for: " + entryId);
                return;
            }

            preparedData = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, Long.toString(entryId))
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, entryInfo.getFeedTitle())
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, entryInfo.getEntryTitle())
                    .build();

            if (!mediaSession.isActive()) {
                mediaSession.setActive(true);
            }
            mediaSession.setMetadata(preparedData);
            
            Log.d(TAG, "Metadata prepared for entry: " + entryId);
        }

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            Log.e("MEDIA_TRACE", "🔥 CALLBACK: onPlayFromMediaId()");
            Log.e("MEDIA_TRACE", "   mediaId = " + mediaId);
            Log.e("MEDIA_TRACE", "   extras = " + (extras == null ? "null" : extras.keySet()));
            Log.d(TAG, "🎵 onPlayFromMediaId called for ID: " + mediaId);
            
            // STEP 3: Service ONLY uses PlaybackStateModel - NO database queries
            if (extras == null || !extras.containsKey("playback_state")) {
                Log.e(TAG, "❌ No PlaybackStateModel in extras! Cannot play.");
                Log.e(TAG, "   UI must send complete PlaybackStateModel.");
                updatePlaybackState(PlaybackStateCompat.STATE_ERROR);
                return;
            }
            
            PlaybackStateModel playbackModel = extras.getParcelable("playback_state");
            if (playbackModel == null) {
                Log.e(TAG, "❌ PlaybackStateModel is null!");
                updatePlaybackState(PlaybackStateCompat.STATE_ERROR);
                return;
            }
            
            Log.d(TAG, "✅ Received PlaybackStateModel:");
            Log.d(TAG, "   Mode: " + playbackModel.mode);
            Log.d(TAG, "   Playlist size: " + playbackModel.entryIds.size());
            Log.d(TAG, "   Current index: " + playbackModel.currentIndex);
            Log.d(TAG, "   Language: " + playbackModel.language);
            Log.d(TAG, "   Text to read length: " + playbackModel.textToRead.length());
            
            // Cache PlaybackStateModel for onPlay() callback
            lastPlaybackStateModel = playbackModel;
            
            // Update queue from PlaybackStateModel
            playbackQueue.clear();
            playbackQueue.addAll(playbackModel.entryIds);
            currentQueueIndex = playbackModel.currentIndex;
            isQueueInitialized = true;
            
            // Store playlist for notification
            currentPlaylistForNotification = new long[playbackModel.entryIds.size()];
            for (int i = 0; i < playbackModel.entryIds.size(); i++) {
                currentPlaylistForNotification[i] = playbackModel.entryIds.get(i);
            }
            
            // Validate content (AUTHORITATIVE from UI)
            if (playbackModel.textToRead == null || playbackModel.textToRead.trim().isEmpty()) {
                Log.e(TAG, "❌ No content to read in PlaybackStateModel!");
                updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
                return;
            }
            
            // Prepare metadata
            preparedData = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, String.valueOf(playbackModel.currentEntryId))
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, playbackModel.feedTitle)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, playbackModel.entryTitle)
                    .build();
            
            if (!mediaSession.isActive()) {
                mediaSession.setActive(true);
            }
            mediaSession.setMetadata(preparedData);
            
            // Extract and play (using UI's text, NOT database content)
            ttsPlayer.extract(playbackModel.currentEntryId, 0, playbackModel.textToRead, playbackModel.language);
            
            // CRITICAL: Ensure MediaSession is active before updating state
            if (!mediaSession.isActive()) {
                Log.w(TAG, "⚠️ MediaSession was not active, activating now");
                mediaSession.setActive(true);
            }
            
            // CRITICAL FIX: Android TTS doesn't reliably resume after pause
            // Always stop and re-extract to ensure TTS works
            Log.d(TAG, "🔄 Forcing TTS re-preparation (Android TTS resume workaround)");
            ttsPlayer.stop();

            // Re-extract with same content
            boolean extractSuccess = ttsPlayer.extract(
                playbackModel.currentEntryId,
                0,
                playbackModel.textToRead,
                playbackModel.language
            );

            if (!extractSuccess) {
                Log.e(TAG, "❌ TTS extraction failed");
                updatePlaybackState(PlaybackStateCompat.STATE_ERROR);
                return;
            }

            // FORCE SPEAK: If UI sends PlaybackStateModel, Service MUST always speak
            // Remove all isPausedManually checks - UI is the boss
            Log.d(TAG, "🎯 UI sent PlaybackStateModel - forcing speak (ignoring paused state)");
            ttsPlayer.speak();

            // CRITICAL: Update state IMMEDIATELY after speak()
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
            Log.d(TAG, "✅ Playback state set to PLAYING");
            
            Log.d(TAG, "✅ Playback started for entry " + playbackModel.currentEntryId + " (mode: " + playbackModel.mode + ")");
        }

        @Override
        public void onPlay() {
            Log.e("MEDIA_TRACE", "🔥 CALLBACK: onPlay()");
            Log.d(TAG, "▶️ onPlay called (MediaSession callback)");
            
            // Reuse cached PlaybackStateModel - NO database queries
            if (lastPlaybackStateModel == null) {
                Log.e(TAG, "❌ onPlay failed: no cached PlaybackStateModel");
                Log.e(TAG, "   UI must call playFromMediaId first to initialize state");
                return;
            }
            
            Log.d(TAG, "✅ Reusing cached PlaybackStateModel for resume");
            
            // Re-package and call onPlayFromMediaId with cached state
            Bundle extras = new Bundle();
            extras.putParcelable("playback_state", lastPlaybackStateModel);
            
            onPlayFromMediaId(
                String.valueOf(lastPlaybackStateModel.currentEntryId),
                extras
            );
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
            Log.d("🔥PLAYLIST_DEBUG", "⏹️ onStop called - PRESERVING queue for potential resume");
            Log.d("🔥PLAYLIST_DEBUG", "   Queue size: " + playbackQueue.size());
            Log.d("🔥PLAYLIST_DEBUG", "   Current index: " + currentQueueIndex);
            Log.d("🔥PLAYLIST_DEBUG", "   Queue contents: " + playbackQueue.toString());

            if (ttsPlayer != null) {
                ttsPlayer.stop();
                ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> ttsPlayer.hideFakeLoading());
            }

            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED);
            Log.d("🔥PLAYLIST_DEBUG", "✅ Stop complete - queue PRESERVED");
        }

        @Override
        public void onSkipToNext() {
            Log.e("MEDIA_TRACE", "🔥 CALLBACK: onSkipToNext()");
            Log.d(TAG, "⏭️ onSkipToNext called");
            
            if (!isQueueInitialized || playbackQueue.isEmpty()) {
                Log.e(TAG, "❌ Queue not initialized, cannot skip");
                return;
            }
            
            if (currentQueueIndex >= playbackQueue.size() - 1) {
                Log.w(TAG, "🎯 Reached end of queue, pausing");
                onPause();
                return;
            }
            
            currentQueueIndex++;
            long nextEntryId = playbackQueue.get(currentQueueIndex);
            
            Log.d(TAG, "   Skipping to index " + currentQueueIndex + ", entry ID: " + nextEntryId);
            
            // STEP 4: Send callback to UI to load next article
            Bundle extras = new Bundle();
            extras.putLong("next_entry_id", nextEntryId);
            extras.putInt("next_index", currentQueueIndex);
            
            // Notify UI via session event
            mediaSession.sendSessionEvent("request_next_article", extras);
            
            Log.d(TAG, "✅ Skip request sent to UI for entry: " + nextEntryId);
        }

        @Override
        public void onSkipToPrevious() {
            Log.e("MEDIA_TRACE", "🔥 CALLBACK: onSkipToPrevious()");
            Log.d(TAG, "⏮️ onSkipToPrevious called");
            
            if (!isQueueInitialized || currentQueueIndex <= 0) {
                Log.w(TAG, "❌ Cannot skip previous: at start of queue");
                return;
            }
            
            currentQueueIndex--;
            long prevEntryId = playbackQueue.get(currentQueueIndex);
            
            Log.d(TAG, "   Skipping to index " + currentQueueIndex + ", entry ID: " + prevEntryId);
            
            // STEP 4: Send callback to UI to load previous article
            Bundle extras = new Bundle();
            extras.putLong("prev_entry_id", prevEntryId);
            extras.putInt("prev_index", currentQueueIndex);
            
            // Notify UI via session event
            mediaSession.sendSessionEvent("request_previous_article", extras);
            
            Log.d(TAG, "✅ Skip request sent to UI for entry: " + prevEntryId);
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
                    if (currentQueueIndex != -1 && isQueueInitialized) {
                        long currentId = playbackQueue.get(currentQueueIndex);
                        onPlayFromMediaId(String.valueOf(currentId), null);
                    }
                    break;
                case "playFromService":
                    if (preparedData != null && !ttsPlayer.isUiControlPlayback()) {
                        ttsPlayer.play();
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
                            // FORCE SPEAK: Remove isPausedManually check - UI is boss
                            Log.d(TAG, "🎯 contentChangedSummary - forcing speak (ignoring paused state)");
                            ttsPlayer.speak();
                        }
                    }
                    break;
                case "contentChangedTranslation":
                    long currentEntryId2 = sharedPreferencesRepository.getCurrentReadingEntryId();
                    String newContent2 = sharedPreferencesRepository.getCurrentTtsContent();
                    String newLang2 = sharedPreferencesRepository.getCurrentTtsLang();
                    if (newContent2 != null && !newContent2.trim().isEmpty()) {
                        ttsPlayer.stopTtsPlayback();
                        boolean success = ttsPlayer.extract(currentEntryId2, entryRepository.getEntryById(currentEntryId2).getFeedId(), newContent2, newLang2);
                        if (success) {
                            int savedSentenceIndex = sharedPreferencesRepository.getCurrentTtsSentencePosition();
                            int clampedSentenceIndex = Math.min(savedSentenceIndex, ttsPlayer.getSentences().size() - 1);
                            entryRepository.updateSentCount(clampedSentenceIndex, currentEntryId2);
                            ttsPlayer.setupTts();
                            // FORCE SPEAK: Remove isPausedManually check - UI is boss
                            Log.d(TAG, "🎯 contentChangedTranslation - forcing speak (ignoring paused state)");
                            ttsPlayer.speak();
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
            Log.e("MEDIA_TRACE", "🎯 setPlaybackState called, state=" + state);
            Log.e("MEDIA_TRACE", "🎯 mediaSession.isActive() = " + mediaSession.isActive());
            Log.d(TAG, "🔄 updatePlaybackState called with state: " + state);
            
            // CRITICAL: Ensure MediaSession is active
            if (!mediaSession.isActive()) {
                Log.w(TAG, "⚠️ MediaSession not active in updatePlaybackState, activating now");
                mediaSession.setActive(true);
            }
            
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
            
            PlaybackStateCompat newState = stateBuilder.build();
            mediaSession.setPlaybackState(newState);
            
            Log.d(TAG, "✅ MediaSession playback state updated to: " + state);
            Log.d(TAG, "   Actions enabled: PLAY, PAUSE, SKIP_NEXT, SKIP_PREVIOUS, FF, REWIND");

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
        }
    };

    public class TtsPlayerListener extends PlaybackStateListener {

        private final ServiceManager serviceManager;

        TtsPlayerListener() {
            serviceManager = new ServiceManager();
        }

        @Override
        public void onPlaybackStateChange(PlaybackStateCompat state) {
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
                Notification notification = ttsNotification.getNotification(preparedData, state, getSessionToken(), currentPlaylistForNotification);

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

                Notification notification = ttsNotification.getNotification(preparedData, state, getSessionToken(), currentPlaylistForNotification);
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
