package com.adriana.newscompanion.di;

import android.app.Application;

import androidx.room.Room;

import com.adriana.newscompanion.data.database.AppDatabase;
import com.adriana.newscompanion.data.entry.EntryDao;
import com.adriana.newscompanion.data.feed.FeedDao;
import com.adriana.newscompanion.data.history.HistoryDao;
import com.adriana.newscompanion.data.playlist.PlaylistDao;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;

@Module
@InstallIn(SingletonComponent.class)
public class AppModule {

    @Provides
    @Singleton
    public static AppDatabase provideDatabase(Application app, AppDatabase.Callback callback) {
        return Room.databaseBuilder(app, AppDatabase.class, "app_database")
                .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12)
                .addCallback(callback)
                .allowMainThreadQueries()
                .build();
    }

    @Provides
    public static FeedDao provideFeedDao(AppDatabase db) {
        return db.feedDao();
    }

    @Provides
    public static EntryDao provideEntryDao(AppDatabase db) {
        return db.entryDao();
    }

    @Provides
    public static PlaylistDao providePlaylistDao(AppDatabase db) {
        return db.playlistDao();
    }

    @Provides
    public static HistoryDao provideHistoryDao(AppDatabase db) {
        return db.historyDao();
    }
}
