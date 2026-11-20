package com.example.appa;

import android.app.Activity;
import android.os.Bundle;

/** Entry point. All UI/logic lives in Kotlin (see MainScreen.kt). */
public class MainActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        MainScreenKt.showMainScreen(this);
    }
}
