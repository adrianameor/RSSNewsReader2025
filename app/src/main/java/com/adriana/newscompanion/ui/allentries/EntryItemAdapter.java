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

public class EntryItemAdapter extends ListAdapter<EntryInfo, EntryItemAdapter.EntryItemHolder> {

    // The field is correctly named 'entryItemClickInterface'
    EntryItemClickInterface entryItemClickInterface;
    private Context context;
    private boolean isSelectionMode = false;
    private static final String TAG = "EntryItemAdapter";
    private final boolean autoTranslateEnabled;

    public EntryItemAdapter(EntryItemClickInterface entryItemClickInterface, boolean autoTranslateEnabled) {
        super(DIFF_CALLBACK);
        // FIX: Assign the constructor parameter to the correct field.
        this.entryItemClickInterface = entryItemClickInterface;
        this.autoTranslateEnabled = autoTranslateEnabled;
    }

    private static final DiffUtil.ItemCallback<EntryInfo> DIFF_CALLBACK = new DiffUtil.ItemCallback<EntryInfo>() {
        @Override
        public boolean areItemsTheSame(@NonNull EntryInfo oldItem, @NonNull EntryInfo newItem) {
            return oldItem.getEntryId() == newItem.getEntryId();
        }

        @Override
        public boolean areContentsTheSame(@NonNull EntryInfo oldE, @NonNull EntryInfo newE) {
            // -- START DEBUGGING LOGS --
            if (oldE.getEntryId() == newE.getEntryId()) { // Only log for the same item
                boolean titlesAreDifferent = !java.util.Objects.equals(oldE.getTranslatedTitle(), newE.getTranslatedTitle());
                if (titlesAreDifferent) {
                    Log.d("DIFF_DEBUG", "Item " + oldE.getEntryId() + ": Translated titles have changed!");
                    Log.d("DIFF_DEBUG", "  Old: '" + oldE.getTranslatedTitle() + "'");
                    Log.d("DIFF_DEBUG", "  New: '" + newE.getTranslatedTitle() + "'");
                }
            }

            // 1. Check if the titles have changed (original OR translated).
            boolean sameTitle = java.util.Objects.equals(oldE.getEntryTitle(), newE.getEntryTitle()) &&
                    java.util.Objects.equals(oldE.getTranslatedTitle(), newE.getTranslatedTitle());

            // 2. Check if the descriptions have changed (original OR translated).
            boolean sameDescription = java.util.Objects.equals(oldE.getEntryDescription(), newE.getEntryDescription()) &&
                    java.util.Objects.equals(oldE.getTranslatedSummary(), newE.getTranslatedSummary());

            // 3. Check other UI-relevant states.
            boolean sameBookmark = java.util.Objects.equals(oldE.getBookmark(), newE.getBookmark());
            boolean sameVisited  = java.util.Objects.equals(oldE.getVisitedDate(), newE.getVisitedDate());

            // If any of these are different, the contents are different.
            boolean areTheSame = sameTitle && sameDescription && sameBookmark && sameVisited;

            if (oldE.getEntryId() == newE.getEntryId() && !areTheSame) {
                Log.d("DIFF_DEBUG", "Item " + oldE.getEntryId() + ": Contents are DIFFERENT. A redraw will be triggered.");
            }

            return areTheSame;
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
        // FIX: Use the correct variable name 'entryItemClickInterface'
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
            Log.d("TranslationDebug", "ADAPTER: Binding entry " + entryInfo.getEntryId() + ". Received translated title: " + entryInfo.getTranslatedTitle());
            TextView statusView = view.findViewById(R.id.extractionStatus);

            textViewEntryTitle.setTextColor(textViewEntryPubDate.getTextColors());
            String titleToDisplay = entryInfo.getTranslatedTitle();
            if (titleToDisplay == null || titleToDisplay.trim().isEmpty()) {
                titleToDisplay = entryInfo.getEntryTitle();
            }
            textViewEntryTitle.setText(titleToDisplay);
            Log.d("TranslationDebug", "ADAPTER: AFTER SETTING TEXT, TextView for entry " + entryInfo.getEntryId() + " now contains: '" + textViewEntryTitle.getText().toString() + "'");
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

            // FIX: Use the correct variable name 'entryItemClickInterface'
            bookmarkButton.setOnClickListener(v -> {
                if (entryItemClickInterface != null) {
                    String newBookmarkState = (entryInfo.getBookmark() == null || entryInfo.getBookmark().equals("N")) ? "Y" : "N";
                    entryItemClickInterface.onBookmarkButtonClick(newBookmarkState, entryInfo.getEntryId());
                }
            });

            // FIX: Use the correct variable name 'entryItemClickInterface' and pass the correct arguments to match the interface
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
            // FIX: Use the correct variable name 'entryItemClickInterface'
            selectedCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isSelectionMode && entryItemClickInterface != null) {
                    entryInfo.setSelected(isChecked);
                    entryItemClickInterface.onItemSelected(entryInfo);
                }
            });

            // FIX: Use the correct variable name 'entryItemClickInterface'
            view.setOnClickListener(v -> {
                if (entryItemClickInterface != null) {
                    entryItemClickInterface.onEntryClick(entryInfo);
                }
            });

            // FIX: Use the correct variable name 'entryItemClickInterface'
            view.setOnLongClickListener(v -> {
                isSelectionMode = true;
                if(entryItemClickInterface != null) entryItemClickInterface.onSelectionModeChanged(true);
                notifyDataSetChanged();
                return true;
            });

            String content = entryInfo.getContent();
            int priority = entryInfo.getPriority();
            boolean hasOriginalHtml   = !TextUtils.isEmpty(entryInfo.getOriginalHtml());
            // THIS IS THE FIX: The definition of "translated" now also requires the title to be present.
            boolean hasTranslatedHtml = (!TextUtils.isEmpty(entryInfo.getHtml())
                    && (entryInfo.getOriginalHtml() == null ||
                    !entryInfo.getHtml().equals(entryInfo.getOriginalHtml())))
                    && !TextUtils.isEmpty(entryInfo.getTranslatedTitle());
            statusView.setText("");

            if (autoTranslateEnabled) {
                if (hasTranslatedHtml) {
                    statusView.setBackgroundResource(R.drawable.status_dot_green);
                    statusView.setVisibility(View.VISIBLE);
                } else if (hasOriginalHtml) {
                    statusView.setBackgroundResource(R.drawable.status_dot_yellow);
                    statusView.setVisibility(View.VISIBLE);
                }else {
                    statusView.setBackgroundResource(R.drawable.status_dot_red);
                    statusView.setVisibility(View.VISIBLE);
                }
            } else {
                if (content != null && !content.isEmpty()) {
                    statusView.setBackgroundResource(R.drawable.status_dot_green);
                    statusView.setVisibility(View.VISIBLE);
                } else if (hasOriginalHtml && priority > 0) {
                    statusView.setBackgroundResource(R.drawable.status_dot_yellow);
                    statusView.setVisibility(View.VISIBLE);
                } else {
                    statusView.setBackgroundResource(R.drawable.status_dot_red);
                    statusView.setVisibility(View.VISIBLE);
                }
            }
        }
    }

    public interface EntryItemClickInterface {
        void onEntryClick(EntryInfo entryInfo);
        void onMoreButtonClick(long entryId, String link, boolean unread);
        void onBookmarkButtonClick(String bool, long id);
        void onSelectionModeChanged(boolean isSelectionMode);
        void onItemSelected(EntryInfo entryInfo);
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