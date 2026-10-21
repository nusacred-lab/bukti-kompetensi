package id.nusacred.portals

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import id.nusacred.contracts.ApiContract

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    check(ApiContract.VERSION == "1.0.0")
    ComposeViewport(document.getElementById("portal")!!) {
        Column(Modifier.padding(24.dp)) {
            BasicText("Digital Competency Proof")
            BasicText("Sign in with your custodian administrator account using the link above.")
        }
    }
}
