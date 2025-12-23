package com.adriana.newscompanion.data.deepseek;

import java.util.List;
import java.util.Locale;

import io.reactivex.rxjava3.core.Single;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.POST;

public interface DeepSeekApiService {

    @POST("v1/chat/completions")
    Single<DeepSeekResponse> translate(
            @Header("Authorization") String apiKey,
            @Body DeepSeekRequest request
    );

    @POST("v1/chat/completions")
    Single<DeepSeekResponse> summarize(
            @Header("Authorization") String apiKey,
            @Body SummarizeRequest request
    );

    @POST("v1/chat/completions")
    Single<DeepSeekResponse> cleanArticle(
            @Header("Authorization") String apiKey,
            @Body CleanArticleRequest request
    );

    class DeepSeekRequest {
        public String model = "deepseek-coder";
        public List<Message> messages;
        public double temperature = 0.7;

        public DeepSeekRequest(String textToTranslate, String sourceLang, String targetLang, boolean isHtml) {
            String prompt;
            if (isHtml) {
                prompt = String.format(Locale.US,
                        "You are an expert HTML translator. Translate all human-readable text content within the following HTML from %s to %s. " +
                                "This includes article body text, headings, and crucially, all image captions (figcaption or caption tags). " +
                                "Do not change any HTML tags or their attributes. Maintain the original HTML structure perfectly. " +
                                "Only provide the translated HTML, with no additional explanations. Here is the HTML to translate:\n\n%s",
                        sourceLang, targetLang, textToTranslate
                );
            } else {
                prompt = String.format(Locale.US,
                        "Translate the following text from %s to %s. Do not translate proper nouns or technical terms. Only provide the translation, with no additional explanations. Text: %s",
                        sourceLang, targetLang, textToTranslate
                );
            }
            this.messages = List.of(new Message("user", prompt));
        }
    }

    class SummarizeRequest {
        public String model = "deepseek-coder";
        public List<Message> messages;
        public double temperature = 0.7;

        public SummarizeRequest(String textToSummarize, int wordCount) {
            this(textToSummarize, wordCount, null);
        }

        public SummarizeRequest(String textToSummarize, int wordCount, String targetLanguage) {
            String prompt;
            if (targetLanguage != null && !targetLanguage.isEmpty()) {
                String targetLangName = new Locale(targetLanguage).getDisplayLanguage(Locale.ENGLISH);
                prompt = String.format(Locale.US,
                        "You are a helpful assistant. First, identify the language of the following article. Don't tell me the language." +
                                "Then, summarize the article DIRECTLY into %s in approximately %d words. " +
                                "Capture the key points, main arguments, and overall tone of the original text. " +
                                "IMPORTANT: Structure the output into multiple paragraphs for readability. Use \\n\\n to separate paragraphs. " +
                                "Do not include any introductory phrases like \"This article discusses...\" or \"In summary...\". " +
                                "Just provide the summary text directly in %s. Here is the article:\n\n%s",
                        targetLangName, wordCount, targetLangName, textToSummarize
                );
            } else {
                prompt = String.format(Locale.US,
                        "You are a helpful assistant. First, identify the main language of the following article. Don't tell me the language. " +
                                "Then, summarize the article in that same language in approximately %d words. " +
                                "Capture the key points, main arguments, and overall tone of the original text. " +
                                "IMPORTANT: Structure the output into multiple paragraphs for readability. Use \\n\\n to separate paragraphs. " +
                                "Do not include any introductory phrases like \"This article discusses...\" or \"In summary...\". " +
                                "Just provide the summary text directly. Here is the article:\n\n%s",
                        wordCount, textToSummarize
                );
            }
            this.messages = List.of(new Message("user", prompt));
        }
    }

    class CleanArticleRequest {
        public String model = "deepseek-coder";
        public List<Message> messages;
        public double temperature = 0.1;

        public CleanArticleRequest(String htmlToClean) {
            String prompt = "You are an AGGRESSIVE HTML cleaner. Your PRIMARY GOAL is to remove ALL clutter and keep ONLY the main article content. Be ruthless in removing anything that is not core article text.\n\n" +
                    "REMOVE COMPLETELY (delete the entire element and its content):\n" +
                    "1. ANY text containing: 'Related', 'Read more', 'You might also like', 'More stories', 'Recommended', 'Trending'\n" +
                    "2. ANY text containing: 'Subscribe', 'Sign up', 'Newsletter', 'Email', 'Log in', 'Sign in', 'Register', 'Join'\n" +
                    "3. ANY text containing: 'Share', 'Tweet', 'Facebook', 'Twitter', 'LinkedIn', 'WhatsApp', 'Instagram'\n" +
                    "4. ANY text containing: 'Image source', 'Image caption', 'Photo caption', 'Getty Images', 'Reuters', 'AP Photo', 'AFP', 'Photo by', 'Image credit', 'Photograph:'\n" +
                    "5. ANY text containing: 'Video', 'Watch', 'Play', 'Duration', 'Listen', 'Podcast'\n" +
                    "6. ANY text containing: 'Comment', 'Comments', 'Join the conversation', 'Leave a comment'\n" +
                    "7. ANY text containing: 'Advertisement', 'Sponsored', 'Promoted', 'Ad'\n" +
                    "8. ANY text containing: 'Cookie', 'Privacy', 'Terms', 'Policy'\n" +
                    "9. ANY text containing: 'Homepage', 'Home page', 'Back to', 'Return to', 'Go to'\n" +
                    "10. ANY standalone bylines like 'By [Name]', 'Written by', 'Reporter:', 'Author:'\n" +
                    "11. ANY navigation elements, breadcrumbs, menus\n" +
                    "12. ANY social media widgets, sharing buttons, follow buttons\n" +
                    "13. ANY links that say 'Click here', 'Learn more', 'Find out more'\n" +
                    "14. ANY copyright notices, disclaimers at the bottom\n" +
                    "15. ANY 'Most read', 'Most popular', 'Top stories' sections\n\n" +
                    "KEEP ONLY:\n" +
                    "- Main article paragraphs\n" +
                    "- Article headings (h2, h3, etc.)\n" +
                    "- Essential images that are part of the story\n" +
                    "- Block quotes that are part of the article\n" +
                    "- Lists that are part of the article content\n\n" +
                    "CRITICAL RULES:\n" +
                    "- If you see ANY of the removal keywords above, DELETE that entire element\n" +
                    "- Be AGGRESSIVE - when in doubt, REMOVE it\n" +
                    "- Do NOT keep navigation, sidebars, footers, headers\n" +
                    "- Do NOT keep any metadata, attribution, or source information\n" +
                    "- Return ONLY the cleaned HTML with NO explanations\n" +
                    "- Maintain valid HTML structure\n\n" +
                    "HTML to clean:\n\n" + htmlToClean;

            this.messages = List.of(new Message("user", prompt));
        }
    }

    class Message {
        public String role;
        public String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    class DeepSeekResponse {
        public List<Choice> choices;
    }

    class Choice {
        public Message message;
    }
}
