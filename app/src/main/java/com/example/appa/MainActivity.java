package com.example.appa;

import android.app.Activity;
import android.os.Bundle;
import android.os.StrictMode;

/** Entry point. All UI/logic lives in Kotlin (see MainScreen.kt). */
public class MainActivity extends Activity {
    private MainScreen screen;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (BuildConfig.DEBUG) {
            // debug-only guardrail: would have flagged main-thread network I/O / leaks
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                    .detectAll().penaltyLog().build());
        }
        screen = MainScreenKt.showMainScreen(this);
    }

    @Override protected void onStart() {
        super.onStart();
        if (screen != null) screen.onStart();
    }

    @Override protected void onResume() {
        super.onResume();
        if (screen != null) screen.onResume();
    }

    @Override protected void onStop() {
        if (screen != null) screen.onStop();
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (screen != null) screen.detach();
        super.onDestroy();
    }
}
