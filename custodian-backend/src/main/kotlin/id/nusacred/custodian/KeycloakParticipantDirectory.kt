package id.nusacred.custodian

import com.nimbusds.jose.util.JSONObjectUtils
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** A dedicated wallet-realm service account; never use a master/bootstrap admin. */
class KeycloakParticipantDirectory(private val config: OidcConfig,private val secret: String,
    private val transport: ((HttpRequest) -> Pair<Int,InputStream>)? = null) : ParticipantDirectory {
    private val client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
    private val realm=URI(config.issuer).path.removePrefix("/realms/")
    init { require(URI(config.issuer).path=="/realms/$realm" && realm!="master" && realm.matches(Regex("[A-Za-z0-9._-]+")));require(secret.length>=32) }
    private fun request(method: String,path: String,body: String?=null,token: String?=null,form: Boolean=false): Pair<Int,String> {
        require(path.startsWith("/realms/$realm/") || path.startsWith("/admin/realms/$realm/"))
        val builder=HttpRequest.newBuilder(URI(config.backchannelOrigin+path)).timeout(Duration.ofSeconds(10)).header("Accept","application/json")
        if(token!=null) builder.header("Authorization","Bearer $token")
        if(body!=null) builder.header("Content-Type",if(form) "application/x-www-form-urlencoded" else "application/json")
        builder.method(method,if(body==null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        val response=transport?.invoke(builder.build()) ?: client.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream()).let { it.statusCode() to it.body() }
        val bytes=response.second.use { it.readNBytes(131073) }
        check(bytes.size<=131072) { "Identity response limit" }
        return response.first to String(bytes,Charsets.UTF_8)
    }
    private fun users(base: String,email: String,token: String): List<*> {
        val (code,body)=request("GET",base+"/users?"+form(mapOf("email" to email,"exact" to "true")),token=token)
        check(code==200)
        return JSONObjectUtils.parse("{\"items\":$body}")["items"] as List<*>
    }
    override fun invite(email: String) {
        val (code,body)=request("POST","/realms/$realm/protocol/openid-connect/token",form(mapOf(
            "grant_type" to "client_credentials","client_id" to "wallet-invitations","client_secret" to secret)),form=true)
        check(code==200)
        val token=JSONObjectUtils.parse(body)["access_token"] as String
        val base="/admin/realms/$realm"
        if(users(base,email,token).isNotEmpty()) throw ParticipantExists()
        val (roleCode,role)=request("GET",base+"/roles/applicant",token=token)
        check(roleCode==200)
        val user=json(mapOf("username" to email,"email" to email,"enabled" to true,"emailVerified" to false,
            "firstName" to "Demo","lastName" to "Participant","requiredActions" to listOf("VERIFY_EMAIL","UPDATE_PASSWORD")))
        val created=request("POST",base+"/users",user,token).first
        if(created==409) throw ParticipantExists()
        check(created==201)
        val found=users(base,email,token)
        check(found.size==1)
        val id=(found.single() as Map<*,*>)["id"] as String
        check(id.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        check(request("POST",base+"/users/$id/role-mappings/realm","[$role]",token).first==204)
        check(request("PUT",base+"/users/$id/execute-actions-email?lifespan=900","[\"VERIFY_EMAIL\",\"UPDATE_PASSWORD\"]",token).first==204)
    }
    companion object {
        fun fromEnvironment(config: OidcConfig): KeycloakParticipantDirectory? = System.getenv("WALLET_INVITATION_CLIENT_SECRET_FILE")?.let {
            KeycloakParticipantDirectory(config,Files.readString(Path.of(it)).trim())
        }
    }
}
