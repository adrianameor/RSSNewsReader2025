package com.adriana.newscompanion.service.util;

import android.util.Log;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Utility class for normalizing and handling language codes for TTS compatibility.
 * Converts various language code formats to proper Android TTS Locale codes.
 */
public class LanguageUtil {
    
    private static final String TAG = "LanguageUtil";
    
    // Map of common language codes to their preferred TTS locale codes
    private static final Map<String, String> LANGUAGE_CODE_MAP = new HashMap<>();
    
    static {
        // Chinese variants
        LANGUAGE_CODE_MAP.put("zh", "zh-CN");           // Chinese → Simplified Chinese
        LANGUAGE_CODE_MAP.put("zh-hans", "zh-CN");      // Simplified Chinese
        LANGUAGE_CODE_MAP.put("zh-hant", "zh-TW");      // Traditional Chinese
        LANGUAGE_CODE_MAP.put("zh-cn", "zh-CN");        // Simplified Chinese (China)
        LANGUAGE_CODE_MAP.put("zh-tw", "zh-TW");        // Traditional Chinese (Taiwan)
        LANGUAGE_CODE_MAP.put("zh-hk", "zh-HK");        // Traditional Chinese (Hong Kong)
        
        // Malay variants
        LANGUAGE_CODE_MAP.put("ms", "ms-MY");           // Malay → Malaysian Malay
        LANGUAGE_CODE_MAP.put("ms-my", "ms-MY");        // Malaysian Malay
        
        // English variants
        LANGUAGE_CODE_MAP.put("en", "en-US");           // English → US English
        LANGUAGE_CODE_MAP.put("en-us", "en-US");        // US English
        LANGUAGE_CODE_MAP.put("en-gb", "en-GB");        // British English
        LANGUAGE_CODE_MAP.put("en-au", "en-AU");        // Australian English
        
        // Spanish variants
        LANGUAGE_CODE_MAP.put("es", "es-ES");           // Spanish → Spain Spanish
        LANGUAGE_CODE_MAP.put("es-es", "es-ES");        // Spain Spanish
        LANGUAGE_CODE_MAP.put("es-mx", "es-MX");        // Mexican Spanish
        
        // French variants
        LANGUAGE_CODE_MAP.put("fr", "fr-FR");           // French → France French
        LANGUAGE_CODE_MAP.put("fr-fr", "fr-FR");        // France French
        LANGUAGE_CODE_MAP.put("fr-ca", "fr-CA");        // Canadian French
        
        // German
        LANGUAGE_CODE_MAP.put("de", "de-DE");           // German → Germany German
        LANGUAGE_CODE_MAP.put("de-de", "de-DE");        // Germany German
        
        // Japanese
        LANGUAGE_CODE_MAP.put("ja", "ja-JP");           // Japanese
        LANGUAGE_CODE_MAP.put("ja-jp", "ja-JP");        // Japanese
        
        // Korean
        LANGUAGE_CODE_MAP.put("ko", "ko-KR");           // Korean
        LANGUAGE_CODE_MAP.put("ko-kr", "ko-KR");        // Korean
        
        // Indonesian
        LANGUAGE_CODE_MAP.put("id", "id-ID");           // Indonesian
        LANGUAGE_CODE_MAP.put("id-id", "id-ID");        // Indonesian
        
        // Thai
        LANGUAGE_CODE_MAP.put("th", "th-TH");           // Thai
        LANGUAGE_CODE_MAP.put("th-th", "th-TH");        // Thai
        
        // Vietnamese
        LANGUAGE_CODE_MAP.put("vi", "vi-VN");           // Vietnamese
        LANGUAGE_CODE_MAP.put("vi-vn", "vi-VN");        // Vietnamese
        
        // Portuguese variants
        LANGUAGE_CODE_MAP.put("pt", "pt-BR");           // Portuguese → Brazilian Portuguese
        LANGUAGE_CODE_MAP.put("pt-br", "pt-BR");        // Brazilian Portuguese
        LANGUAGE_CODE_MAP.put("pt-pt", "pt-PT");        // European Portuguese
        
        // Italian
        LANGUAGE_CODE_MAP.put("it", "it-IT");           // Italian
        LANGUAGE_CODE_MAP.put("it-it", "it-IT");        // Italian
        
        // Russian
        LANGUAGE_CODE_MAP.put("ru", "ru-RU");           // Russian
        LANGUAGE_CODE_MAP.put("ru-ru", "ru-RU");        // Russian
        
        // Arabic
        LANGUAGE_CODE_MAP.put("ar", "ar-SA");           // Arabic → Saudi Arabic
        LANGUAGE_CODE_MAP.put("ar-sa", "ar-SA");        // Saudi Arabic
        
        // Hindi
        LANGUAGE_CODE_MAP.put("hi", "hi-IN");           // Hindi
        LANGUAGE_CODE_MAP.put("hi-in", "hi-IN");        // Hindi
        
        // Tamil
        LANGUAGE_CODE_MAP.put("ta", "ta-IN");           // Tamil
        LANGUAGE_CODE_MAP.put("ta-in", "ta-IN");        // Tamil
    }
    
