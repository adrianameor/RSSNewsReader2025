# URGENT ISSUES TO FIX

## Current Problems:

### 1. **YELLOW CIRCLES STUCK FOR 30+ MINUTES**
**Root Cause**: TtsExtractor processes articles ONE BY ONE in a WebView
- Each article takes 5-10 seconds to load and extract
- If you have 100 articles, that's 8-16 MINUTES minimum
- The extraction is SYNCHRONOUS and BLOCKING

**Why This Happens**:
```
Article 1: Load URL → Wait → Extract → Save (10 sec)
Article 2: Load URL → Wait → Extract → Save (10 sec)
Article 3: Load URL → Wait → Extract → Save (10 sec)
... 100 articles = 16+ minutes!
```

### 2. **AI Cleaning Only Runs on RSS Refresh**
**Root Cause**: AI cleaning code is inside `RssWorker.doWork()`
- Only triggers when RSS worker runs
- Does NOT trigger when you toggle AI cleaning setting
- Does NOT trigger when you change sort order

**Current Flow**:
```
User adds feed → RssWorker runs → Extract → AI Clean
User toggles AI cleaning → NOTHING HAPPENS
User changes sort order → NOTHING HAPPENS
```

### 3. **No Way to Manually Trigger AI Cleaning**
Users cannot force AI cleaning to run on existing articles

## Solutions Needed:

### Solution 1: Add Manual AI Cleaning Trigger
Create a button/menu option to manually trigger AI cleaning on all uncleaned articles

### Solution 2: Make AI Cleaning Run Independently  
Move AI cleaning to a separate Worker that can be triggered independently

### Solution 3: Speed Up Extraction
The TtsExtractor is the bottleneck - but this is a separate issue from AI cleaning

## Color Meanings (When Auto-Translate is OFF):

- 🔴 **RED**: No content (article just downloaded)
- 🟡 **YELLOW**: In extraction queue (waiting for TtsExtractor)
- 🔵 **BLUE**: Readability4J extracted (waiting for AI cleaning)
- 🟢 **GREEN**: AI-cleaned (final state)

## What User Sees:

1. Add feed → Articles appear with RED dots
2. Wait... → TtsExtractor starts, dots turn YELLOW
3. Wait 10-30 minutes... → Extraction completes, dots turn BLUE
4. Wait for next RSS refresh... → AI cleaning runs, dots turn GREEN

**This is TOO SLOW and TOO CONFUSING!**
