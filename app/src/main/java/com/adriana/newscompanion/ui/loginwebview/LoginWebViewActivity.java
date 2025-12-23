package com.adriana.newscompanion.ui.loginwebview;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.adriana.newscompanion.R;
import com.adriana.newscompanion.data.sharedpreferences.SharedPreferencesRepository;
import com.adriana.newscompanion.databinding.ActivityLoginwebviewBinding;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class LoginWebViewActivity extends AppCompatActivity {

    private ActivityLoginwebviewBinding binding;
    private LinearProgressIndicator loading;
    private WebView webView;
    private String link;
    private FloatingActionButton fabDone;
    private Handler handler = new Handler(Looper.getMainLooper());

    @Inject
    SharedPreferencesRepository sharedPreferencesRepository;

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            showLoginCompleteDialog();
        }
    }

    private void showLoginCompleteDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(LoginWebViewActivity.this);
        builder.setTitle(R.string.login_complete_confirmation)
                .setIcon(R.drawable.ic_alert)
                .setMessage(R.string.login_complete_message)
                .setNeutralButton(R.string.no, (dialogInterface, i) -> {
                    // User cancelled login - set RESULT_CANCELED and finish
                    setResult(Activity.RESULT_CANCELED);
                    finish();
                })
                .setPositiveButton(R.string.yes, (dialogInterface, i) -> {
                    // User confirmed login complete - set RESULT_OK and finish
                    setResult(Activity.RESULT_OK, new Intent());
                    finish();
                })
                .setCancelable(false)
                .show();
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        link = getIntent().getStringExtra("link");

        binding = ActivityLoginwebviewBinding.inflate(getLayoutInflater());

        loading = binding.loginWebViewLoading;
        fabDone = binding.fabDone;

        // Initially hide the FAB
        fabDone.setVisibility(View.GONE);

        // Set up FAB click listener
        fabDone.setOnClickListener(v -> showLoginCompleteDialog());

        MaterialToolbar toolbar = binding.loginWebViewToolbar;
        toolbar.setNavigationOnClickListener(view -> onBackPressed());
        toolbar.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.loginWebViewZoomIn) {
                int newTextZoom = webView.getSettings().getTextZoom() + 10;
                webView.getSettings().setTextZoom(newTextZoom);
                sharedPreferencesRepository.setTextZoom(newTextZoom);
                return true;
            } else if (itemId == R.id.loginWebViewZoomOut) {
                int newTextZoom = webView.getSettings().getTextZoom() - 10;
                webView.getSettings().setTextZoom(newTextZoom);
                sharedPreferencesRepository.setTextZoom(newTextZoom);
                return true;
            }
            return false;
        });

        webView= binding.loginWebView;
        webView.setWebViewClient(new WebClient());
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);

        int textZoom = sharedPreferencesRepository.getTextZoom();
        if (textZoom != 0) {
            webView.getSettings().setTextZoom(textZoom);
        }

        if (sharedPreferencesRepository.getNight()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                webView.getSettings().setForceDark(WebSettings.FORCE_DARK_ON);
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                webView.getSettings().setForceDark(WebSettings.FORCE_DARK_OFF);
            }
        }

        webView.loadUrl(link);

        Log.d("Test url",link);
        setContentView(binding.getRoot());
    }

    private class WebClient extends WebViewClient {

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon){
            super.onPageStarted(view,url,favicon);
            // Hide FAB when new page starts loading
            fabDone.setVisibility(View.GONE);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view,String url){
            view.loadUrl(url);
            return true;
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            super.onPageCommitVisible(view, url);
            loading.setVisibility(View.INVISIBLE);
            
            // Show FAB after page loads with a slight delay
            handler.postDelayed(() -> {
                if (fabDone != null) {
                    fabDone.show();
                }
            }, 1000); // 1 second delay to ensure page is fully loaded
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
