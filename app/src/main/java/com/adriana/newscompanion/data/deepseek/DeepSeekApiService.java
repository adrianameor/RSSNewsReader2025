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
        public double temperature = 0.0;

        public CleanArticleRequest(String htmlToClean) {
            String prompt = "Extract ONLY the main news article body. REMOVE everything else.\n" +
                            "Delete: image credits (e.g. Getty Images, Reuters), captions, video/watch sections, related links, author bios, timestamps, social media prompts, ads, navigation, footers, headers, disclaimers, cookie notices, and verification labels.\n" +
                            "KEEP ONLY: the article headline (if present) and the core paragraphs that tell the story.\n\n" +
                            "HTML:\n" + htmlToClean;
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
