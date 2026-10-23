package id.nusacred.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Presentation identity only. Authorization and trust are always checked by the backend. */
data class EntityIdentity(
    val brand: String, val legalName: String, val role: String, val icon: String,
    val purpose: String, val relationship: String,
    val ink: Color, val accent: Color, val soft: Color, val paper: Color,
    val muted: Color, val line: Color, val sidebar: Color
)

object Entities {
    val Issuer = EntityIdentity(
        "LSP Nusantara", "LSP Teknik Ketenagalistrikan Nusantara (demo)", "Credential issuer", "document",
        "Certifying the skills behind good work.",
        "This certifier issues your competency proof. Your wallet provider stores it, and the employer evaluates it independently.",
        Color(0xFF29264C), Color(0xFF5140B5), Color(0xFFEEEBFA), Color(0xFFF6F5FC),
        Color(0xFF69647E), Color(0xFFE2DEEF), Color(0xFF262348)
    )
    val Wallet = EntityIdentity(
        "NusaCred Wallet", "PT Dompet Identitas Nusantara (demo)", "Wallet provider", "wallet",
        "Your credentials. Safely in your hands.",
        "Your independent wallet provider stores your credentials. Your certifier issues them; the employer makes its own verification decision.",
        Color(0xFF183331), Color(0xFF087B69), Color(0xFFE5F2EB), Color(0xFFF5F6F2),
        Color(0xFF667873), Color(0xFFDFE6DF), Color(0xFF183331)
    )
    val Company = EntityIdentity(
        "Karya Instalasi", "PT Karya Instalasi Nusantara (demo)", "Employer & verifier", "briefcase",
        "Find the skills that move work forward.",
        "This employer sets its own requirements and trusted certifiers. Credentials reach it only after you approve sharing in your independent wallet.",
        Color(0xFF253248), Color(0xFFA34720), Color(0xFFFAECE3), Color(0xFFFBF7F3),
        Color(0xFF6C6D75), Color(0xFFE4DED7), Color(0xFF172236)
    )
    fun forService(service: String) = when(service) { "issuer" -> Issuer; "company" -> Company; else -> Wallet }
}
internal val LocalEntity = staticCompositionLocalOf { Entities.Wallet }
@Composable fun EntityTheme(identity: EntityIdentity, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalEntity provides identity, content=content)
}
@Composable fun OrganizationIdentity(identity: EntityIdentity, compact: Boolean=false) {
    Column(verticalArrangement=Arrangement.spacedBy(if(compact) 5.dp else 10.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Icon(identity.icon,identity.accent,Modifier.size(18.dp));Eyebrow(identity.role,identity.accent)
        }
        Label(identity.legalName,if(compact) 12 else 18,weight=FontWeight.SemiBold)
        if(!compact) Label(identity.relationship,13,Palette.Muted)
    }
}
