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
import com.adriana.newscompanion.util.AiCleaningTrigger;

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
        
        // --- UPDATED ADAPTER INITIALIZATION ---
        boolean autoTranslate = sharedPreferencesRepository.getAutoTranslate();
        boolean summarization = sharedPreferencesRepository.isSummarizationEnabled();
        boolean aiCleaning = sharedPreferencesRepository.isAiCleaningEnabled();
        
        adapter = new EntryItemAdapter(this, autoTranslate, summarization, aiCleaning);
        // ---------------------------------------

        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                entriesRecycler.scrollToPosition(0);
            }
        });
        entriesRecycler.setAdapter(adapter);

        sortBy = allEntriesViewModel.getSortBy();
        swipeRefreshLayout = binding.swipeRefreshLayout;

        swipeRefreshLayout.setOnRefreshListener(() -> allEntriesViewModel.refreshEntries(swipeRefreshLayout));

        binding.deleteAllVisitedEntriesButton.setOnClickListener(view -> allEntriesViewModel.deleteAllVisitedEntries());

        allEntriesViewModel.getToastMessage().observe(getViewLifecycleOwner(), s -> {
            if (s != null && !s.isEmpty()) {
                Snackbar.make(requireView(), s, Snackbar.LENGTH_SHORT).show();
                allEntriesViewModel.resetToastMessage();
            }
        });

        allEntriesViewModel.getUnreadCount().observe(getViewLifecycleOwner(), integer -> {
            String unread = (integer != null) ? integer + " unread" : "0 unread";
            unreadTextView.setText(unread);
        });

        allEntriesViewModel.getAllEntries().observe(getViewLifecycleOwner(), newEntries -> {
            if (sortBy.equals("oldest")) {
                Collections.sort(newEntries, new EntryInfo.OldestComparator());
            } else {
                Collections.sort(newEntries, new EntryInfo.LatestComparator());
            }

            adapter.submitList(new ArrayList<>(newEntries));
            entries = newEntries;

            if (newEntries.isEmpty()) {
                entriesRecycler.setVisibility(View.GONE);
                emptyContainer.setVisibility(View.VISIBLE);
            } else {
                emptyContainer.setVisibility(View.GONE);
                entriesRecycler.setVisibility(View.VISIBLE);
            }
        });

        webViewViewModel = new ViewModelProvider(requireActivity()).get(WebViewViewModel.class);
        compositeDisposable = new CompositeDisposable();
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (getArguments() != null) {
            String newTitle = getArguments().getString("title");
            feedId = getArguments().getLong("id");
            if (newTitle != null) {
                title = newTitle;
                binding.filterTitle.setText(title);
                allEntriesViewModel.getEntriesByFeed(feedId, filterBy);
            }
        } else {
            title = "All feeds";
        }

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
                                    if (entryInfo.getEntryTitle().toLowerCase(Locale.ROOT).contains(query)) filteredEntries.add(entryInfo);
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

        webViewViewModel.getLoadingState().observe(getViewLifecycleOwner(), isLoading -> {
            if (entries != null) {
                for (EntryInfo entry : entries) entry.setLoading(isLoading);
                adapter.notifyDataSetChanged();
            }
        });
    }

    private void translate(EntryInfo entryInfo) {
        String originalHtml = entryRepository.getOriginalHtmlById(entryInfo.getEntryId());
        if (originalHtml == null || originalHtml.isEmpty()) return;

        String sourceLang = entryInfo.getFeedLanguage();
        String targetLang = sharedPreferencesRepository.getDefaultTranslationLanguage();

        if (sourceLang == null || sourceLang.equalsIgnoreCase(targetLang)) return;

        Disposable translationDisposable = translationRepository.translateText(originalHtml, sourceLang, targetLang)
                .subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io())
                .observeOn(io.reactivex.rxjava3.android.schedulers.AndroidSchedulers.mainThread())
                .subscribe(
                        translatedHtml -> {
                            entryRepository.updateHtml(translatedHtml, entryInfo.getEntryId());
                            Document doc = Jsoup.parse(translatedHtml);
                            String translatedTitleText = doc.selectFirst("div.entry-header > p:first-of-type") != null 
                                ? doc.selectFirst("div.entry-header > p:first-of-type").text() : "";
                            
                            String translatedBodyText = textUtil.extractHtmlContent(doc.body().html(), "--####--");
                            entryRepository.updateTranslatedTitle(translatedTitleText, entryInfo.getEntryId());
                            entryRepository.updateTranslatedSummary(translatedBodyText, entryInfo.getEntryId());
                            entryRepository.updateTranslated(translatedTitleText + "--####--" + translatedBodyText, entryInfo.getEntryId());
                            allEntriesViewModel.getEntriesByFeed(feedId, filterBy);
                        },
                        throwable -> {}
                );
        compositeDisposable.add(translationDisposable);
    }


    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (translationFinishedReceiver != null) LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(translationFinishedReceiver);
        binding = null;
    }

    @Override
    public void onPause() {
        if (translationFinishedReceiver != null) LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(translationFinishedReceiver);
        super.onPause();
        if (swipeRefreshLayout.isRefreshing()) swipeRefreshLayout.setRefreshing(false);
        if (isSelectionMode) exitSelectionMode();
    }

    @Override
    public void onEntryClick(EntryInfo entryInfo, boolean isTranslated) {
        Context context = getContext();
        if (context == null) return;
        Intent intent = new Intent(context, WebViewActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("entry_id", entryInfo.getEntryId());
        intent.putExtra("read", false);
        intent.putExtra("entry_title", isTranslated ? entryInfo.getTranslatedTitle() : entryInfo.getEntryTitle());
        intent.putExtra("is_translated", isTranslated);
        
        // FIX: Add complete playlist (full filtered/sorted result set)
        long[] playlistIds = new long[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            playlistIds[i] = entries.get(i).getEntryId();
        }
        intent.putExtra("playlist_ids", playlistIds);
        
        android.util.Log.d("PLAYLIST_FIX", "✅ onEntryClick: Sending FULL playlist. Size=" + playlistIds.length);
        
        allEntriesViewModel.updateVisitedDate(entryInfo.getEntryId());
        startActivity(intent);
    }

    @Override
    public void onMoreButtonClick(long entryId, String link, boolean unread) {
        long[] allLinks = new long[entries.size()];
        for (int i = 0; i < entries.size(); i++) allLinks[i] = entries.get(i).getEntryId();
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
    public void onBookmarkButtonClick(String bool, long id) { allEntriesViewModel.updateBookmark(bool, id); }

    @Override
    public void onFilterChange(String filter) {
        this.filterBy = filter;
        allEntriesViewModel.getEntriesByFeed(feedId, filter);
        binding.filterTitle.setText(filter.equals("all") ? title : filter + " (" + title + ")");
    }

    @Override
    public void onSortChange(String sort) {
        this.sortBy = sort;
        allEntriesViewModel.setSortBy(sort);

        List<EntryInfo> currentEntries = new ArrayList<>(entries);
        if (sort.equals("oldest")) {
            Collections.sort(currentEntries, new EntryInfo.OldestComparator());
        } else {
            Collections.sort(currentEntries, new EntryInfo.LatestComparator());
        }

        adapter.submitList(currentEntries);
        entries = currentEntries;

        if (sharedPreferencesRepository.isAiCleaningEnabled()) {
            AiCleaningTrigger.triggerAiCleaning(requireContext());
        }
    }

    @Override
    public void onPlayingButtonClick(long entryId) {
        EntryInfo entryToOpen = null;
        for (EntryInfo info : entries) if (info.getEntryId() == entryId) entryToOpen = info;
        if (entryToOpen == null) return;
        
        Intent intent = new Intent(getContext(), WebViewActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("read", false);
        intent.putExtra("entry_id", entryId);
        boolean isTranslated = entryToOpen.getTranslatedTitle() != null;
        intent.putExtra("entry_title", isTranslated ? entryToOpen.getTranslatedTitle() : entryToOpen.getEntryTitle());
        intent.putExtra("is_translated", isTranslated);
        
        // FIX: Use complete list from entries (full filtered/sorted result set)
        // NOT adapter.getCurrentIdList() which only has loaded window
        long[] playlistIds = new long[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            playlistIds[i] = entries.get(i).getEntryId();
        }
        intent.putExtra("playlist_ids", playlistIds);
        
        android.util.Log.d("PLAYLIST_FIX", "✅ onPlayingButtonClick: Sending FULL playlist. Size=" + playlistIds.length);
        
        allEntriesViewModel.updateVisitedDate(entryId);
        startActivity(intent);
    }

    @Override
    public void onReadingButtonClick(long entryId) {
        EntryInfo entryToOpen = null;
        for (EntryInfo info : entries) if (info.getEntryId() == entryId) entryToOpen = info;
        if (entryToOpen == null) return;
        
        Intent intent = new Intent(getContext(), WebViewActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("read", true);
        intent.putExtra("entry_id", entryId);
        boolean isTranslated = entryToOpen.getTranslatedTitle() != null;
        intent.putExtra("entry_title", isTranslated ? entryToOpen.getTranslatedTitle() : entryToOpen.getEntryTitle());
        intent.putExtra("is_translated", isTranslated);
        
        // FIX: Use complete list from entries (full filtered/sorted result set)
        // NOT adapter.getCurrentIdList() which only has loaded window
        long[] playlistIds = new long[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            playlistIds[i] = entries.get(i).getEntryId();
        }
        intent.putExtra("playlist_ids", playlistIds);
        
        android.util.Log.d("PLAYLIST_FIX", "✅ onReadingButtonClick: Sending FULL playlist. Size=" + playlistIds.length);
        
        allEntriesViewModel.updateVisitedDate(entryId);
        startActivity(intent);
    }

    @Override
    public void onSelectionModeChanged(boolean isSelectionMode) {
        this.isSelectionMode = isSelectionMode;
        requireActivity().invalidateOptionsMenu();
    }

    @Override
    public void onItemSelected(EntryInfo entryInfo) {
        if (entryInfo.isSelected()) selectedEntries.add(entryInfo);
        else selectedEntries.remove(entryInfo);
        if (selectedCountTextView != null) selectedCountTextView.setText(selectedEntries.size() + " selected");
    }

    public void enterSelectionMode() {
        isSelectionMode = true;
        actionBar = ((AppCompatActivity) getActivity()).getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(false);
            actionBar.setDisplayShowCustomEnabled(true);
            actionBar.setCustomView(R.layout.actionbar_multipleselection);
            selectedCountTextView = actionBar.getCustomView().findViewById(R.id.selected_count);
            selectedCountTextView.setText(selectedEntries.size() + " selected");
            actionBar.getCustomView().findViewById(R.id.menu_delete).setOnClickListener(v -> {
                for (EntryInfo item : selectedEntries) allEntriesViewModel.deleteEntry(item.getEntryId());
                exitSelectionMode();
            });
            actionBar.getCustomView().findViewById(R.id.menu_cancel).setOnClickListener(v -> exitSelectionMode());
        }
    }

    public void exitSelectionMode() {
        isSelectionMode = false;
        for (EntryInfo info : selectedEntries) info.setSelected(false);
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
        translationFinishedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                allEntriesViewModel.getEntriesByFeed(feedId, filterBy);
            }
        };
    }

    @Override
    public void onResume() {
        super.onResume();
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(translationFinishedReceiver, new IntentFilter("translation-finished"));
    }
}
