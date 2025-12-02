package com.adriana.newscompanion.data.playlist;

import android.util.Log;

import com.adriana.newscompanion.data.entry.EntryRepository;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.inject.Inject;

public class PlaylistRepository {

    private static final String TAG = "PlaylistRepository";
    private PlaylistDao playlistDao;
    private EntryRepository entryRepository;

    @Inject
    public PlaylistRepository(PlaylistDao playlistDao, EntryRepository entryRepository) {
        this.playlistDao = playlistDao;
        this.entryRepository = entryRepository;
    }

    public void insert(Playlist playlist) {
        playlistDao.insert(playlist);
    }

    public void update(Playlist playlist) {
        playlistDao.update(playlist);
    }

    public void delete(Playlist playlist) {
        playlistDao.delete(playlist);
    }

    public void deleteAllPlaylists() {
        playlistDao.deleteAllPlaylists();
    };

    public Date getLatestPlaylistCreatedDate() {
        return playlistDao.getLatestPlaylistCreatedDate();
    }

    public String getLatestPlaylist() {
        String playlist = playlistDao.getLatestPlaylist();
        Log.d(TAG, "getLatestPlaylist() returning: " + (playlist == null ? "NULL" : playlist));
        return playlist;
    }

    public boolean updatePlaylistToPrevious() {
        String playlistString = playlistDao.getLatestPlaylist();

        // ADD NULL CHECK HERE
        if (playlistString == null || playlistString.isEmpty()) {
            Log.e(TAG, "updatePlaylistToPrevious: Playlist is null or empty");
            return false;
        }

        List<Long> playlist = stringToLongList(playlistString);

        // Also check if playlist is empty after parsing
        if (playlist.isEmpty()) {
            Log.w(TAG, "updatePlaylistToPrevious: Playlist is empty after parsing");
            return false;
        }

        boolean loop = true;
        long lastId = entryRepository.getLastVisitedEntryId();
        int index = playlist.indexOf(lastId);

        while (loop) {
            index -= 1;
            if (index >= 0) {
                long currentId = playlist.get(index);
                if (entryRepository.checkIdExist(currentId)) {
                    Date date = new Date();
                    entryRepository.updateDate(date, currentId);
                    return true;
                }
            } else {
                loop = false;
            }
        }
        return false;
    }

    public boolean updatePlayListToNext() {
        String playlistString = playlistDao.getLatestPlaylist();

        if (playlistString == null || playlistString.isEmpty()) {
            Log.e(TAG, "updatePlayListToNext: Playlist is null or empty");
            return false;
        }

        List<Long> playlist = stringToLongList(playlistString);

        if (playlist.isEmpty()) {
            Log.w(TAG, "updatePlayListToNext: Playlist is empty after parsing");
            return false;
        }

        long lastId = entryRepository.getLastVisitedEntryId();
        int index = playlist.indexOf(lastId);

        // CHECK IF LAST ID IS IN THE PLAYLIST
        if (index == -1) {
            Log.w(TAG, "updatePlayListToNext: Last ID " + lastId + " not found in playlist");
            // Maybe start from the beginning?
            index = 0;
        }

        boolean loop = true;
        while (loop) {
            index += 1;
            if (index < playlist.size()) {
                long currentId = playlist.get(index);
                if (entryRepository.checkIdExist(currentId)) {
                    Date date = new Date();
                    entryRepository.updateDate(date, currentId);
                    return true;
                }
            } else {
                loop = false;
            }
        }
        return false;
    }

    public List<Long> stringToLongList(String genreIds) {
        List<Long> list = new ArrayList<>();

        // ADD NULL CHECK AT THE BEGINNING
        if (genreIds == null || genreIds.isEmpty()) {
            Log.w(TAG, "stringToLongList: genreIds is null or empty");
            return list; // Return empty list instead of crashing
        }

        String[] array = genreIds.split(",");

        for (String s : array) {
            if (s != null && !s.isEmpty()) {
                try {
                    list.add(Long.parseLong(s));
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Error parsing playlist ID: " + s, e);
                    // Skip invalid entries but don't crash
                }
            }
        }
        return list;
    }
}
