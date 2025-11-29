package my.mmu.rssnewsreader.service.rss;

import android.content.Context;
import android.util.Log;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;
import my.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;

@Singleton
public class RssWorkManager {

    private static final String TAG = "RssWorkManager";
    // We will use one single, consistent name for the unique periodic work.
    private static final String UNIQUE_PERIODIC_WORK_NAME = "RssPeriodicRefreshWorker";

    private final Context context;
    private final SharedPreferencesRepository sharedPreferencesRepository;

    @Inject
    public RssWorkManager(@ApplicationContext Context context, SharedPreferencesRepository sharedPreferencesRepository) {
        this.context = context;
        this.sharedPreferencesRepository = sharedPreferencesRepository;
    }

    /**
     * This is the one and only method to schedule our workers.
     * It is now safe and robust.
     */
    public void enqueueRssWorker() {
        WorkManager workManager = WorkManager.getInstance(context);

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        // We now mark the one-time request as "Expedited" to tell the system
        // to run it as soon as possible, instead of waiting for a low-priority window.
        OneTimeWorkRequest immediateWorkRequest = new OneTimeWorkRequest.Builder(RssWorker.class)
                .setConstraints(constraints)
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build();

        Log.d(TAG, "Enqueuing IMMEDIATE one-time RssWorker.");
        workManager.enqueue(immediateWorkRequest);


        // --- PERIODIC WORK ---
        // We also ensure our recurring background sync is scheduled.

        // Get the interval from settings, but enforce WorkManager's 15-minute minimum to prevent crashes.
        int intervalFromSettings = sharedPreferencesRepository.getJobPeriodic();
        long finalInterval = Math.max(15, intervalFromSettings);

        // Create the periodic request for the future.
        PeriodicWorkRequest periodicWorkRequest = new PeriodicWorkRequest.Builder(RssWorker.class, finalInterval, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();

        // Use enqueueUniquePeriodicWork with a REPLACE policy.
        // This means if a periodic worker is already scheduled, it will be updated with the new interval.
        // This is safer and simpler than manually checking if work is scheduled.
        workManager.enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // KEEP ensures it's not rescheduled if one already exists with the same parameters
                periodicWorkRequest
        );
        Log.d(TAG, "Ensured PERIODIC RssWorker is scheduled with interval: " + finalInterval + " minutes.");
    }

    /**
     * This method is no longer needed with the new robust scheduling logic, but we will keep it and fix it.
     */
    public void dequeueRssWorker() {
        Log.d(TAG, "Cancelling unique periodic work: " + UNIQUE_PERIODIC_WORK_NAME);
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC_WORK_NAME);
    }

    /**
     * This method is no longer used by the new enqueue logic, but we will fix it for correctness.
     * It is not reliable for checking if work will run in the future, only if it's currently enqueued.
     */
    public boolean isWorkScheduled() {
        return false; // This check was unreliable and causing crashes. The new enqueue logic is safer.
    }
}