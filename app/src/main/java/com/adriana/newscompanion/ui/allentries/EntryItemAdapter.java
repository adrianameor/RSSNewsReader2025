package com.adriana.newscompanion.ui.allentries;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.squareup.picasso.Picasso;

import java.util.Date;
import java.util.concurrent.TimeUnit;

import com.adriana.newscompanion.R;
import com.adriana.newscompanion.model.EntryInfo;

import java.util.ArrayList;
import java.util.List;

public class EntryItemAdapter extends ListAdapter<EntryInfo, EntryItemAdapter.EntryItemHolder> {

    EntryItemClickInterface entryItemClickInterface;
    private Context context;
    private boolean isSelectionMode = false;
    private static final String TAG = "EntryItemAdapter";
    private final boolean autoTranslateEnabled;
    private final boolean summarizationEnabled;
    private final boolean aiCleaningEnabled;

    public EntryItemAdapter(EntryItemClickInterface entryItemClickInterface, boolean autoTranslateEnabled, boolean summarizationEnabled, boolean aiCleaningEnabled) {
        super(DIFF_CALLBACK);
        this.entryItemClickInterface = entryItemClickInterface;
        this.autoTranslateEnabled = autoTranslateEnabled;
        this.summarizationEnabled = summarizationEnabled;
        this.aiCleaningEnabled = aiCleaningEnabled;
    }

    private static final DiffUtil.ItemCallback<EntryInfo> DIFF_CALLBACK = new DiffUtil.ItemCallback<EntryInfo>() {
        @Override
        public boolean areItemsTheSame(@NonNull EntryInfo oldItem, @NonNull EntryInfo newItem) {
            return oldItem.getEntryId() == newItem.getEntryId();
        }

        @Override
        public boolean areContentsTheSame(@NonNull EntryInfo oldE, @NonNull EntryInfo newE) {
            boolean sameTitle = java.util.Objects.equals(oldE.getEntryTitle(), newE.getEntryTitle()) &&
                    java.util.Objects.equals(oldE.getTranslatedTitle(), newE.getTranslatedTitle());

            boolean sameDescription = java.util.Objects.equals(oldE.getEntryDescription(), newE.getEntryDescription()) &&
                    java.util.Objects.equals(oldE.getTranslatedSummary(), newE.getTranslatedSummary());

            boolean sameBookmark = java.util.Objects.equals(oldE.getBookmark(), newE.getBookmark());
            boolean sameVisited  = java.util.Objects.equals(oldE.getVisitedDate(), newE.getVisitedDate());
            
            boolean sameAiState = oldE.isAiCleaned() == newE.isAiCleaned() &&
                                 oldE.isAiSummarized() == newE.isAiSummarized() &&
                                 oldE.isAiSummaryTranslated() == newE.isAiSummaryTranslated() &&
                                 java.util.Objects.equals(oldE.getHtml(), newE.getHtml());

            return sameTitle && sameDescription && sameBookmark && sameVisited && sameAiState;
        }
    };

