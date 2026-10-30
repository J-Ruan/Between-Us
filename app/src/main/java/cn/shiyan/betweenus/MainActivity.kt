package cn.shiyan.betweenus

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {
    private var locked by mutableStateOf(true)
    private var authenticating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            Box(Modifier.fillMaxSize()) {
                // Keep launchers registered while locked, so SAF results survive authentication.
                ShiyanUi(unlocked = !locked)
                if (locked) {
                    Box(
                        Modifier.fillMaxSize().background(Color(0xFFFFFAF3)).clickable { open() },
                        contentAlignment = Alignment.Center,
                    ) { Text("点击验证身份，查看私人回忆", color = Color(0xFF945B66)) }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); open() }
    override fun onStop() {
        if (getSharedPreferences("settings", MODE_PRIVATE).getBoolean("lock", false)) locked = true
        super.onStop()
    }

    private fun open() {
        if (!getSharedPreferences("settings", MODE_PRIVATE).getBoolean("lock", false)) {
            locked = false
            return
        }
        if (!locked || authenticating) return
        authenticating = true
        val auth = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                authenticating = false
                locked = false
            }
            override fun onAuthenticationError(code: Int, message: CharSequence) {
                authenticating = false
                // Remain locked; allow retry without exposing the underlying library.
            }
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("解锁拾言")
            .setSubtitle("保护你的私人回忆").setAllowedAuthenticators(auth).build())
    }
}