    /**
     * Normalizes a language code to a TTS-compatible locale code.
     * 
     * Priority:
     * 1. If the code is already in proper format (e.g., "ms-MY"), return as-is
     * 2. If the code has a mapping (e.g., "zh" → "zh-CN"), return the mapped value
     * 3. If the code is a simple 2-letter code, try to create a locale
     * 4. If all else fails, return "en-US" as fallback
     * 
     * @param languageCode The language code to normalize (e.g., "zh", "ms-MY", "en")
     * @return Normalized language code suitable for TTS (e.g., "zh-CN", "ms-MY", "en-US")
     */
    public static String normalizeLanguageCode(String languageCode) {
        if (languageCode == null || languageCode.trim().isEmpty()) {
            Log.w(TAG, "Language code is null or empty, defaulting to en-US");
            return "en-US";
        }
        
        // Clean up the input
        String cleaned = languageCode.trim().toLowerCase(Locale.ROOT);
        
        // Check if we have a direct mapping
        if (LANGUAGE_CODE_MAP.containsKey(cleaned)) {
            String normalized = LANGUAGE_CODE_MAP.get(cleaned);
            Log.d(TAG, "Normalized language code: " + languageCode + " → " + normalized);
            return normalized;
        }
        
        // If it's already in the format "xx-YY", validate and return
        if (cleaned.matches("[a-z]{2}-[a-z]{2}")) {
            // Convert to proper case: "ms-my" → "ms-MY"
            String[] parts = cleaned.split("-");
            String normalized = parts[0] + "-" + parts[1].toUpperCase(Locale.ROOT);
            Log.d(TAG, "Language code already in proper format: " + languageCode + " → " + normalized);
            return normalized;
        }
        
        // If it's a simple 2-letter code not in our map, try to create a basic locale
        if (cleaned.matches("[a-z]{2}")) {
            // For unmapped 2-letter codes, use the code itself with uppercase country
            String normalized = cleaned + "-" + cleaned.toUpperCase(Locale.ROOT);
            Log.d(TAG, "Created basic locale for unmapped code: " + languageCode + " → " + normalized);
            return normalized;
        }
        
        // Last resort: default to English
        Log.w(TAG, "Could not normalize language code: " + languageCode + ", defaulting to en-US");
        return "en-US";
    }
    
    /**
     * Checks if a language code is valid and supported.
     * 
     * @param languageCode The language code to check
     * @return true if the code appears valid, false otherwise
     */
    public static boolean isValidLanguageCode(String languageCode) {
        if (languageCode == null || languageCode.trim().isEmpty()) {
            return false;
        }
        
        String cleaned = languageCode.trim().toLowerCase(Locale.ROOT);
        
        // Check if it's in our map or matches valid patterns
        return LANGUAGE_CODE_MAP.containsKey(cleaned) 
                || cleaned.matches("[a-z]{2}") 
                || cleaned.matches("[a-z]{2}-[a-z]{2}");
    }
    
    /**
     * Gets the display name for a language code.
     * 
     * @param languageCode The language code (e.g., "zh-CN", "ms-MY")
     * @return The display name (e.g., "Chinese (China)", "Malay (Malaysia)")
     */
    public static String getDisplayName(String languageCode) {
        if (languageCode == null || languageCode.trim().isEmpty()) {
            return "Unknown";
        }
        
        try {
            String normalized = normalizeLanguageCode(languageCode);
            String[] parts = normalized.split("-");
            
            if (parts.length == 2) {
                Locale locale = new Locale(parts[0], parts[1]);
                return locale.getDisplayName();
            } else {
                Locale locale = new Locale(parts[0]);
                return locale.getDisplayName();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting display name for: " + languageCode, e);
            return languageCode;
        }
    }
}
