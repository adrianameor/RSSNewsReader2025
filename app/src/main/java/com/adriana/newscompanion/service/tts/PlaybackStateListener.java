package com.adriana.newscompanion.service.tts;

import android.support.v4.media.session.PlaybackStateCompat;

public abstract class PlaybackStateListener {

    public abstract void onPlaybackStateChange(PlaybackStateCompat state);
}
