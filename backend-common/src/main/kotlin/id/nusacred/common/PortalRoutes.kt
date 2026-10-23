package id.nusacred.common

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.staticFiles
import java.io.File
import java.net.URLEncoder

fun Application.portalRoutes(service: String, assets: File? = null) {
    require(service in setOf("issuer","company","custodian"))
    routing {
        get("/api/v1/portal-config") { call.api { call.reply(mapOf("service" to service,"brand" to "NusaCred",
            "walletOrigin" to walletPublicOrigin(),"environment" to "Synthetic demo")) } }
        if(service != "custodian") get("/health/live") { call.api { call.reply(mapOf("status" to "up")) } }
        if(assets != null) { require(assets.isDirectory);staticFiles("/",assets) }
    }
}
fun walletPublicOrigin(): String {
    val origin=System.getenv("WALLET_PUBLIC_ORIGIN") ?: "https://dompet-identitas.demo.inoerawan.id"
    require(origin in setOf("https://dompet-identitas.demo.inoerawan.id","https://wallet.demo.inoerawan.id"))
    return origin
}
fun walletLink(reference: String, receiving: Boolean): String = walletPublicOrigin()+
    (if(receiving) "/receive?offer=" else "/present?request_url=")+URLEncoder.encode(reference,Charsets.UTF_8)
fun qrResponse(value: String): Map<String,Any?> {
    require(value.length<=8192)
    val matrix=QRCodeWriter().encode(value,BarcodeFormat.QR_CODE,1,1,mapOf(EncodeHintType.MARGIN to 0,EncodeHintType.CHARACTER_SET to "UTF-8"))
    return mapOf("value" to value,"modules" to (0 until matrix.height).map { y ->
        (0 until matrix.width).joinToString("") { x -> if(matrix[x,y]) "1" else "0" }
    })
}
