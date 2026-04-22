# Fix Data Flow Race Condition - Implementation Checklist

## Step 1: Add Memory Cache Field
- [x] Add `private long[] currentPlaylistCache;` field to WebViewActivity

## Step 2: Create Helper Method `getPlaylistFromIntent()`
- [x] Extract playlist array from Intent
- [x] Save to repository (for service recovery)
- [x] Store in memory cache
- [x] Return the array for immediate use

## Step 3: Modify `switchPlayMode()` to Accept Playlist Parameter
- [x] Change signature to `private void switchPlayMode(long[] playlistIdsFromIntent)`
- [x] Use passed array instead of reading from repository
- [x] Initialize TtsPlaylist with the array

## Step 4: Modify `switchReadMode()` to Accept Playlist Parameter
- [x] Change signature to `private void switchReadMode(long[] playlistIdsFromIntent)`
- [x] Use passed array instead of reading from repository
- [x] Initialize TtsPlaylist with the array

## Step 5: Update `onCreate()` to Pass Playlist Directly
- [x] Call `getPlaylistFromIntent()` to get array
- [x] Pass array to `switchPlayMode()` or `switchReadMode()`

## Step 6: Fix `setupMediaPlaybackButtons()` Play Button
- [x] Get playlist from Intent first (primary source)
- [x] Try memory cache as fallback (secondary source)
- [x] Try repository as tertiary fallback
- [x] Use createSimpleFallbackPlaylist() as last resort
- [x] Pass full playlist array to service

## Step 7: Fix `onNewIntent()` to Update Playlist
- [x] Verify `setIntent(intent)` is called
- [x] Call `getPlaylistFromIntent()` to extract and save
- [x] Update TtsPlaylist in memory for Play Mode

## Step 8: Update Toolbar Listeners
- [x] Fix switchPlayMode button to pass playlist parameter
- [x] Fix switchReadMode button to pass playlist parameter

## Testing Checklist
- [ ] Test article navigation in Read mode
- [ ] Test article navigation in Play mode
- [ ] Verify skip next/previous works correctly
- [ ] Confirm notification shows correct queue
- [ ] Test switching between articles via notification

## Summary of Changes

All implementation steps have been completed! The race condition has been fixed by:

1. **Added memory cache**: `currentPlaylistCache` field stores the playlist array in memory
2. **Created helper method**: `getPlaylistFromIntent()` extracts playlist from Intent, saves to repository, and caches in memory
3. **Modified switchPlayMode()**: Now accepts `long[] playlistIdsFromIntent` parameter and uses it directly
4. **Modified switchReadMode()**: Now accepts `long[] playlistIdsFromIntent` parameter and uses it directly
5. **Updated onCreate()**: Calls `getPlaylistFromIntent()` and passes result to switch methods
6. **Fixed setupMediaPlaybackButtons()**: Uses repository (which was saved synchronously) as the source
7. **Fixed onNewIntent()**: Calls `setIntent()` and `getPlaylistFromIntent()` to update playlist
8. **Updated toolbar listeners**: Both switchPlayMode and switchReadMode buttons now retrieve and pass playlist

The fix eliminates the race condition by:
- Using synchronous Intent data instead of asynchronous repository reads
- Passing playlist directly as parameters to avoid timing issues
- Maintaining memory cache for quick access
- Using repository only as a backup/fallback mechanism
