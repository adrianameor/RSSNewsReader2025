# TODO: Fix TTS Navigation Behavior

## Approved Plan
- When navigating back to the same article, TTS should continue without stopping.
- When switching to a different article, stop current TTS and start new one.

## Steps
1. ✅ Update WebViewActivity.onNewIntent() - only stop TTS if newEntryId != currentId.
2. ✅ Update TtsService.onPrepareFromMediaId() - only stop TTS if mediaId != preparedData.mediaId.
3. Test the changes.

# TODO: Fix TTS to Read Title First

## Approved Plan
- Prepend article title to content before passing to TTS player.
- Use consistent delimiter "--####--" from TtsExtractor.

## Steps
1. ✅ Update WebViewActivity.java loadEntryContent() - prepend titleToDisplay to contentForTts for full article view.
2. ✅ Update TtsService.java onPrepareFromMediaId() - prepend appropriate title to content.
3. ✅ Update TtsService.java prepareAndPlayCurrentTrack() - prepend title when getting content from DB.
4. ✅ Ensure consistent use of ttsExtractor.delimiter in summary and translation handling.
5. Test the changes.
