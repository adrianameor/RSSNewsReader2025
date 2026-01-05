package com.adriana.newscompanion.ui.setting;

import static android.app.Activity.RESULT_OK;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.adriana.newscompanion.R;
import com.adriana.newscompanion.data.sharedpreferences.SharedPreferencesRepository;
import com.adriana.newscompanion.service.rss.RssWorkManager;
import com.adriana.newscompanion.service.tts.TtsPlayer;
import com.adriana.newscompanion.ui.main.MainActivity;
import com.adriana.newscompanion.util.AiCleaningTrigger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class SettingsFragment extends PreferenceFragmentCompat {

    @Inject
    RssWorkManager rssWorkManager;

    @Inject
    TtsPlayer ttsPlayer;

    @Inject
    SharedPreferencesRepository sharedPreferencesRepository;

    private ListPreference backgroundMusicFilePreference;
    private boolean isAdditionalImport;
    private final CharSequence[] defaultMusicEntries = {"Default", "Import music file (ogg format is preferred)"};
    private final CharSequence[] defaultMusicValues = {"default", "userFile"};
    private final CharSequence[] extendedMusicEntries  = {"Default", "Imported music file", "Import another music file (ogg format is preferred)"};
    private final CharSequence[] extendedMusicValues  = {"default", "userFile", "addUserFile"};

    private SharedPreferences.OnSharedPreferenceChangeListener listener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            switch (key) {
                case "jobPeriodic":
                    rssWorkManager.enqueueRssWorker();
                    break;
                case "autoTranslate":
                case "displaySummary":
                    // FIX: If user enables Translation or Summarization, start the sync immediately
                    // to process existing articles.
                    if (sharedPreferences.getBoolean(key, false)) {
                        Log.d("SettingsFragment", key + " enabled. Triggering sync engine.");
                        rssWorkManager.enqueueRssWorker();
                        Toast.makeText(requireContext(), "Processing articles...", Toast.LENGTH_SHORT).show();
                    }
                    break;
                case "ai_cleaning_enabled":
                    if (sharedPreferences.getBoolean(key, false)) {
                        Log.d("SettingsFragment", "AI Cleaning enabled. Triggering cleaner.");
                        AiCleaningTrigger.triggerAiCleaning(requireContext());
                        Toast.makeText(requireContext(), "Cleaning articles...", Toast.LENGTH_SHORT).show();
                    }
                    break;
                case "sortBy":
                    if (sharedPreferencesRepository.isAiCleaningEnabled()) {
                        AiCleaningTrigger.triggerAiCleaning(requireContext());
                    }
                    break;
                case "night":
                    if (sharedPreferencesRepository.getNight()) {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                    } else {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                    }
                    ((MainActivity) getActivity()).updateThemeSwitch();
                    break;
                case "backgroundMusic":
                    if (ttsPlayer.isPlayingMediaPlayer()) {
                        if (sharedPreferencesRepository.getBackgroundMusic()) {
                            ttsPlayer.setupMediaPlayer(false);
                        } else {
                            ttsPlayer.stopMediaPlayer();
                        }
                    }
                    break;
                case "backgroundMusicFile":
                    String musicFile = sharedPreferencesRepository.getBackgroundMusicFile();
                    if (!musicFile.equals("default")) {
                        if (!musicFile.equals("userFile")) {
                            isAdditionalImport = true;
                            sharedPreferencesRepository.setBackgroundMusicFile("userFile");
                            backgroundMusicFilePreference.setValue("userFile");
                        } else {
                            backgroundMusicFilePreference.setEntries(extendedMusicEntries);
                            backgroundMusicFilePreference.setEntryValues(extendedMusicValues);
                            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                            intent.setType("audio/*");
                            saveMusicFileLauncher.launch(intent);
                        }
                    } else {
                        backgroundMusicFilePreference.setEntries(defaultMusicEntries);
                        backgroundMusicFilePreference.setEntryValues(defaultMusicValues);
                        if (ttsPlayer.isPlayingMediaPlayer()) {
                            ttsPlayer.setupMediaPlayer(true);
                        }
                    }
                    break;
                case "backgroundMusicVolume":
                    ttsPlayer.changeMediaPlayerVolume();
                    break;
            }
        }
    };

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey);

        backgroundMusicFilePreference = findPreference("backgroundMusicFile");

        if (!sharedPreferencesRepository.getBackgroundMusicFile().equals("default")) {
            backgroundMusicFilePreference.setEntries(extendedMusicEntries);
            backgroundMusicFilePreference.setEntryValues(extendedMusicValues);
        } else {
            backgroundMusicFilePreference.setEntries(defaultMusicEntries);
            backgroundMusicFilePreference.setEntryValues(defaultMusicValues);
        }

        Preference ttsSettingsPreference = findPreference("key_text_to_speech_settings");
        if (ttsSettingsPreference != null) {
            ttsSettingsPreference.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
                return true;
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        Objects.requireNonNull(getPreferenceManager().getSharedPreferences()).registerOnSharedPreferenceChangeListener(listener);
    }

    @Override
    public void onPause() {
        super.onPause();
        Objects.requireNonNull(getPreferenceManager().getSharedPreferences()).unregisterOnSharedPreferenceChangeListener(listener);
    }

    private final ActivityResultLauncher<Intent> saveMusicFileLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Intent data = result.getData();
                    if (data != null) {
                        handleSelectedFile(data.getData());
                    }
                } else {
                    if (isAdditionalImport) {
                        isAdditionalImport = false;
                    } else {
                        sharedPreferencesRepository.setBackgroundMusicFile("default");
                        backgroundMusicFilePreference.setValue("default");
                    }
                    Toast.makeText(requireContext(), "File selection canceled or failed", Toast.LENGTH_SHORT).show();
                }
            });

    private void handleSelectedFile(Uri fileUri) {
        File internalStorageDir = getActivity().getFilesDir();
        File destinationFile = new File(internalStorageDir, "user_file.mp3");
        try {
            InputStream inputStream = getActivity().getContentResolver().openInputStream(fileUri);
            OutputStream outputStream = new FileOutputStream(destinationFile);
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.close();
            inputStream.close();
            Toast.makeText(requireContext(), "File imported successfully", Toast.LENGTH_SHORT).show();
            if (ttsPlayer.isPlayingMediaPlayer()) {
                ttsPlayer.setupMediaPlayer(true);
            }
        } catch (IOException e) {
            sharedPreferencesRepository.setBackgroundMusicFile("default");
            e.printStackTrace();
        }
    }
}
