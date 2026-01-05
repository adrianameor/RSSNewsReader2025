package com.adriana.newscompanion.data.sharedpreferences;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import javax.inject.Inject;
import dagger.hilt.android.qualifiers.ApplicationContext;

public class SharedPreferencesRepository {

    private final SharedPreferences sharedPreferences;

    @Inject
    public SharedPreferencesRepository(@ApplicationContext Context context) {
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    }

    private SharedPreferences.Editor getEditor() {
        return sharedPreferences.edit();
    }

    public int getJobPeriodic() {
        return Integer.parseInt(sharedPreferences.getString("jobPeriodic", "0"));
    }

    public void setInitialJobPeriodic() {
        getEditor().putString("jobPeriodic", "360").commit();
    }

    public void setJobPeriodic(String jobPeriodic) {
        getEditor().putString("jobPeriodic", jobPeriodic).commit();
    }

    public boolean getNight() {
        return sharedPreferences.getBoolean("night", false);
    }

    public void setNight(boolean isNight) {
        getEditor().putBoolean("night", isNight).commit();
    }

    public boolean isSummarizationEnabled() {
        return sharedPreferences.getBoolean("displaySummary", false);
    }

    public void setDisplaySummary(boolean displaySummary) {
        getEditor().putBoolean("displaySummary", displaySummary).commit();
    }

    public boolean getHighlightText() {
        return sharedPreferences.getBoolean("highlightText", true);
    }

    public void setHighlightText(boolean highlightText) {
        getEditor().putBoolean("highlightText", highlightText).commit();
    }

    public void setTextZoom(int textZoom) {
        getEditor().putInt("textZoom", textZoom).commit();
    }

    public int getTextZoom() {
        return sharedPreferences.getInt("textZoom", 0);
    }

    public void setSortBy(String sortBy) {
        getEditor().putString("sortBy", sortBy).commit();
    }

    public String getSortBy() {
        // CHANGED: Default value from "oldest" to "latest"
        return sharedPreferences.getString("sortBy", "latest");
    }

    public int getConfidenceThreshold() {
        return sharedPreferences.getInt("confidenceThreshold", 50);
    }

    public void setConfidenceThreshold(int confidenceThreshold) {
        getEditor().putInt("confidenceThreshold", confidenceThreshold).commit();
    }

    public boolean getBackgroundMusic() {
        return sharedPreferences.getBoolean("backgroundMusic", false);
    }

    public void setBackgroundMusic(boolean backgroundMusic) {
        getEditor().putBoolean("backgroundMusic", backgroundMusic).commit();
    }

    public String getBackgroundMusicFile() {
        return sharedPreferences.getString("backgroundMusicFile", "default");
    }

    public void setBackgroundMusicFile(String file) {
        getEditor().putString("backgroundMusicFile", file).commit();
    }

    public int getBackgroundMusicVolume() {
        return sharedPreferences.getInt("backgroundMusicVolume", 50);
    }

    public void setBackgroundMusicVolume(int volume) {
        getEditor().putInt("backgroundMusicVolume", volume).commit();
    }

    public int getEntriesLimitPerFeed() {
        return sharedPreferences.getInt("entriesLimitPerFeed", 1000);
    }

    public void setEntriesLimitPerFeed(int limit) {
        getEditor().putInt("entriesLimitPerFeed", limit).commit();
    }

    public boolean getIsPausedManually() {
        return sharedPreferences.getBoolean("isPausedManually", false);
    }

    public void setIsPausedManually(boolean isPaused) {
        getEditor().putBoolean("isPausedManually", isPaused).commit();
    }

    public String getDefaultTranslationLanguage() {
        return sharedPreferences.getString("defaultTranslationLanguage", "zh");
    }



    public void setDefaultTranslationLanguage(String language) {
        getEditor().putString("defaultTranslationLanguage", language).commit();
    }

    public String getTranslationMethod() {
        return sharedPreferences.getString("translationMethod", "allAtOnce");
    }

    public void setTranslationMethod(String method) {
        getEditor().putString("translationMethod", method).commit();
    }

    public boolean getAutoTranslate() {
        return sharedPreferences.getBoolean("autoTranslate", false);
    }

    public void setAutoTranslate(boolean autoTranslate) {
        getEditor().putBoolean("autoTranslate", autoTranslate).commit();
    }

    public void setIsTranslatedView(long entryId, boolean isTranslatedView) {
        getEditor().putBoolean("is_translated_view_" + entryId, isTranslatedView).commit();
    }

    public boolean getIsTranslatedView(long entryId) {
        return sharedPreferences.getBoolean("is_translated_view_" + entryId,false);
    }

    public boolean hasTranslationToggle(long entryId) {
        return sharedPreferences.contains("is_translated_view_" + entryId);
    }

    public void setScrollX(long entryId, int value) {
        getEditor().putInt("scroll_x_" + entryId, value).commit();
    }

    public void setScrollY(long entryId, int value) {
        getEditor().putInt("scroll_y_" + entryId, value).commit();
    }

    public int getScrollX(long entryId) {
        return sharedPreferences.getInt("scroll_x_" + entryId, 0);
    }

    public int getScrollY(long entryId) {
        return sharedPreferences.getInt("scroll_y_" + entryId, 0);
    }

    public void setWebViewMode(long entryId, boolean isWebViewMode) {
        getEditor().putBoolean("web_view_mode_" + entryId, isWebViewMode).commit();
    }

    public boolean getWebViewMode(long entryId) {
        return sharedPreferences.getBoolean("web_view_mode_" + entryId, false);
    }

    public void setCurrentReadingEntryId(long entryId) {
        getEditor().putLong("current_reading_entry_id", entryId).commit();
    }

    public long getCurrentReadingEntryId() {
        return sharedPreferences.getLong("current_reading_entry_id", -1);
    }

    public int getAiSummaryLength() {
        return sharedPreferences.getInt("ai_summary_length", 200);
    }

    public boolean isAiCleaningEnabled() {
        return sharedPreferences.getBoolean("ai_cleaning_enabled", true);
    }

    public void setCurrentTtsContent(String content) {
        getEditor().putString("current_tts_content", content).commit();
    }

    public String getCurrentTtsContent() {
        return sharedPreferences.getString("current_tts_content", null);
    }

    public void setCurrentTtsLang(String lang) {
        getEditor().putString("current_tts_lang", lang).commit();
    }

    public String getCurrentTtsLang() {
        return sharedPreferences.getString("current_tts_lang", null);
    }

    public void setCurrentTtsPosition(int position) {
        getEditor().putInt("current_tts_position", position).commit();
    }

    public int getCurrentTtsPosition() {
        return sharedPreferences.getInt("current_tts_position", 0);
    }

    public void setCurrentTtsSentencePosition(int position) {
        getEditor().putInt("current_tts_sentence_position", position).commit();
    }

    public int getCurrentTtsSentencePosition() {
        return sharedPreferences.getInt("current_tts_sentence_position", 0);
    }
}
