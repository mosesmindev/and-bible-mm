package net.bible.android.view.activity;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;


import net.bible.android.activity.R;
import net.bible.android.activity.StartupActivity;


public class SplashActivity extends AppCompatActivity {

	private static final String TAG = "SplashActivity";


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
		Log.d(TAG, "onCreate: create splash");
        setContentView(R.layout.startup_view);

		// 使用 Handler 延迟跳转 -- Use the Handler to delay jumps
		new Handler().postDelayed(new Runnable() {
			@Override
			public void run() {
				// 跳转到启动Activity -- Go to Start Activity
				Intent intent = new Intent(SplashActivity.this, StartupActivity.class);
				startActivity(intent);
				finish(); // 结束 SplashActivity -- End the SplashActivity
			}
		}, 4000); // 延迟4s -- Delay 4s

    }

}