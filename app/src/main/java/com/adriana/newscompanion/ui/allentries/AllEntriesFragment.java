package com.adriana.newscompanion.ui.allentries;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import com.adriana.newscompanion.data.repository.TranslationRepository;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import com.adriana.newscompanion.R;
import com.adriana.newscompanion.data.entry.EntryRepository;
import com.adriana.newscompanion.data.sharedpreferences.SharedPreferencesRepository;
import com.adriana.newscompanion.service.tts.TtsPlayer;
import com.adriana.newscompanion.service.tts.TtsPlaylist;
import com.adriana.newscompanion.databinding.FragmentAllEntriesBinding;

import com.adriana.newscompanion.model.EntryInfo;
import com.adriana.newscompanion.service.util.AutoTranslator;
import com.adriana.newscompanion.service.util.TextUtil;
import com.adriana.newscompanion.ui.webview.WebViewActivity;

import com.google.android.material.snackbar.Snackbar;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import com.adriana.newscompanion.ui.webview.WebViewViewModel;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;

@AndroidEntryPoint
public class AllEntriesFragment extends Fragment implements EntryItemAdapter.EntryItemClickInterface, FilterBottomSheet.FilterClickInterface, EntryItemDialog.EntryItemDialogClickInterface {

    private BroadcastReceiver translationFinishedReceiver;
    private static final String TAG = AllEntriesFragment.class.getSimpleName();
    private FragmentAllEntriesBinding binding;
    private SwipeRefreshLayout swipeRefreshLayout;
    private AllEntriesViewModel allEntriesViewModel;
    private TextView unreadTextView;
    private EntryItemAdapter adapter;
    private List<EntryInfo> entries = new ArrayList<>();
    private String sortBy;
    private String filterBy = "all";
    private String title;
    private long feedId;
    private List<EntryInfo> selectedEntries = new ArrayList<>();
    private TextView selectedCountTextView;
    private ActionBar actionBar;
    private AutoTranslator autoTranslator;

    @Inject
    TextUtil textUtil;
    @Inject
    TtsPlaylist ttsPlaylist;
    @Inject
    TtsPlayer ttsPlayer;
    @Inject
    SharedPreferencesRepository sharedPreferencesRepository;
    @Inject
    EntryRepository entryRepository;
    @Inject
    TranslationRepository translationRepository;


    private boolean isSelectionMode = false;
    private WebViewViewModel webViewViewModel;
    private CompositeDisposable compositeDisposable;


    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {

        binding = FragmentAllEntriesBinding.inflate(inflater, container, false);
        allEntriesViewModel = new ViewModelProvider(requireActivity()).get(AllEntriesViewModel.class);
        unreadTextView = binding.unread;

        ConstraintLayout emptyContainer = binding.emptyContainer;
        RecyclerView entriesRecycler = binding.entriesRecycler;
        entriesRecycler.setLayoutManager(new LinearLayoutManager(getContext()));
        entriesRecycler.setHasFixedSize(true);
        boolean autoTranslate = sharedPreferencesRepository.getAutoTranslate();
        adapter = new EntryItemAdapter(this, autoTranslate);
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onItemRangeChanged(int positionStart, int itemCount) {
            }

            @Override
            public void onItemRangeChanged(int positionStart, int itemCount, @Nullable Object payload) {
            }

            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                entriesRecycler.scrollToPosition(0);

            }

            @Override
            public void onItemRangeRemoved(int positionStart, int itemCount) {
                entriesRecycler.scrollToPosition(0);

            }

