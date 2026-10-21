package id.nusacred.integration

import com.nimbusds.jose.util.JSONObjectUtils
import id.nusacred.common.*
import id.nusacred.contracts.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.*

class BrowserContractTest {
    @Test fun `public browser results require the originating session and csrf`() = testApplication {
        val store = DomainStore(Files.createTempFile("p3-browser-", ".sqlite"),"company")
        val admin = Principal("admin","company-a",setOf(Role.COMPANY_ADMIN),"admin-session")
        store.saveJob(admin,"electrician","Electrician","DEMO-ELEC-001","competent","key-000000000001")
        val guard = RequestGuard("https://company.example",{ _, _ -> error("No authenticated session") })
        application { businessRoutes(store,guard) }
        val start=client.get(ApiContract.PREFIX+"/browser-session")
        assertEquals(200,start.status.value)
        val cookie=start.headers["Set-Cookie"]!!.substringBefore(';')
        assertTrue(start.headers["Set-Cookie"]!!.contains("Secure"))
        assertTrue(start.headers["Set-Cookie"]!!.contains("HttpOnly"))
        val csrf=JSONObjectUtils.parse(start.bodyAsText())["csrf"] as String
        val path=ApiContract.PREFIX+"/jobs/electrician/verification-sessions"
        assertEquals(401,client.post(path).status.value)
        assertEquals(403,client.post(path) { header("Cookie",cookie) }.status.value)
        val session=client.post(path) {
            header("Cookie",cookie); header("Origin","https://company.example"); header("X-CSRF-Token",csrf)
            header("Content-Type","application/json"); header("Idempotency-Key","key-000000000002"); setBody("{}")
        }
        assertEquals(202,session.status.value)
        val body=JSONObjectUtils.parse(session.bodyAsText())
        assertEquals(Decision.INCOMPLETE.name,body["decision"])
        val id=body["id"] as String
        val result=client.get(ApiContract.PREFIX+"/verification-sessions/"+id) { header("Cookie",cookie) }
        assertEquals(200,result.status.value)
        val second=client.get(ApiContract.PREFIX+"/browser-session").headers["Set-Cookie"]!!.substringBefore(';')
        assertEquals(404,client.get(ApiContract.PREFIX+"/verification-sessions/"+id) { header("Cookie",second) }.status.value)
        assertEquals(401,client.get(ApiContract.PREFIX+"/verification-sessions/"+id).status.value)
        val error=JSONObjectUtils.parse(client.get(ApiContract.PREFIX+"/verification-sessions/"+id).bodyAsText())
        assertEquals(setOf("code","message","correlationId","retryable"),error.keys)
        store.close()
    }
    @Test fun `APK and Web shared DTO fields match the OpenAPI schema`() {
        val spec=JSONObjectUtils.parse(Files.readString(java.nio.file.Path.of("../docs/openapi.json")))
        val components=spec["components"] as Map<*,*>
        val schemas=components["schemas"] as Map<*,*>
        for ((name,type) in listOf("ApiError" to ApiError::class.java,"JobInput" to JobInput::class.java,
            "AssessmentInput" to AssessmentInput::class.java,"ExchangeInput" to ExchangeInput::class.java,
            "ConsentInput" to ConsentInput::class.java,"Operation" to OperationView::class.java,"Verification" to VerificationView::class.java)) {
            val schema=schemas[name] as Map<*,*>
            val properties=schema["properties"] as Map<*,*>
            assertEquals(properties.keys,type.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet(),name)
        }
    }
}
