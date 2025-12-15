package com.adriana.newscompanion.data.deepseek;

import java.util.List;

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

    class DeepSeekRequest {
        public String model = "deepseek-coder";
        public List<Message> messages;
        public double temperature = 0.7;

        // This single constructor now handles both plain text and HTML
        public DeepSeekRequest(String textToTranslate, String sourceLang, String targetLang, boolean isHtml) {
            String prompt;
            if (isHtml) {
                prompt = String.format(
                        "You are an expert HTML translator. Translate the text content within the following HTML from %s to %s. " +
                                "Crucially, do not change any HTML tags or their attributes. Maintain the original HTML structure perfectly. " +
                                "Only translate the human-readable text between the tags. Do not translate proper nouns or technical terms. " +
                                "Only provide the translated HTML, with no additional explanations or introductions. Here is the HTML to translate:\n\n%s",
                        sourceLang, targetLang, textToTranslate
                );
            } else {
                prompt = String.format(
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
            String prompt = String.format(
                    "Summarize the following article into a concise summary of approximately %d words. " +
                            "Capture the key points, main arguments, and overall tone of the original text. " +
                            "Do not include any introductory phrases like \"This article discusses...\" or \"In summary...\". " +
                            "Just provide the summary directly. Here is the article:\n\n%s",
                    wordCount, textToSummarize
            );
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
