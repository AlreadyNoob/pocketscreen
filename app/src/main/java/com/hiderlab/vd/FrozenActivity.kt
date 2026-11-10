package com.hiderlab.vd

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/**
 * A blank screen shown on a hidden display on top of a frozen app. Covering the app puts it in the
 * background, which is what lets the phone's Hiber freezer freeze it. It closes itself as soon as
 * the app is brought back to the front (noHistory).
 */
class FrozenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "App frozen\nResume it from PocketScreen"
            setTextColor(Color.parseColor("#9AA4B2"))
            setBackgroundColor(Color.parseColor("#0E1116"))
            textSize = 16f
            gravity = Gravity.CENTER
        })
    }
}
