package com.adriana.newscompanion.di;

import java.util.concurrent.TimeUnit;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;
import com.adriana.newscompanion.data.deepseek.DeepSeekApiService;
import com.adriana.newscompanion.data.repository.TranslationRepository; // Import the missing class
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory;
import retrofit2.converter.gson.GsonConverterFactory;


@Module
@InstallIn(SingletonComponent.class)
public class NetworkModule {

    private static final String DEEPSEEK_API_BASE_URL = "https://api.deepseek.com/";

    @Provides
    @Singleton
    public OkHttpClient provideOkHttpClient() {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BODY);
        return new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build();
    }

    @Provides
    @Singleton
    public Retrofit provideRetrofit(OkHttpClient okHttpClient) {
        return new Retrofit.Builder()
                .baseUrl(DEEPSEEK_API_BASE_URL)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .addCallAdapterFactory(RxJava3CallAdapterFactory.create())
                .build();
    }

    @Provides
    @Singleton
    public DeepSeekApiService provideDeepSeekApiService(Retrofit retrofit) {
        return retrofit.create(DeepSeekApiService.class);
    }

    // This is the blueprint that tells Hilt how to build a TranslationRepository.
    @Provides
    @Singleton
    public static TranslationRepository provideTranslationRepository(DeepSeekApiService deepSeekApiService) {
        return new TranslationRepository(deepSeekApiService);
    }

}