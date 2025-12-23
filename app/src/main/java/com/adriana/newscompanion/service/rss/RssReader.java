package com.adriana.newscompanion.service.rss;

import android.util.Log;

import org.xml.sax.InputSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

public class RssReader {
    private String rssUrl;
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36";

    public RssReader(String url) {
        // Ensure we use HTTPS but allow redirects to handle the rest
        this.rssUrl = url.replace("http://", "https://");
    }

    public RssFeed getFeed() throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(rssUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            
            // CRITICAL: Set a real User-Agent to avoid 403/429 blocks
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml, */*");
            connection.setInstanceFollowRedirects(true);

            int responseCode = connection.getResponseCode();
            Log.d("RssReader", "Fetching: " + rssUrl + " | Status: " + responseCode);

            if (responseCode == 429) {
                String retryAfter = connection.getHeaderField("Retry-After");
                Log.e("RssReader", "RATE LIMITED (429). Server says wait: " + retryAfter + " seconds.");
                throw new Exception("Rate limited by server. Retry later.");
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new Exception("HTTP Error " + responseCode + " for URL: " + rssUrl);
            }

            // Parse the RSS feed
            SAXParserFactory factory = SAXParserFactory.newInstance();
            SAXParser saxParser = factory.newSAXParser();
            RssHandler handler = new RssHandler();

            Reader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            InputSource inputSource = new InputSource(reader);
            saxParser.parse(inputSource, handler);

            RssFeed feed = handler.getRssFeed();

            if (feed == null || feed.getRssItems().isEmpty()) {
                // If it's 200 OK but parsing fails, it might be an HTML "Bot Challenge" page
                Log.e("RssReader", "XML parsing failed. The response might be HTML/Bot Protection.");
                throw new Exception("Parsed feed is empty. Possible bot protection interference.");
            }

            return feed;

        } catch (Exception e) {
            Log.e("RssReader", "Error for " + rssUrl + ": " + e.getMessage());
            throw e;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
