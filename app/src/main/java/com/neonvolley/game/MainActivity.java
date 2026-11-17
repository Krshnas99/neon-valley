package com.neonvolley.game;

import android.app.Activity;
import android.media.AudioManager;
import android.os.Bundle;

public class MainActivity extends Activity {
    private GameView view;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setVolumeControlStream(AudioManager.STREAM_MUSIC); // volume keys control game sound
        view = new GameView(this);
        setContentView(view);
    }

    @Override
    protected void onDestroy() {
        if (view != null) view.release();
        super.onDestroy();
    }
}
