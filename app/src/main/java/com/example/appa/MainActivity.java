package com.example.appa;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
public class MainActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView tv = new TextView(this);
        tv.setText("Hello from app-a");
        tv.setTextSize(28);
        setContentView(tv);
    }
}