    @NonNull
    @Override
    public EntryItemHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.entry_item, parent, false);
        context = parent.getContext();
        return new EntryItemHolder(itemView);
    }

    @Override
    public void onBindViewHolder(@NonNull EntryItemAdapter.EntryItemHolder holder, int position) {
        EntryInfo currentEntry = getItem(position);
        holder.bind(currentEntry);
    }

    public void exitSelectionMode() {
        isSelectionMode = false;
        if(entryItemClickInterface != null) entryItemClickInterface.onSelectionModeChanged(false);
        notifyDataSetChanged();
    }

    class EntryItemHolder extends RecyclerView.ViewHolder {

        private TextView textViewEntryTitle, textViewFeedTitle, textViewEntryPubDate;
        private ImageView imageViewEntryImage, imageViewFeedImage;
        private MaterialButton bookmarkButton;
        private MaterialButton moreButton;
        private CheckBox selectedCheckbox;
        private View view;

        public EntryItemHolder(@NonNull View itemView) {
            super(itemView);
            textViewEntryTitle = itemView.findViewById(R.id.entryTitle);
            textViewFeedTitle = itemView.findViewById(R.id.feedTitle);
            textViewEntryPubDate = itemView.findViewById(R.id.entryPubDate);
            imageViewFeedImage = itemView.findViewById(R.id.feedImage);
            imageViewEntryImage = itemView.findViewById(R.id.entryImage);
            bookmarkButton = itemView.findViewById(R.id.bookmark_button);
            moreButton = itemView.findViewById(R.id.more_button);
            selectedCheckbox = itemView.findViewById(R.id.selectedCheckbox);
            view = itemView;
        }

        public void bind(EntryInfo entryInfo) {
            TextView statusView = view.findViewById(R.id.extractionStatus);

            textViewEntryTitle.setTextColor(textViewEntryPubDate.getTextColors());
            String titleToDisplay = entryInfo.getTranslatedTitle();
            if (titleToDisplay == null || titleToDisplay.trim().isEmpty()) {
                titleToDisplay = entryInfo.getEntryTitle();
            }
            textViewEntryTitle.setText(titleToDisplay);
            textViewFeedTitle.setText(entryInfo.getFeedTitle());

            String pubDate = covertTimeToText(entryInfo.getEntryPublishedDate());
            textViewEntryPubDate.setText(pubDate);

            if (entryInfo.getVisitedDate() != null) {
                textViewEntryTitle.setTextColor(Color.parseColor("#CD5C5C"));
            }

            if (entryInfo.getBookmark() == null || entryInfo.getBookmark().equals("N")) {
                bookmarkButton.setIcon(ContextCompat.getDrawable(context, R.drawable.ic_bookmark_outline));
                bookmarkButton.setIconTint(ContextCompat.getColorStateList(context, R.color.text));
            } else {
                bookmarkButton.setIcon(ContextCompat.getDrawable(context, R.drawable.ic_bookmark_filled));
                bookmarkButton.setIconTint(ContextCompat.getColorStateList(context, R.color.primary));
            }

            bookmarkButton.setOnClickListener(v -> {
                if (entryItemClickInterface != null) {
                    String newBookmarkState = (entryInfo.getBookmark() == null || entryInfo.getBookmark().equals("N")) ? "Y" : "N";
                    entryItemClickInterface.onBookmarkButtonClick(newBookmarkState, entryInfo.getEntryId());
                }
            });

            moreButton.setOnClickListener(v -> {
                if (entryItemClickInterface != null) {
                    entryItemClickInterface.onMoreButtonClick(entryInfo.getEntryId(), entryInfo.getEntryLink(), entryInfo.getVisitedDate() == null);
                }
            });

            if (TextUtils.isEmpty(entryInfo.getEntryImageUrl())) {
                imageViewEntryImage.setVisibility(View.GONE);
            } else {
                imageViewEntryImage.setVisibility(View.VISIBLE);
                Picasso.get().load(entryInfo.getEntryImageUrl()).into(imageViewEntryImage);
            }
            if (TextUtils.isEmpty(entryInfo.getFeedImageUrl())) {
                imageViewFeedImage.setVisibility(View.GONE);
            } else {
                imageViewFeedImage.setVisibility(View.VISIBLE);
                Picasso.get().load(entryInfo.getFeedImageUrl()).into(imageViewFeedImage);
            }

            selectedCheckbox.setVisibility(isSelectionMode ? View.VISIBLE : View.GONE);
            selectedCheckbox.setChecked(entryInfo.isSelected());
            selectedCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isSelectionMode && entryItemClickInterface != null) {
                    entryInfo.setSelected(isChecked);
                    entryItemClickInterface.onItemSelected(entryInfo);
                }
            });

            view.setOnClickListener(v -> {
                if (entryItemClickInterface != null) {
                    boolean isTranslated = !TextUtils.isEmpty(entryInfo.getTranslated())
                            && entryInfo.getTranslated().contains("--####--");
                    entryItemClickInterface.onEntryClick(entryInfo, isTranslated);
                }
            });

            view.setOnLongClickListener(v -> {
                isSelectionMode = true;
                if(entryItemClickInterface != null) entryItemClickInterface.onSelectionModeChanged(true);
                notifyDataSetChanged();
                return true;
            });

            statusView.setVisibility(View.VISIBLE);
            
            boolean hasReadability = !TextUtils.isEmpty(entryInfo.getOriginalHtml());
            boolean isAiCleaned = entryInfo.isAiCleaned();
            boolean isAiSummarized = entryInfo.isAiSummarized();
            boolean isAiSummaryTranslated = entryInfo.isAiSummaryTranslated();
            boolean hasFullTranslation = !TextUtils.isEmpty(entryInfo.getTranslated()) && entryInfo.getTranslated().contains("--####--");

            // --- FIX FOR WRONG YELLOW COLOR ---
            // Yellow should only show if extraction is actually progress (isLoading flag)
            // or if it's queued (priority > 0) but readability isn't done yet.
            boolean isQueued = entryInfo.getPriority() > 0 && !hasReadability;

            if (autoTranslateEnabled && summarizationEnabled && aiCleaningEnabled) {
                if (hasFullTranslation && isAiCleaned && isAiSummarized && isAiSummaryTranslated) {
                    statusView.setBackgroundResource(R.drawable.status_dot_green);
                } else if (isAiCleaned) {
                    statusView.setBackgroundResource(R.drawable.status_dot_blue);
                } else if (isAiSummarized && isAiSummaryTranslated) {
                    statusView.setBackgroundResource(R.drawable.status_dot_purple);
                } else if (hasReadability) {
                    statusView.setBackgroundResource(R.drawable.status_dot_yellow);
                } else {
                    statusView.setBackgroundResource(R.drawable.status_dot_red);
                }
            }
            else if (autoTranslateEnabled && summarizationEnabled) {
                if (hasFullTranslation && isAiSummarized && isAiSummaryTranslated) {
                    statusView.setBackgroundResource(R.drawable.status_dot_green);
                } else if (isAiSummarized && isAiSummaryTranslated) {
                    statusView.setBackgroundResource(R.drawable.status_dot_blue);
                } else if (hasReadability) {
                    statusView.setBackgroundResource(R.drawable.status_dot_yellow);
                } else {
                    statusView.setBackgroundResource(R.drawable.status_dot_red);
                }
            }
            else if (autoTranslateEnabled && aiCleaningEnabled) {
                if (hasFullTranslation && isAiCleaned) {
                    statusView.setBackgroundResource(R.drawable.status_dot_green);
                } else if (isAiCleaned) {
                    statusView.setBackgroundResource(R.drawable.status_dot_blue);
                } else if (hasReadability) {
                    statusView.setBackgroundResource(R.drawable.status_dot_yellow);
                } else {
                    statusView.setBackgroundResource(R.drawable.status_dot_red);
                }
            }
            else if (summarizationEnabled && aiCleaningEnabled) {
                if (isAiSummarized && isAiCleaned) {
                    statusView.setBackgroundResource(R.drawable.status_dot_green);
                } else if (isAiSummarized) {
                    statusView.setBackgroundResource(R.drawable.status_dot_blue);
                } else if (hasReadability) {
                    statusView.setBackgroundResource(R.drawable.status_dot_yellow);
                } else {
                    statusView.setBackgroundResource(R.drawable.status_dot_red);
                }
            }
            else if (autoTranslateEnabled) {
                if (hasFullTranslation) {
                    statusView.setBackgroundResource(R.drawable.status_dot_green);
                } else if (hasReadability) {
                    statusView.setBackgroundResource(R.drawable.status_dot_yellow);
                } else {
                    statusView.setBackgroundResource(R.drawable.status_dot_red);
                }
            }
            else if (summarizationEnabled) {
                if (isAiSummarized) {
                    statusView.setBackgroundResource(R.drawable.status_dot_green);
                } else if (hasReadability) {
                    statusView.setBackgroundResource(R.drawable.status_dot_yellow);
                } else {
                    statusView.setBackgroundResource(R.drawable.status_dot_red);
                }
            }
            else if (aiCleaningEnabled) {
                if (isAiCleaned) {
                    statusView.setBackgroundResource(R.drawable.status_dot_green);
                } else if (hasReadability) {
                    statusView.setBackgroundResource(R.drawable.status_dot_yellow);
                } else {
                    statusView.setBackgroundResource(R.drawable.status_dot_red);
                }
            }
            // Scenario 1: All OFF (Fix: Red if not loaded, Green if readability done)
            else {
                if (hasReadability) {
                    statusView.setBackgroundResource(R.drawable.status_dot_green);
                } else if (isQueued) {
                    statusView.setBackgroundResource(R.drawable.status_dot_yellow);
                } else {
                    statusView.setBackgroundResource(R.drawable.status_dot_red);
                }
            }
        }
    }

    public interface EntryItemClickInterface {
        void onEntryClick(EntryInfo entryInfo, boolean isTranslated);
        void onMoreButtonClick(long entryId, String link, boolean unread);
        void onBookmarkButtonClick(String bool, long id);
        void onSelectionModeChanged(boolean isSelectionMode);
        void onItemSelected(EntryInfo entryInfo);
    }

    /**
     * Returns the current list of entry IDs in the order they appear in the adapter.
     * This reflects the user's current sort and filter settings.
     * 
     * @return List of entry IDs in adapter order
     */
    public List<Long> getCurrentIdList() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < getItemCount(); i++) {
            EntryInfo entry = getItem(i);
            if (entry != null) {
                ids.add(entry.getEntryId());
            }
        }
        return ids;
    }

    public String covertTimeToText(Date date) {
        String convTime = null;
        String suffix = "ago";
        Date nowTime = new Date();
        long dateDiff = nowTime.getTime() - date.getTime();
        long second = TimeUnit.MILLISECONDS.toSeconds(dateDiff);
        long minute = TimeUnit.MILLISECONDS.toMinutes(dateDiff);
        long hour = TimeUnit.MILLISECONDS.toHours(dateDiff);
        long day = TimeUnit.MILLISECONDS.toDays(dateDiff);

        if (second < 60) {
            convTime = second + " seconds " + suffix;
        } else if (minute < 60) {
            convTime = minute + " minutes " + suffix;
        } else if (hour < 24) {
            convTime = hour + " hours " + suffix;
        } else if (day >= 7) {
            if (day > 360) {
                convTime = (day / 360) + " years " + suffix;
            } else if (day > 30) {
                convTime = (day / 30) + " months " + suffix;
            } else {
                convTime = (day / 7) + " week " + suffix;
            }
        } else {
            convTime = day + " days " + suffix;
        }
        return convTime;
    }
}
