package com.adriana.newscompanion.model;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/**
 * Single source of truth for TTS playback state.
 * UI builds this and sends to Service. Service ONLY uses this data.
 * Service NEVER queries database or makes decisions.
 */
public class PlaybackStateModel implements Parcelable {
    
    // Playlist state
    public List<Long> entryIds;        // Complete playlist in order
    public int currentIndex;            // Current position in playlist
    
    // Playback mode
    public PlaybackMode mode;           // SUMMARY / FULL / TRANSLATED
    
    // Content to play (AUTHORITATIVE - Service must use this exactly)
    public String textToRead;           // EXACT text to speak (never replaced)
    public String language;             // Language for TTS
    
    // Metadata
    public long currentEntryId;         // Current article ID
    public String entryTitle;           // For notification
    public String feedTitle;            // For notification
    
    public PlaybackStateModel() {
        this.entryIds = new ArrayList<>();
    }
    
    // Parcelable implementation
    protected PlaybackStateModel(Parcel in) {
        entryIds = new ArrayList<>();
        in.readList(entryIds, Long.class.getClassLoader());
        currentIndex = in.readInt();
        mode = PlaybackMode.valueOf(in.readString());
        textToRead = in.readString();
        language = in.readString();
        currentEntryId = in.readLong();
        entryTitle = in.readString();
        feedTitle = in.readString();
    }
    
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeList(entryIds);
        dest.writeInt(currentIndex);
        dest.writeString(mode.name());
        dest.writeString(textToRead);
        dest.writeString(language);
        dest.writeLong(currentEntryId);
        dest.writeString(entryTitle);
        dest.writeString(feedTitle);
    }
    
    @Override
    public int describeContents() {
        return 0;
    }
    
    public static final Creator<PlaybackStateModel> CREATOR = new Creator<PlaybackStateModel>() {
        @Override
        public PlaybackStateModel createFromParcel(Parcel in) {
            return new PlaybackStateModel(in);
        }
        
        @Override
        public PlaybackStateModel[] newArray(int size) {
            return new PlaybackStateModel[size];
        }
    };
    
    /**
     * Playback mode determines which content version is being played
     */
    public enum PlaybackMode {
        FULL,           // Original article content
        SUMMARY,        // AI-generated summary
        TRANSLATED      // Translated content
    }
}
