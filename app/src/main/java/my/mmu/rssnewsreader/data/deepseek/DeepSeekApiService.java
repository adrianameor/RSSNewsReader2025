package my.mmu.rssnewsreader.data.deepseek;

import com.google.gson.annotations.SerializedName;

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

    class DeepSeekRequest {
        public String model = "deepseek-coder";
        public List<Message> messages;
        public double temperature = 0.7;

        public DeepSeekRequest(String htmlToTranslate, String sourceLang, String targetLang) {
            // This is the new, smarter prompt for translating HTML content.
            String prompt = String.format(
                "You are an expert HTML translator. Translate the text content within the following HTML from %s to %s. " +
                "Crucially, do not change any HTML tags or their attributes. Maintain the original HTML structure perfectly. " +
                "Only translate the human-readable text between the tags. Do not translate proper nouns or technical terms. " +
                "Only provide the translated HTML, with no additional explanations or introductions. Here is the HTML to translate:\n\n%s",
                sourceLang, targetLang, htmlToTranslate
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
