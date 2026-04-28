package com.prince.turtlekeyboard;


import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import com.prince.turtlekeyboard.databinding.ActivityMainBinding;

public class MainActivity extends Activity {

    // Used to load the 'turtlekeyboard' library on application startup.
//    static {
//        System.loadLibrary("turtlekeyboard");
//    }

    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Example of a call to a native method
        TextView tv = binding.sampleText;
//        tv.setText(stringFromJNI());
    }

    /**
     * A native method that is implemented by the 'turtlekeyboard' native library,
     * which is packaged with this application.
     */
//    public native String stringFromJNI();
}