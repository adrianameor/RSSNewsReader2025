package com.adriana.newscompanion.service.tts;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.view.KeyEvent;

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
import com.adriana.newscompanion.data.sharedpreferences.SharedPreferencesRepository;
import com.adriana.newscompanion.model.EntryInfo;
import com.adriana.newscompanion.model.PlaybackStateModel;
import com.adriana.newscompanion.ui.webview.WebViewListener;

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
    TtsExtractor ttsExtractor;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private boolean isAudioFocusGranted = false;
    private boolean shouldResumeAfterFocusGain = false;
    private TtsNotification ttsNotification;
    private static MediaSessionCompat mediaSession;
    private MediaMetadataCompat preparedData;
    private boolean serviceInStartedState;
    private boolean isPreparing = false;
    private static MediaSessionCompat mediaSessionInstance;

    // Cache last PlaybackStateModel for onPlay() callback
    private PlaybackStateModel lastPlaybackStateModel = null;
    // Track last played entry ID for resume vs restart logic
    private long lastPlayedEntryId = -1;
    // Track if play is pending extract completion
    private boolean pendingPlay = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        TtsMediaButtonReceiver.handleIntent(mediaSession, intent);
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        Log.d(TAG, "created");
        ttsPlayer.initTts(TtsService.this, new TtsPlayerListener(), callback);

        audioFocusChangeListener = focusChange -> {
            Log.d(TAG, "🎧 Audio focus change: " + focusChange);

            switch (focusChange) {

                case AudioManager.AUDIOFOCUS_GAIN:
                    isAudioFocusGranted = true;

                    if (shouldResumeAfterFocusGain) {
                        Log.d(TAG, "▶️ Regaining focus → resume playback");
                        if (mediaSession != null) {
                            mediaSession.getController().getTransportControls().play();
                        }
                        shouldResumeAfterFocusGain = false;
                    }

                    if (ttsPlayer != null) {
                        ttsPlayer.setVolumeNormal();
                    }
                    break;

                case AudioManager.AUDIOFOCUS_LOSS:
                    isAudioFocusGranted = false;
                    shouldResumeAfterFocusGain = false;

                    if (mediaSession != null) {
                        mediaSession.getController().getTransportControls().stop();
                    }
                    break;

                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    shouldResumeAfterFocusGain = true;

                    if (mediaSession != null) {
                        mediaSession.getController().getTransportControls().pause();
                    }
                    break;

                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    if (ttsPlayer != null) {
                        ttsPlayer.setVolumeDuck();
                    }
                    break;
            }
        };

        ttsNotification = new TtsNotification(this);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .setAcceptsDelayedFocusGain(true)
                    .setWillPauseWhenDucked(false)
                    .build();
        }

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

    private AudioManager.OnAudioFocusChangeListener audioFocusChangeListener;

    private boolean requestAudioFocus() {
        int result;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            result = audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            result = audioManager.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
            );
        }

        isAudioFocusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return isAudioFocusGranted;
    }

    private void abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            audioManager.abandonAudioFocus(audioFocusChangeListener);
        }
        isAudioFocusGranted = false;
    }

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                Log.d(TAG, "🔊 Headphones disconnected → pause");
                callback.onPause();
            }
        }
    };

    @Override
    public void onDestroy() {
        unregisterReceiver(noisyReceiver);
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
            
            // CRITICAL: Update state to PLAYING IMMEDIATELY (BEFORE extract)
            // FIX: MediaSession state timing bug - UI checks state after first click
            if (!mediaSession.isActive()) {
                Log.w(TAG, "⚠️ MediaSession was not active, activating now");
                mediaSession.setActive(true);
            }
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);

            // Reset pending play flag to prevent old extracts from speaking
            pendingPlay = false;
            // Set pending play flag for this new play
            pendingPlay = true;

            // Extract and play (using UI's text, NOT database content)
            // FIX #1: Set callback to start speech AFTER extract finishes
            // FIX #2: Only reset sentence counter when article changes
            ttsPlayer.setOnReadyToSpeak(() -> {
                Log.e("TTS_TRACE", "🎯 Extract finished, checking pending play");
                if (pendingPlay) {
                    Log.e("TTS_TRACE", "🎯 Pending play triggered, starting speech");
                    if (playbackModel.currentEntryId != lastPlayedEntryId) {
                        Log.e("TTS_TRACE", "📖 New article - resetting to sentence 0");
                        ttsPlayer.resetSentenceCounter();
                        lastPlayedEntryId = playbackModel.currentEntryId;
                    } else {
                        Log.e("TTS_TRACE", "🔄 Same article - resuming from current position");
                    }
                    ttsPlayer.speakNextSentence();
                    pendingPlay = false;
                } else {
                    Log.e("TTS_TRACE", "🎯 Extract finished but no pending play");
                }
            });

            // ✅ ONE AND ONLY ONE extract call per Play action
            ttsPlayer.extract(playbackModel.currentEntryId, 0, playbackModel.textToRead, playbackModel.language);
            Log.d(TAG, "✅ Playback state set to PLAYING");
            
            Log.d(TAG, "✅ Playback started for entry " + playbackModel.currentEntryId + " (mode: " + playbackModel.mode + ")");
        }

        @Override
        public void onPlay() {

            if (!requestAudioFocus()) {
                Log.w(TAG, "❌ Audio focus denied");
                return;
            }

            if (lastPlaybackStateModel == null) {
                Log.e(TAG, "❌ No cached state, cannot resume");
                return;
            }

            Log.d(TAG, "▶️ Resuming playback");

            if (ttsPlayer != null) {
                ttsPlayer.setPausedManually(false);
                ttsPlayer.speakNextSentence();
            }

            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
        }


        @Override
        public void onPause() {
            abandonAudioFocus();
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
            abandonAudioFocus();

            if (ttsPlayer != null) {
                ttsPlayer.stop();
                ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> ttsPlayer.hideFakeLoading());
            }

            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED);
        }

        @Override
        public void onSkipToNext() {
            Log.e("MEDIA_TRACE", "🔥 CALLBACK: onSkipToNext()");

            mediaSession.sendSessionEvent("request_next_article", null);
        }

        @Override
        public void onSkipToPrevious() {
            Log.e("MEDIA_TRACE", "🔥 CALLBACK: onSkipToPrevious()");

            mediaSession.sendSessionEvent("request_previous_article", null);
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
                Notification notification = ttsNotification.getNotification(preparedData, state, getSessionToken(), null);

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

                Notification notification = ttsNotification.getNotification(preparedData, state, getSessionToken(), null);
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
