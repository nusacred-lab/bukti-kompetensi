package id.nusacred.portals

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport {
        Column(Modifier.padding(24.dp)) {
            BasicText("Digital Competency Proof")
            BasicText("Portal stack verification. Sign-in is not available yet.")
        }
    }
}
