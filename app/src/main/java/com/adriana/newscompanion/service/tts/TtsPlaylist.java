package com.adriana.newscompanion.service.tts;

import android.graphics.Bitmap;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.util.Log;

import com.adriana.newscompanion.data.entry.EntryRepository;
import com.adriana.newscompanion.data.playlist.PlaylistRepository;
import com.adriana.newscompanion.model.EntryInfo;
import com.squareup.picasso.Picasso;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class TtsPlaylist {

    private static final String TAG = "TtsPlaylist";

    private final EntryRepository entryRepository;
    private final PlaylistRepository playlistRepository;

    // --- STATE VARIABLES to hold the current playlist in memory ---
    private List<Long> playlistIds = new ArrayList<>();
    private int currentIndex = -1;
    // ---

    @Inject
    public TtsPlaylist(EntryRepository entryRepository, PlaylistRepository playlistRepository) {
        this.entryRepository = entryRepository;
        this.playlistRepository = playlistRepository;
    }

    // --- THIS IS THE FIX ---
    // This new method replaces the old one. It doesn't load anything itself.
    // It simply accepts the fresh, correct playlist data that the TtsService will provide.
    public void setPlaylist(List<Long> newPlaylistIds, long currentId) {
        if (newPlaylistIds == null || newPlaylistIds.isEmpty()) {
            // As a fallback, if the service gives us a bad list, create a list
            // with just the current item to prevent crashes.
            Log.e(TAG, "setPlaylist was called with an empty or null list. Creating a fallback playlist.");
            this.playlistIds = new ArrayList<>();
            this.playlistIds.add(currentId);
            this.currentIndex = 0;
            return;
        }

        // Set the playlist from the provided fresh list and find the starting index.
        this.playlistIds = new ArrayList<>(newPlaylistIds); // Make a copy to be safe.
        this.currentIndex = this.playlistIds.indexOf(currentId);

        // If for some reason the currentId isn't in the list, default to the start.
        if (this.currentIndex == -1) {
            Log.w(TAG, "Current ID (" + currentId + ") not found in the new playlist. Defaulting to index 0.");
            if (!this.playlistIds.isEmpty()) {
                this.currentIndex = 0;
            }
        }

        Log.d(TAG, "Playlist has been set. Item count: " + this.playlistIds.size() + ". Current index: " + this.currentIndex);
    }

    public List<MediaBrowserCompat.MediaItem> getMediaItems() {
        MediaMetadataCompat metadata = getCurrentMetadata();
        if (metadata == null) {
            return new ArrayList<>();
        }
        List<MediaBrowserCompat.MediaItem> result = new ArrayList<>();
        result.add(new MediaBrowserCompat.MediaItem(metadata.getDescription(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
        return result;
    }

    public MediaMetadataCompat getCurrentMetadata() {
        // --- CORRECTED LOGIC ---
        // 1. Get the current ID from our internal state.
        long currentId = getPlayingId();
        if (currentId == -1) {
            Log.e(TAG, "getCurrentMetadata failed: currentId is -1. The playlist is likely empty or has not been loaded by the service yet.");
            return null; // Return null if there's no valid current item. This is expected on first load.
        }

        // 2. Fetch the EntryInfo for the CORRECT article.
        EntryInfo entryInfo = entryRepository.getEntryInfoById(currentId);
        if (entryInfo == null) {
            Log.e(TAG, "getCurrentMetadata failed: EntryInfo not found for ID: " + currentId);
            return null;
        }

        // The rest of your metadata creation logic was good and remains.
        final String content = entryRepository.getContentById(entryInfo.getEntryId());
        final String html = entryRepository.getHtmlById(entryInfo.getEntryId());
        final String translated = entryRepository.getTranslatedTextById(entryInfo.getEntryId());
        final Bitmap[] feedImage = {null};

        Thread thread = new Thread(() -> {
            try {
                if(entryInfo.getFeedImageUrl() != null && !entryInfo.getFeedImageUrl().isEmpty()) {
                    feedImage[0] = Picasso.get().load(entryInfo.getFeedImageUrl()).get();
                }
            } catch (IOException e) {
                Log.e(TAG, "Picasso failed to load image for feed: " + entryInfo.getFeedImageUrl(), e);
            }
        });
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            Log.e(TAG, "Picasso thread interrupted", e);
        }

        // 3. Build the metadata with the correct data.
        return new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, Long.toString(entryInfo.getEntryId()))
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, entryInfo.getFeedTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, entryInfo.getEntryTitle())
                .putString("link", entryInfo.getEntryLink())
                .putString("content", content)
                .putString("translated", translated)
                .putString("html", html)
                .putString("language", entryInfo.getFeedLanguage())
                .putLong("date", entryInfo.getEntryPublishedDate().getTime())
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, feedImage[0])
                .putString("feedImageUrl", entryInfo.getFeedImageUrl())
                .putString("entryImageUrl", entryInfo.getEntryImageUrl())
                .putString("bookmark", entryInfo.getBookmark())
                .putLong("feedId", entryInfo.getFeedId())
                .putString("ttsSpeechRate", Float.toString(entryInfo.getTtsSpeechRate()))
                .build();
    }

    // --- UPDATED SKIP METHODS that use in-memory state ---
    public boolean skipPrevious() {
        if (currentIndex > 0) {
            currentIndex--;
            Log.d(TAG, "Skipped to PREVIOUS. New index: " + currentIndex);
            return true;
        }
        Log.w(TAG, "Cannot skip previous. Already at the start of the playlist.");
        return false;
    }

    public boolean skipNext() {
        if (playlistIds != null && currentIndex < playlistIds.size() - 1) {
            currentIndex++;
            Log.d(TAG, "Skipped to NEXT. New index: " + currentIndex);
            return true;
        }
        Log.w(TAG, "Cannot skip next. Already at the end of the playlist.");
        return false;
    }

    public void updatePlayingId(long id) {
        int newIndex = playlistIds.indexOf(id);
        if (newIndex != -1) {
            this.currentIndex = newIndex;
        }
    }

    public long getPlayingId() {
        if (playlistIds != null && currentIndex >= 0 && currentIndex < playlistIds.size()) {
            return playlistIds.get(currentIndex);
        }
        return -1;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public int size() {
        return playlistIds != null ? playlistIds.size() : 0;
    }

    public long get(int index) {
        if (playlistIds != null && index >= 0 && index < playlistIds.size()) {
            return playlistIds.get(index);
        }
        return -1;
    }

    public int indexOf(long id) {
        return playlistIds != null ? playlistIds.indexOf(id) : -1;
    }

    // --- Private helper method to parse the ID list ---
    private List<Long> stringToLongList(String idListString) {
        List<Long> list = new ArrayList<>();
        if (idListString == null || idListString.isEmpty()) {
            return list;
        }
        String[] array = idListString.split(",");
        for (String s : array) {
            if (!s.isEmpty()) {
                try {
                    list.add(Long.parseLong(s));
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Failed to parse long from playlist string: " + s);
                }
            }
        }
        return list;
    }
}