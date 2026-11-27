package com.signa.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.signa.app.ui.navigation.SignaApp
import com.signa.app.ui.theme.SignaTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.signa.app.data.local.session.SessionManager
import com.signa.app.data.local.submissions.PhotoSubmissionQueue

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var sessionManager: SessionManager
    @Inject lateinit var photoSubmissionQueue: PhotoSubmissionQueue

    override fun onStart() {
        super.onStart()
        sessionManager.revalidate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SignaTheme {
                SignaApp()
            }
        }
        photoSubmissionQueue.start()
    }
}
