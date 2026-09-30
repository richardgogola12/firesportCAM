package sk.firesport.cam

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/** Návod na použitie (assets/navod.html). */
class HelpActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Návod"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val web = WebView(this)
        web.settings.javaScriptEnabled = false
        web.setBackgroundColor(0xFF121212.toInt())
        setContentView(web)
        web.loadUrl("file:///android_asset/navod.html")
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