            @Override
            public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
                entriesRecycler.scrollToPosition(0);

            }
        });
        entriesRecycler.setAdapter(adapter);

        sortBy = allEntriesViewModel.getSortBy();

        swipeRefreshLayout = binding.swipeRefreshLayout;

        swipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                allEntriesViewModel.refreshEntries(swipeRefreshLayout);
            }
        });

        binding.deleteAllVisitedEntriesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                allEntriesViewModel.deleteAllVisitedEntries();
            }
        });

        allEntriesViewModel.getToastMessage().observe(getViewLifecycleOwner(), new Observer<String>() {
            @Override
            public void onChanged(String s) {
                if (s != null && !s.isEmpty()) {
                    Snackbar.make(requireView(), s, Snackbar.LENGTH_SHORT).show();
                    allEntriesViewModel.resetToastMessage();
                }
            }
        });

        allEntriesViewModel.getUnreadCount().observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @Override
            public void onChanged(Integer integer) {
                String unread;
                if (integer != null) unread = integer + " unread";
                else unread = "0 unread";
                unreadTextView.setText(unread);
            }
        });

        allEntriesViewModel.getAllEntries().observe(getViewLifecycleOwner(), new Observer<List<EntryInfo>>() {
            @Override
            public void onChanged(List<EntryInfo> newEntries) {
                // THIS IS THE FIX
                // When the LiveData changes (either on startup or after our broadcast),
                // we receive the new, updated list here.
                Log.d("TranslationDebug", "ADAPTER OBSERVER: Received new list with " + newEntries.size() + " entries.");

                // Sort the new list based on the user's preference
                if (sortBy.equals("oldest")) {
                    Collections.sort(newEntries, new EntryInfo.OldestComparator());
                } else {
                    Collections.sort(newEntries, new EntryInfo.LatestComparator());
                }

                //Update the UI with the new, sorted Files.list.
                // This will trigger the DiffUtil and update only the items that have changed.
                adapter.submitList(new ArrayList<>(newEntries));
                entries = newEntries; // Update the local copy

                // Handle the empty state view
                if (newEntries.isEmpty()) {
                    entriesRecycler.setVisibility(View.GONE);
                    emptyContainer.setVisibility(View.VISIBLE);
                } else {
                    emptyContainer.setVisibility(View.GONE);
                    entriesRecycler.setVisibility(View.VISIBLE);
                }
            }
        });

        webViewViewModel = new ViewModelProvider(requireActivity()).get(WebViewViewModel.class);
        compositeDisposable = new CompositeDisposable();
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // This block handles setting the title and triggering the FIRST data load.
        if (getArguments() != null) {
            String newTitle = getArguments().getString("title");
            feedId = getArguments().getLong("id");
            if (newTitle != null) {
                title = newTitle;
                binding.filterTitle.setText(title);
                // Trigger the initial data load. The observer in onCreateView will handle the result.
                allEntriesViewModel.getEntriesByFeed(feedId, filterBy);
            }
        } else {
            title = "All feeds";
        }

        if (autoTranslator != null && sharedPreferencesRepository.getAutoTranslate()) {
            // THIS IS THE FIX:
            // We provide a simple callback. When the AutoTranslator finishes its background work,
            // this callback will run on the main thread and tell the ViewModel to fetch the new,
            // translated data. The existing observer in onCreateView will then update the UI.
            // This is simpler and more reliable than the broadcast receiver.
            autoTranslator.runAutoTranslation(() -> {
                Log.d("TranslationDebug", "AutoTranslator onComplete callback fired. Refreshing entries.");
                if (allEntriesViewModel != null) {
                    allEntriesViewModel.getEntriesByFeed(feedId, filterBy);
                }
            });
        }

        // --- All the original NavController and MenuProvider logic remains unchanged ---
        NavController navController = Navigation.findNavController(view);

        binding.goToAddFeedButton.setOnClickListener(v -> {
            NavOptions navOptions = new NavOptions.Builder()
                    .setPopUpTo(R.id.feedFragment, false)
                    .build();
            navController.navigate(R.id.feedFragment, null, navOptions);
        });

        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                if (isSelectionMode) {
                    menu.clear();
                    enterSelectionMode();
                } else {
                    menuInflater.inflate(R.menu.top_app_bar_main, menu);
                    MenuItem menuItem = menu.findItem(R.id.search);
                    SearchView searchView = (SearchView) menuItem.getActionView();
                    searchView.setQueryHint("Type here to search");
                    searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                        @Override
                        public boolean onQueryTextSubmit(String query) { return false; }
                        @Override
                        public boolean onQueryTextChange(String newText) {
                            final String query = newText.toLowerCase(Locale.ROOT);
                            final List<EntryInfo> filteredEntries = new ArrayList<>();
                            if (entries != null) {
                                for (EntryInfo entryInfo : entries) {
                                    final String entryTitle = entryInfo.getEntryTitle().toLowerCase(Locale.ROOT);
                                    if (entryTitle.contains(query)) filteredEntries.add(entryInfo);
                                }
                            }
                            adapter.submitList(filteredEntries);
                            return true;
                        }
                    });
                }
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                if (menuItem.getItemId() == R.id.filter) {
                    FilterBottomSheet filterBottomSheet = new FilterBottomSheet(AllEntriesFragment.this, sortBy, filterBy);
                    filterBottomSheet.show(requireActivity().getSupportFragmentManager(), "FilterBottomSheet");
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);

        // The webViewViewModel observer and BroadcastReceiver registration remains here, as it's tied to the view's lifecycle.
        webViewViewModel.getLoadingState().observe(getViewLifecycleOwner(), isLoading -> {
            Log.d(TAG, "Loading state observed in AllEntriesFragment: " + isLoading);
            if (entries != null) {
                for (EntryInfo entry : entries) {
                    entry.setLoading(isLoading);
                }
                adapter.notifyDataSetChanged();
            }
        });
    }

    private void translate(EntryInfo entryInfo) {
        Log.d(TAG, "Manual translation requested for entry ID: " + entryInfo.getEntryId());

        String originalHtml = entryRepository.getOriginalHtmlById(entryInfo.getEntryId());
        if (originalHtml == null || originalHtml.isEmpty()) {
            Toast.makeText(requireContext(), "Original content not found, cannot translate.", Toast.LENGTH_SHORT).show();
            return;
        }

        String sourceLang = entryInfo.getFeedLanguage();
        String targetLang = sharedPreferencesRepository.getDefaultTranslationLanguage();

        if (sourceLang == null || sourceLang.isEmpty() || sourceLang.equalsIgnoreCase(targetLang)) {
            Toast.makeText(requireContext(), "Article is already in the target language or source language is unknown.", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(requireContext(), "Translating " + entryInfo.getEntryTitle() + "...", Toast.LENGTH_SHORT).show();

        // This now works because the import and @Inject are correct.
        Disposable translationDisposable = translationRepository.translateText(originalHtml, sourceLang, targetLang)
                .subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io())
                .observeOn(io.reactivex.rxjava3.android.schedulers.AndroidSchedulers.mainThread())
                .subscribe(
                        (String translatedHtml) -> {
                            Log.d(TAG, "Manual translation successful for entry ID: " + entryInfo.getEntryId());
                            entryRepository.updateHtml(translatedHtml, entryInfo.getEntryId());

                            Document doc = Jsoup.parse(translatedHtml);
                            org.jsoup.nodes.Element titleElement = doc.selectFirst("div.entry-header > p:first-of-type");
                            String translatedTitleText = (titleElement != null) ? titleElement.text() : "";

                            org.jsoup.nodes.Element header = doc.selectFirst("div.entry-header");
                            if (header != null) {
                                header.remove();
                            }
                            String translatedBodyText = textUtil.extractHtmlContent(doc.body().html(), "--####--");

                            entryRepository.updateTranslatedTitle(translatedTitleText, entryInfo.getEntryId());
                            entryRepository.updateTranslatedSummary(translatedBodyText, entryInfo.getEntryId());
                            entryRepository.updateTranslated(translatedTitleText + "--####--" + translatedBodyText, entryInfo.getEntryId());

                            allEntriesViewModel.getEntriesByFeed(feedId, filterBy);

                            Toast.makeText(requireContext(), "Translation successful!", Toast.LENGTH_SHORT).show();
                        },
                        (Throwable throwable) -> {
                            Log.e(TAG, "Manual translation failed for entry ID: " + entryInfo.getEntryId(), throwable);
                            Toast.makeText(requireContext(), "Translation failed: " + throwable.getMessage(), Toast.LENGTH_LONG).show();
                        }
                );

        if (compositeDisposable != null) {
            compositeDisposable.add(translationDisposable);
        }
    }


    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // This MUST be here to match the registration in onViewCreated
        if (translationFinishedReceiver != null) {
            LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(translationFinishedReceiver);
        }
        binding = null;
    }

    @Override
    public void onPause() {
        // THIS IS PART OF THE FIX:
        // We stop listening for the broadcast when the fragment is no longer visible.
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(translationFinishedReceiver);

        super.onPause();if (swipeRefreshLayout.isRefreshing()) swipeRefreshLayout.setRefreshing(false);
        if (isSelectionMode) {
            exitSelectionMode();
        }
    }

    @Override
    public void onEntryClick(EntryInfo entryInfo, boolean isTranslated) {
        // THIS IS THE FIX:
        // This method now accepts the 'isTranslated' boolean directly from the adapter.

        Context context = getContext();
        if (context == null) {
            return;
        }

        Intent intent = new Intent(context, WebViewActivity.class);
        intent.putExtra("entry_id", entryInfo.getEntryId());
        intent.putExtra("read", false); // Assume a normal click is for reading/playing

        // 1. Get the correct title that corresponds to the translation state.
        String titleToPass;
        if (isTranslated) {
            titleToPass = entryInfo.getTranslatedTitle();
        } else {
            titleToPass = entryInfo.getEntryTitle();
        }

        // As a safety fallback, if the title is still empty, use the original title.
        if (titleToPass == null || titleToPass.trim().isEmpty()) {
            titleToPass = entryInfo.getEntryTitle();
        }

        // 2. Pass the correct title AND the correct translation state to the next activity.
        intent.putExtra("entry_title", titleToPass);
        intent.putExtra("is_translated", isTranslated);

        Log.d("DEBUG_TRANSLATION", "Sending to WebViewActivity from onEntryClick: is_translated = " + isTranslated);

        // Update visited date and start the activity
        allEntriesViewModel.updateVisitedDate(entryInfo.getEntryId());
        startActivity(intent);
    }

    @Override
    public void onMoreButtonClick(long entryId, String link, boolean unread) {
        long[] allLinks = new long[entries.size()];
        int index = 0;
        for (EntryInfo entryInfo : entries) {
            allLinks[index] = entryInfo.getEntryId();
            index++;
        }

        Bundle args = new Bundle();
        args.putLongArray("ids", allLinks);
        args.putLong("id", entryId);
        args.putString("link", link);
        args.putBoolean("unread", unread);
        EntryItemBottomSheet bottomSheet = new EntryItemBottomSheet();
        bottomSheet.setArguments(args);
        bottomSheet.show(getChildFragmentManager(), EntryItemBottomSheet.TAG);
    }

    @Override
    public void onBookmarkButtonClick(String bool, long id) {
        allEntriesViewModel.updateBookmark(bool, id);
    }

    @Override
    public void onFilterChange(String filter) {
        this.filterBy = filter;
        allEntriesViewModel.getEntriesByFeed(feedId, filter);
        String text = " (" + title + ")";

        switch (filter) {
            case "read":
                text = "Read only" + text;
                binding.filterTitle.setText(text);
                break;
            case "unread":
                text = "Unread only" + text;
                binding.filterTitle.setText(text);
                break;
            case "bookmark":
                text = "Bookmarks" + text;
                binding.filterTitle.setText(text);
                break;
            default:
                binding.filterTitle.setText(title);
        }
    }

    @Override
    public void onSortChange(String sort) {
        allEntriesViewModel.setSortBy(sort);
        sortBy = sort;
        List<EntryInfo> sortedEntries = new ArrayList<>(entries);
        if (sortBy.equals("oldest")) {
            Collections.sort(sortedEntries, new EntryInfo.OldestComparator());
        } else {
            Collections.sort(sortedEntries, new EntryInfo.LatestComparator());
        }
        adapter.submitList(sortedEntries);
        entries = sortedEntries;
    }


    @Override
    public void onPlayingButtonClick(long entryId) {
        // Find the correct EntryInfo object from our up-to-date list.
        EntryInfo entryToOpen = null;
        for (EntryInfo info : entries) {
            if (info.getEntryId() == entryId) {
                entryToOpen = info;
                break;
            }
        }

        if (entryToOpen == null) {
            Toast.makeText(getContext(), "Error: Could not find article data.", Toast.LENGTH_SHORT).show();
            return;
        }

        Context context = getContext();
        Intent intent = new Intent(context, WebViewActivity.class);
        intent.putExtra("read", false);
        intent.putExtra("entry_id", entryId);

        // We check if a translated title exists...
        String titleToPass = entryToOpen.getTranslatedTitle();
        boolean isTranslated = false;
        if (titleToPass == null || titleToPass.trim().isEmpty()) {
            titleToPass = entryToOpen.getEntryTitle();
        } else {
            //... if it does, we set our flag to true.
            isTranslated = true;
        }

        // THIS IS THE FIX: We pass both the correct title AND the correct state.
        intent.putExtra("entry_title", titleToPass);
        intent.putExtra("is_translated", isTranslated);


        List<Long> allLinks = new ArrayList<>();
        for (EntryInfo entryInfo : entries) {
            allLinks.add(entryInfo.getEntryId());
        }
        allEntriesViewModel.insertPlaylist(allLinks, entryId);
        allEntriesViewModel.updateVisitedDate(entryId);

        if (context != null) {
            context.startActivity(intent);
        }
    }

    @Override
    public void onReadingButtonClick(long entryId) {
        // Find the correct EntryInfo object from our up-to-date list.
        EntryInfo entryToOpen = null;
        for (EntryInfo info : entries) {
            if (info.getEntryId() == entryId) {
                entryToOpen = info;
                break;
            }
        }

        if (entryToOpen == null) {
            Toast.makeText(getContext(), "Error: Could not find article data.", Toast.LENGTH_SHORT).show();
            return;
        }

        Context context = getContext();
        Intent intent = new Intent(context, WebViewActivity.class);
        intent.putExtra("read", true);
        intent.putExtra("entry_id", entryId);

        // We check if a translated title exists...
        String titleToPass = entryToOpen.getTranslatedTitle();
        boolean isTranslated = false;
        if (titleToPass == null || titleToPass.trim().isEmpty()) {
            titleToPass = entryToOpen.getEntryTitle();
        } else {
            //... if it does, we set our flag to true.
            isTranslated = true;
        }

        // THIS IS THE FIX: We pass both the correct title AND the correct state.
        intent.putExtra("entry_title", titleToPass);
        intent.putExtra("is_translated", isTranslated);

        List<Long> allLinks = new ArrayList<>();
        for (EntryInfo entryInfo : entries) {
            allLinks.add(entryInfo.getEntryId());
        }
        allEntriesViewModel.insertPlaylist(allLinks, entryId);
        allEntriesViewModel.updateVisitedDate(entryId);

        if (context != null) {
            context.startActivity(intent);
        }
    }

    @Override
    public void onSelectionModeChanged(boolean isSelectionMode) {
        this.isSelectionMode = isSelectionMode;
        requireActivity().invalidateOptionsMenu();
    }

    @Override
    public void onItemSelected(EntryInfo entryInfo) {
        if (entryInfo.isSelected()) {
            selectedEntries.add(entryInfo);
        } else {
            selectedEntries.remove(entryInfo);
        }
        selectedCountTextView.setText(selectedEntries.size() + " selected");
    }

    public void enterSelectionMode() {
        isSelectionMode = true;

        // Inflate custom view for action bar
        actionBar = ((AppCompatActivity) getActivity()).getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(false);
            actionBar.setDisplayShowCustomEnabled(true);
            actionBar.setCustomView(R.layout.actionbar_multipleselection);

            // Find the TextView in the custom view and update it
            selectedCountTextView = actionBar.getCustomView().findViewById(R.id.selected_count);
            selectedCountTextView.setText(selectedEntries.size() + " selected");

            // Set up listeners for the action buttons in the custom view
            actionBar.getCustomView().findViewById(R.id.menu_delete).setOnClickListener(v -> {
                for (EntryInfo item : selectedEntries) {
                    allEntriesViewModel.deleteEntry(item.getEntryId());
                }
                exitSelectionMode();
            });
            actionBar.getCustomView().findViewById(R.id.menu_mark_as_read).setOnClickListener(v -> {
                for (EntryInfo item : selectedEntries) {
                    allEntriesViewModel.updateVisitedDate(item.getEntryId());
                }
                exitSelectionMode();
            });
            actionBar.getCustomView().findViewById(R.id.menu_translate).setOnClickListener(v -> {
                for (EntryInfo item : selectedEntries) {
                    translate(item);
                }
                Toast.makeText(requireContext(), "Translating " + selectedEntries.size() + " entries", Toast.LENGTH_SHORT).show();
                exitSelectionMode();
            });
            actionBar.getCustomView().findViewById(R.id.menu_cancel).setOnClickListener(v -> {
                exitSelectionMode();
            });
        }
    }

    public void exitSelectionMode() {
        isSelectionMode = false;
        for (EntryInfo entryInfo : selectedEntries) {
            entryInfo.setSelected(false);
        }
        selectedEntries.clear();
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setDisplayShowCustomEnabled(false);
        actionBar.setCustomView(null);
        requireActivity().invalidateOptionsMenu();
        adapter.exitSelectionMode();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // THIS IS PART OF THE FIX:
        // We create the receiver object once, when the fragment itself is created.
        translationFinishedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d("TranslationDebug", "Broadcast received! Refreshing entries now.");
                if (allEntriesViewModel != null) {
                    allEntriesViewModel.getEntriesByFeed(feedId, filterBy);
                }
            }
        };
    }

    @Override
    public void onResume() {
        super.onResume();
        // THIS IS PART OF THE FIX:
        // We start listening for the broadcast whenever the fragment becomes visible.
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(translationFinishedReceiver, new IntentFilter("translation-finished"));
    }
}