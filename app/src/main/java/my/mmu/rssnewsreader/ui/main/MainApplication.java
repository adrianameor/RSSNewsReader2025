package my.mmu.rssnewsreader.ui.main;

import android.app.Application;
import android.util.Log;

import androidx.hilt.work.HiltWorkerFactory;
import androidx.work.Configuration;
import javax.inject.Inject;
import dagger.hilt.android.HiltAndroidApp;
import my.mmu.rssnewsreader.service.rss.RssWorkManager;

// This annotation tells Hilt this is the main application class.
@HiltAndroidApp
// THIS IS A CRITICAL PART: We implement Configuration.Provider to setup Hilt for WorkManager.
public class MainApplication extends Application implements Configuration.Provider {

    // Hilt will inject the worker factory that knows how to create our Hilt-powered workers.
    @Inject
    HiltWorkerFactory workerFactory;

    // We ask Hilt to give us our RssWorkManager instance.
    @Inject
    RssWorkManager rssWorkManager;

    // This method is required by Configuration.Provider.
    // It tells WorkManager to use Hilt's factory to build our workers.
    // This is essential for the @HiltWorker annotation to function.
    @Override
    public Configuration getWorkManagerConfiguration() {
        return new Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .setMinimumLoggingLevel(Log.DEBUG) // Helpful for debugging workers in Logcat
                .build();
    }

    // THIS IS THE FIX: The onCreate method is called once when the app starts.
    // This is the "key turn" that has been missing this entire time.
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d("MainApplication", "RssWorkManager has been enqueued on app startup.");
    }
}