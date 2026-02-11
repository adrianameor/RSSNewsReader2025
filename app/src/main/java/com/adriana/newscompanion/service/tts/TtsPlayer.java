package com.adriana.newscompanion.service.tts;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.adriana.newscompanion.R;
import com.adriana.newscompanion.data.entry.EntryRepository;
import com.adriana.newscompanion.data.sharedpreferences.SharedPreferencesRepository;
import com.adriana.newscompanion.ui.webview.WebViewActivity;
import com.adriana.newscompanion.ui.webview.WebViewListener;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions;
import com.google.mlkit.nl.languageid.LanguageIdentifier;

import java.io.File;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;


@Singleton
public class TtsPlayer extends PlayerAdapter implements TtsPlayerListener {

    public static final String TAG = TtsPlayer.class.getSimpleName();

    private UtteranceProgressListener utteranceListener;
    private TextToSpeech tts;
    private PlaybackStateListener listener;
    private MediaSessionCompat.Callback callback;
    private WebViewListener webViewCallback;
    private Context context;
    private final TtsExtractor ttsExtractor;
    private final EntryRepository entryRepository;
    private final SharedPreferencesRepository sharedPreferencesRepository;

    private int sentenceCounter;
    private List<String> sentences = new ArrayList<>();
    private List<Integer> paragraphStartIndices = new ArrayList<>();

    private CountDownLatch countDownLatch = new CountDownLatch(1);
    private int currentState;
    private long currentId = 0;
    private long feedId = 0;
    private String language;
    private boolean isInit = false;
    private boolean isPreparing = false;
    private boolean actionNeeded = false;
    private boolean isPausedManually;
    private boolean webViewConnected = false;
    private boolean uiControlPlayback = false;
    private boolean isManualSkip = false;
    private boolean isArticleFinished = false;
    private boolean isSettingUpNewArticle = false;
    private MediaPlayer mediaPlayer;
    private String currentUtteranceID = null;
    private boolean hasSpokenAfterSetup = false;
    private PlaybackUiListener playbackUiListener;
    private int currentExtractProgress = 0;
    private Runnable onReadyToSpeak = null;

    @Inject
    public TtsPlayer(@ApplicationContext Context context, TtsExtractor ttsExtractor, EntryRepository entryRepository, SharedPreferencesRepository sharedPreferencesRepository) {
        super(context);
        this.ttsExtractor = ttsExtractor;
        this.entryRepository = entryRepository;
        this.sharedPreferencesRepository = sharedPreferencesRepository;
        this.context = context;
        this.isPausedManually = sharedPreferencesRepository.getIsPausedManually();
    }

