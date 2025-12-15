# TTS Language Detection Fix - Implementation Progress

## Problem
RSS feeds contain language information (e.g., `<language>ms-MY</language>`), but the app ignores it and tries to detect language automatically, which often fails and defaults to English.

## Solution Steps

### ✅ Step 1: Create Language Utility Class
- [x] Create `LanguageUtil.java` with language code normalization
- [x] Add mapping for common language codes (zh→zh-CN, ms→ms-MY, etc.)

### ✅ Step 2: Fix FeedRepository
- [x] Update `addNewFeed()` to prioritize RSS feed language
- [x] Use automatic detection only as fallback
- [x] Apply language normalization

### ✅ Step 3: Verify TtsPlayer
- [x] TtsPlayer already handles language codes correctly
- [x] No changes needed - it uses the feed language from EntryInfo

### ⏳ Step 4: Testing
- [ ] Test with Malay feed (should use ms-MY voice)
- [ ] Test with Chinese feed (should use zh-CN voice)
- [ ] Test with English feed (should use en-US voice)
- [ ] Test with feed without language tag (should auto-detect)

## Files to Modify
1. `app/src/main/java/com/adriana/newscompanion/service/util/LanguageUtil.java` (NEW)
2. `app/src/main/java/com/adriana/newscompanion/data/feed/FeedRepository.java`
3. `app/src/main/java/com/adriana/newscompanion/service/tts/TtsPlayer.java` (verify only)