    @SuppressLint("SetJavaScriptEnabled")
    public void initTts(TtsService ttsService, PlaybackStateListener listener, MediaSessionCompat.Callback callback) {
        this.listener = listener;
        this.callback = callback;
        tts = new TextToSpeech(ttsService, status -> {
            Log.e("TTS_TRACE", "🔊 onInit called, status = " + status);

            if (status == TextToSpeech.SUCCESS) {
                Log.e("TTS_TRACE", "✅ TTS INIT SUCCESS");
                Log.d(TAG, "initTts successful");
                isInit = true;

                if (actionNeeded) {
                    Log.d(TAG, "Deferred auto-play activated — TTS is now ready");
                    setupTts();
                    if (!isPausedManually) {
                        speak();
                    } else {
                        Log.d(TAG, "Deferred play skipped due to manual pause");
                    }
                    actionNeeded = false;
                }
            } else {
                Log.e("TTS_TRACE", "❌ TTS INIT FAILED");
            }
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String s) {
                Log.e("TTS_TRACE", "▶️ onStart utterance = " + s);
            }

            @Override
            public void onDone(String s) {
                Log.e("TTS_TRACE", "✅ onDone utterance = " + s);

                if (isSettingUpNewArticle) {
                    Log.d(TAG, "Setup in progress, ignoring onDone event.");
                    return;
                }

                if (isManualSkip) {
                    Log.d(TAG, "Manual skip — handling skip logic");
                    isManualSkip = false;

                    if (sentenceCounter < sentences.size() - 1) {
                        sentenceCounter++;
                        entryRepository.updateSentCount(sentenceCounter, currentId);
                        Log.d(TAG, "Manual skip done. Continuing at [#" + sentenceCounter + "]");
                        // Continue with next sentence in controlled loop
                        speakNextSentence();
                    } else {
                        Log.d(TAG, "Manual skip finished last sentence. Moving to next article.");
                        entryRepository.updateSentCount(0, currentId);
                        sentenceCounter = 0;
                        isArticleFinished = true;
                        callback.onSkipToNext();
                    }
                    return;
                }

                if (isArticleFinished) {
                    Log.d(TAG, "Already finished article, skipping duplicate onDone");
                    return;
                }

                // ✅ CONTROLLED SPEECH LOOP: Advance to next sentence
                sentenceCounter++;
                entryRepository.updateSentCount(sentenceCounter, currentId);

                if (sentenceCounter < sentences.size()) {
                    Log.d(TAG, "Continuing to next sentence [#" + sentenceCounter + "]");
                    speakNextSentence();
                } else {
                    Log.d(TAG, "🏁 Finished last sentence. Moving to next article.");
                    entryRepository.updateSentCount(0, currentId);
                    sentenceCounter = 0;
                    isArticleFinished = true;
                    callback.onSkipToNext();
                }
            }

            @Override
            public void onError(String s) {
                Log.e("TTS_TRACE", "❌ onError utterance = " + s);
            }
        });
    }

    public interface PlaybackUiListener {
        void onPlaybackStarted();
        void onPlaybackPaused();
    }

    public void setPlaybackUiListener(PlaybackUiListener listener) {
        this.playbackUiListener = listener;
    }

    public void stopTtsPlayback() {
        if (tts != null && tts.isSpeaking()) {
            tts.stop();
        }

        currentId = -1;
        isPausedManually = false;
        isPreparing = false;
        isArticleFinished = false;
        isSettingUpNewArticle = false;
        setUiControlPlayback(false);
        if (playbackUiListener != null) {
            playbackUiListener.onPlaybackPaused();
        }
    }

    public void pauseTts() {
        if (tts != null && tts.isSpeaking()) {
            tts.stop();
        }
        isPausedManually = true;
        sharedPreferencesRepository.setIsPausedManually(true);
        setNewState(PlaybackStateCompat.STATE_PAUSED);
        setUiControlPlayback(false);
        if (playbackUiListener != null) {
            playbackUiListener.onPlaybackPaused();
        }
    }

    public boolean extract(long currentId, long feedId, String content, String language) {
        Log.e("EXTRACT_TRACE",
            "extract() called for entryId=" + currentId +
            " | thread=" + Thread.currentThread().getName() +
            " | stack=" + Log.getStackTraceString(new Throwable()));

        Log.e("TTS_TRACE", "📦 extract() called");
        Log.e("TTS_TRACE", "   entryId = " + currentId);
        Log.e("TTS_TRACE", "   language = " + language);
        Log.e("TTS_TRACE", "   text length = " + (content == null ? "null" : content.length()));
        Log.d(TAG, "Starting extract for article: ID=" + currentId + ", language=" + language);

        // 🔥 FIX #2: MUST RESET EVERYTHING at the VERY TOP when switching articles
        Log.e("TTS_TRACE", "🧹 Clearing previous article state");
        this.sentences.clear();
        this.sentenceCounter = 0;
        this.isArticleFinished = false;
        this.isManualSkip = false;
        this.paragraphStartIndices.clear();
        if (tts != null) tts.stop();

        int currentSentence = -1;
        boolean wasSpeaking = isPlaying();

        if (this.currentId == currentId && !this.sentences.isEmpty()) {
            currentSentence = this.sentenceCounter;
            Log.d(TAG, "View toggle detected. Capturing current sentence index: " + currentSentence);
        }

        this.isSettingUpNewArticle = true;

        this.isPreparing = true;
        
        this.currentId = currentId;
        this.feedId = feedId;
        this.language = language;
        this.hasSpokenAfterSetup = false;

        if (language != null && !language.isEmpty()) {
            ttsExtractor.setCurrentLanguage(language, true);
        }

        if (tts != null && language != null && !language.isEmpty()) {
            try {
                Log.d(TAG, "Setting TTS language to: " + language);
                Locale targetLocale = Locale.forLanguageTag(language);
                int result = tts.setLanguage(targetLocale);
                Log.e("TTS_TRACE", "🌍 setLanguage result = " + result);

                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "Language not supported by TTS engine: " + language + ". Falling back to default.");
                    tts.setLanguage(Locale.getDefault());
                } else {
                    Log.d(TAG, "TTS language successfully set to: " + targetLocale.getDisplayName());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error setting TTS language: " + language, e);
                tts.setLanguage(Locale.getDefault());
            }
        }

        if (content == null || content.trim().isEmpty()) {
            Log.e(TAG, "extract: No content provided. Extraction failed.");
            isPreparing = false;
            isSettingUpNewArticle = false;
            return false;
        }

        String[] raw = content.split(Pattern.quote(ttsExtractor.delimiter));
        List<String> sentenceList = new ArrayList<>(raw.length);
        for (String part : raw) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                sentenceList.add(trimmed);
            }
        }

        paragraphStartIndices.add(0);

        for (String sentence : sentenceList) {
            if (sentence.length() >= TextToSpeech.getMaxSpeechInputLength()) {
                BreakIterator iterator = BreakIterator.getSentenceInstance();
                iterator.setText(sentence);
                int start = iterator.first();
                for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
                    this.sentences.add(sentence.substring(start, end));
                }
            } else {
                this.sentences.add(sentence);
            }
            paragraphStartIndices.add(this.sentences.size());
        }

        if (this.sentences.size() < 2) {
            Log.w(TAG, "Extracted less than 2 sentences. Considering this a failure.");
            this.sentences.clear();
            isPreparing = false;
            isSettingUpNewArticle = false;
            return false;
        }

        // FIX #3: Remove duplicated title sentences
        if (sentences.size() > 1 &&
            sentences.get(0).trim().equals(sentences.get(1).trim())) {
            Log.e("TTS_TRACE", "🧹 Removing duplicated title sentence");
            sentences.remove(0);
        }

        if (currentSentence != -1) {
            this.sentenceCounter = Math.min(currentSentence, this.sentences.size() - 1);
            entryRepository.updateSentCount(this.sentenceCounter, this.currentId);
            Log.d(TAG, "Resume anchor: Sentence #" + this.sentenceCounter + " (Total Sentences: " + this.sentences.size() + ")");
        } else {
            int savedProgress = entryRepository.getSentCount(this.currentId);
            this.sentenceCounter = Math.min(savedProgress, Math.max(0, this.sentences.size() - 1));
        }

        this.isPreparing = false;
        this.isSettingUpNewArticle = false; // Unlock onDone events

        // ✅ CALLBACK: Notify when extract is ready for speech
        if (onReadyToSpeak != null) {
            Log.e("TTS_TRACE", "🎯 Extract finished - calling onReadyToSpeak callback");
            onReadyToSpeak.run();
            onReadyToSpeak = null; // Clear after use
        }

        return true;
    }

    @Override
    public void extractToTts(String content, String language) {
        if (tts == null) {
            Log.w(TAG, "TTS engine is not initialized.");
            return;
        }

        int currentParagraph = -1;
        boolean wasSpeaking = isPlaying();
        if (!this.sentences.isEmpty()) {
            currentParagraph = getCurrentParagraphIndex();
        }

        this.isSettingUpNewArticle = true;
        tts.stop();

        sentences.clear();
        paragraphStartIndices.clear();

        String[] raw = content.split(Pattern.quote(ttsExtractor.delimiter));
        List<String> sentenceList = new ArrayList<>(raw.length);
        for(String part : raw) {
            String trimmed = part.trim();
            if(! trimmed.isEmpty()) {
                sentenceList.add(trimmed);
            }
        }
        int totalSentences = sentenceList.size();

        final int finalCurrentParagraph = currentParagraph;

        new Thread(() -> {
            paragraphStartIndices.add(0);

            for (int i = 0; i < sentenceList.size(); i++) {
                String sentence = sentenceList.get(i);
                if (sentence.length() >= TextToSpeech.getMaxSpeechInputLength()) {
                    BreakIterator iterator = BreakIterator.getSentenceInstance();
                    iterator.setText(sentence);
                    int start = iterator.first();
                    for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
                        sentences.add(sentence.substring(start, end));
                    }
                } else {
                    sentences.add(sentence);
                }
                paragraphStartIndices.add(sentences.size());

                currentExtractProgress = Math.min((int) (((double) sentences.size() / totalSentences) * 100), 95);

                if (i % 3 == 0 || i == sentenceList.size() - 1) {
                    ContextCompat.getMainExecutor(context).execute(() -> {
                        if (webViewCallback != null) {
                            webViewCallback.updateLoadingProgress(currentExtractProgress);
                        }
                    });
                }
            }

            if (sentences.size() < 2) {
                if (webViewCallback != null) webViewCallback.askForReload(feedId);
                sentences.clear();
                actionNeeded = false;
                isSettingUpNewArticle = false;
                return;
            } else {
                if (finalCurrentParagraph != -1) {
                    int safeParagraphIndex = Math.min(finalCurrentParagraph, paragraphStartIndices.size() - 2);
                    sentenceCounter = paragraphStartIndices.get(Math.max(0, safeParagraphIndex));
                    entryRepository.updateSentCount(sentenceCounter, currentId);
                } else {
                    int savedProgress = entryRepository.getSentCount(currentId);
                    sentenceCounter = Math.min(savedProgress, sentences.size() - 1);
                }

                if (!isInit) {
                    actionNeeded = true;
                } else {
                    setupTts();
                }
            }
            
            isSettingUpNewArticle = false;
            // ❌ REMOVED: extractToTts() must NEVER call speak()
            // speak() is ONLY called from onPlayFromMediaId()
            countDownLatch.countDown();
        }).start();
    }

    public void setupTts() {
        Log.d(TAG, "setupTts() called.");
        
        if (tts == null) {
            Log.e(TAG, "TTS engine is null in setupTts(), cannot set language.");
            return;
        }
        
        if (sentences == null || sentences.isEmpty()) {
            Log.w(TAG, "No content to read in setupTts(), skipping language set.");
            return;
        }

        if (language == null || language.isEmpty()) {
            Log.w(TAG, "Warning: Language is null or empty, defaulting to English.");
            language = "en";
        }

        try {
            Log.d(TAG, "Setting TTS language to: " + language);

            Locale targetLocale;
            if (language.contains("-")) {
                String[] parts = language.split("-");
                targetLocale = new Locale(parts[0], parts.length > 1 ? parts[1] : "");
            } else {
                targetLocale = new Locale(language);
            }
            
            int result = tts.setLanguage(targetLocale);
            
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Language not supported by TTS engine: " + language + " (" + targetLocale.getDisplayName() + "). Defaulting to English.");
                tts.setLanguage(Locale.ENGLISH);
                language = "en";
            } else {
                Log.d(TAG, "TTS language successfully set to: " + targetLocale.getDisplayName() + " (" + language + ")");
            }
        } catch (Exception e) {
            Log.e(TAG, "Invalid locale provided to TTS engine: " + language, e);
            tts.setLanguage(Locale.ENGLISH);
            language = "en";
        }
    }

    private void identifyLanguage(String sentence, boolean fromService) {
        float confidenceThreshold = (float) sharedPreferencesRepository.getConfidenceThreshold() / 100;

        LanguageIdentifier languageIdentifier = LanguageIdentification.getClient(
                new LanguageIdentificationOptions.Builder()
                        .setConfidenceThreshold(confidenceThreshold)
                        .build());
        languageIdentifier.identifyLanguage(sentence)
                .addOnSuccessListener(languageCode -> {
                    if (languageCode.equals("und")) {
                        Log.i(TAG, "Can't identify language.");
                        setLanguage(Locale.ENGLISH, fromService);
                    } else {
                        Log.i(TAG, "Language: " + languageCode);
                        setLanguage(new Locale(languageCode), fromService);
                    }
                })
                .addOnFailureListener(Throwable::printStackTrace);
    }

    private void setLanguage(Locale locale, boolean fromService) {
        if (tts == null) {
            return;
        }
        int result = tts.setLanguage(locale);

        Log.d(TAG, "setLanguage() called with: " + locale.toString());
        Log.d(TAG, "setLanguage() result: " + result);

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.d(TAG, "Language not supported" + locale);
            if (webViewCallback != null) {
                webViewCallback.makeSnackbar("Language not installed. Required language: " + locale.getDisplayLanguage());
            }
            tts.setLanguage(Locale.ENGLISH);
        }
        else {
            Log.d(TAG, "Language successfully set to: " + locale);
        }

        if (fromService) {
            callback.onCustomAction("playFromService", null);
        } else {
            Log.d(TAG, "Language set. Waiting for speak() to be called.");
        }
    }

    /**
     * Controlled speech loop - only called from onPlayFromMediaId() and onDone()
     */
    public void speakNextSentence() {
        Log.e("TTS_TRACE", "🎯 speakNextSentence() called");
        Log.e("TTS_TRACE", "   sentenceCounter = " + sentenceCounter);
        Log.e("TTS_TRACE", "   sentences.size() = " + sentences.size());

        if (sentenceCounter >= sentences.size()) {
            Log.e("TTS_TRACE", "🏁 Article finished - no more sentences");
            setNewState(PlaybackStateCompat.STATE_PAUSED);
            return;
        }

        if (isSettingUpNewArticle || isPreparing) {
            Log.d(TAG, "speakNextSentence() skipped — TTS setup or preparation in progress.");
            return;
        }

        if (!isInit || tts == null) {
            Log.d(TAG, "speakNextSentence() skipped — TTS not initialized yet.");
            return;
        }

        String sentence = sentences.get(sentenceCounter);
        Log.e("TTS_TRACE", "▶️ Speaking sentence #" + sentenceCounter + ": " + sentence.substring(0, Math.min(50, sentence.length())) + "...");

        int result = tts.speak(
            sentence,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "sentence_" + sentenceCounter
        );

        Log.e("TTS_TRACE", "📢 TextToSpeech.speak() result = " + result);

        setUiControlPlayback(true);
        setNewState(PlaybackStateCompat.STATE_PLAYING);
        if (playbackUiListener != null) {
            playbackUiListener.onPlaybackStarted();
        }
        if (webViewCallback != null) {
            webViewCallback.highlightText(sentence);
        }
    }

    /**
     * LEGACY METHOD - kept for compatibility but should not be called directly
     * Use speakNextSentence() in the controlled speech loop instead
     */
    @Deprecated
    public void speak() {
        Log.w(TAG, "⚠️ speak() called - this should not happen in new architecture");
        Log.w(TAG, "   Use speakNextSentence() in controlled speech loop instead");
        speakNextSentence();
    }

    public void fastForward() {
        if (tts != null && sentenceCounter < sentences.size() - 1) {
            isManualSkip = true;
            sentenceCounter++;
            entryRepository.updateSentCount(sentenceCounter, currentId);
            tts.stop();
            // ✅ FIX #4: Skip paragraph must call speakNextSentence()
            speakNextSentence();
        } else {
            entryRepository.updateSentCount(0, currentId);
            callback.onSkipToNext();
        }
    }

    public void fastRewind() {
        if (tts != null && sentenceCounter > 0) {
            isManualSkip = true;
            sentenceCounter--;
            entryRepository.updateSentCount(sentenceCounter, currentId);
            tts.stop(); // interrupt current sentence
            // ✅ FIX: Skip previous paragraph must also call speakNextSentence()
            speakNextSentence();
        }
    }

    @Override
    public boolean isPlayingMediaPlayer() {
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }

    public void setupMediaPlayer(boolean forced) {
        if (forced) {
            stopMediaPlayer();
        }

        if (mediaPlayer == null && sharedPreferencesRepository.getBackgroundMusic()) {
            if (sharedPreferencesRepository.getBackgroundMusicFile().equals("default")) {
                mediaPlayer = MediaPlayer.create(context, R.raw.pianomoment);
            } else {
                File savedFile = new File(context.getFilesDir(), "user_file.mp3");
                if (savedFile.exists()) {
                    mediaPlayer = MediaPlayer.create(context, Uri.parse(savedFile.getAbsolutePath()));
                } else {
                    mediaPlayer = MediaPlayer.create(context, R.raw.pianomoment);
                }
            }
            mediaPlayer.setLooping(true);
            changeMediaPlayerVolume();
        }
        playMediaPlayer();
    }

    @Override
    public void playMediaPlayer() {
        if (mediaPlayer != null && !mediaPlayer.isPlaying()) mediaPlayer.start();
    }

    @Override
    public void pauseMediaPlayer() {
        if (mediaPlayer != null) mediaPlayer.pause();
    }

    public void changeMediaPlayerVolume() {
        if (mediaPlayer != null) {
            float volume = (float) sharedPreferencesRepository.getBackgroundMusicVolume() / 100;
            mediaPlayer.setVolume(volume, volume);
        }
    }

    public void stopMediaPlayer() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    @Override
    public boolean isPlaying() {
        return tts != null && tts.isSpeaking();
    }

    @Override
    protected void onPlay() {
        if (tts != null && !isPausedManually) {
            speak();
            setNewState(PlaybackStateCompat.STATE_PLAYING);
        }
    }

    @Override
    protected void onPause() {
        if (tts != null && tts.isSpeaking()) {
            tts.stop();
        }
        setNewState(PlaybackStateCompat.STATE_PAUSED);
    }

    @Override
    protected void onStop() {
        stopMediaPlayer();
        Log.d(TAG, " player stopped");
        if (tts != null) {
            tts.stop();  // Stop speaking, but DON'T shutdown TTS engine
            // TTS engine should persist and be reused
        }
        currentId = 0;
        setNewState(PlaybackStateCompat.STATE_STOPPED);
    }

    private void setNewState(@PlaybackStateCompat.State int state) {
        if (listener != null) {
            currentState = state;
            final PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder();
            stateBuilder.setActions(getAvailableActions());
            stateBuilder.setState(currentState, 0, 1.0f, SystemClock.elapsedRealtime());
            listener.onPlaybackStateChange(stateBuilder.build());
        }
    }

    @PlaybackStateCompat.Actions
    private long getAvailableActions() {
        long actions = PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_REWIND
                | PlaybackStateCompat.ACTION_FAST_FORWARD
                | PlaybackStateCompat.ACTION_PLAY_PAUSE;  // CRITICAL: Always include play/pause toggle
        
        switch (currentState) {
            case PlaybackStateCompat.STATE_STOPPED:
                actions |= PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PAUSE;
                break;
            case PlaybackStateCompat.STATE_PLAYING:
                actions |= PlaybackStateCompat.ACTION_PLAY  // CRITICAL: Include PLAY for toggle
                        | PlaybackStateCompat.ACTION_STOP
                        | PlaybackStateCompat.ACTION_PAUSE;
                break;
            case PlaybackStateCompat.STATE_PAUSED:
                actions |= PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_STOP
                        | PlaybackStateCompat.ACTION_PAUSE;  // CRITICAL: Include PAUSE for toggle
                break;
            default:
                actions |= PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_STOP
                        | PlaybackStateCompat.ACTION_PAUSE;
        }
        
        Log.d(TAG, "getAvailableActions() for state " + currentState + ": " + actions);
        return actions;
    }

    public void setTtsSpeechRate(float speechRate) {
        if (speechRate == 0) {
            try {
                int systemRate = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.TTS_DEFAULT_RATE);
                speechRate = systemRate / 100.0f;
            } catch (Settings.SettingNotFoundException e) {
                e.printStackTrace();
                speechRate = 1.0f;
            }
        }
        tts.setSpeechRate(speechRate);
    }

    public void setWebViewCallback(WebViewListener listener) {
        this.webViewCallback = listener;
    }

    public WebViewListener getWebViewCallback() {
        return webViewCallback;
    }

    public void showFakeLoading() {
        if (webViewCallback != null) {
            webViewCallback.showFakeLoading();
        }
    }

    public void hideFakeLoading() {
        if (webViewCallback != null) {
            webViewCallback.hideFakeLoading();
        }
    }

    public boolean ttsIsNull() {
        return tts == null;
    }

    /**
     * Properly shutdown TTS engine when app is being destroyed
     * This should only be called when the entire app is closing
     */
    public void shutdownTts() {
        Log.d(TAG, "Shutting down TTS engine completely");
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
            isInit = false;
        }
    }

    public boolean isWebViewConnected() {
        return webViewConnected;
    }

    public void setWebViewConnected(boolean isConnected) {
        this.webViewConnected = isConnected;
    }

    public boolean isUiControlPlayback() {
        return uiControlPlayback;
    }

    public void setUiControlPlayback(boolean isUiControlPlayback) {
        this.uiControlPlayback = isUiControlPlayback;
    }

    public long getCurrentId() {
        return currentId;
    }

    public boolean isPausedManually() {
        return isPausedManually;
    }

    public void setPausedManually(boolean isPaused) {
        sharedPreferencesRepository.setIsPausedManually(isPaused);
        isPausedManually = isPaused;
    }

    public boolean isPreparing() {
        return isPreparing;
    }

    public boolean isSpeaking() {
        return tts != null && tts.isSpeaking();
    }

    public int getCurrentIdPlaying() {
        return (int) currentId;
    }

    public int getCurrentExtractProgress() {
        return currentExtractProgress;
    }

    public void resetStateForNewArticle() {
        Log.d(TAG, "Resetting player state for new article.");
        this.isArticleFinished = false;
        this.sentenceCounter = 0;
        this.isManualSkip = false;
        this.sentences.clear();
        this.paragraphStartIndices.clear();
    }

    /**
     * Reset sentence counter for controlled speech loop
     */
    public void resetSentenceCounter() {
        Log.e("TTS_TRACE", "🔄 resetSentenceCounter() called");
        this.sentenceCounter = 0;
        this.isArticleFinished = false;
        this.isManualSkip = false;
    }

    /**
     * Set callback to be invoked when extract() finishes
     */
    public void setOnReadyToSpeak(Runnable callback) {
        this.onReadyToSpeak = callback;
    }

    public int getCurrentParagraphIndex() {
        if (paragraphStartIndices.isEmpty()) return 0;
        for (int i = 0; i < paragraphStartIndices.size() - 1; i++) {
            if (sentenceCounter >= paragraphStartIndices.get(i) && sentenceCounter < paragraphStartIndices.get(i + 1)) {
                return i;
            }
        }
        return Math.max(0, paragraphStartIndices.size() - 2);
    }

    public List<String> getSentences() {
        return sentences;
    }

    public List<Integer> getParagraphStartIndices() {
        return paragraphStartIndices;
    }

    public int getCurrentSentenceIndex() {
        return sentenceCounter;
    }
}
