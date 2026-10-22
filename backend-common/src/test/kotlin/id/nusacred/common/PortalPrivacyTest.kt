package id.nusacred.common

import com.google.zxing.BinaryBitmap
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.qrcode.QRCodeReader
import id.nusacred.contracts.Role
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class PortalPrivacyTest {
    private fun p(org:String,role:Role)=Principal("operator",org,setOf(role),"session")
    private fun key()=UUID.randomUUID().toString()
    @Test fun `assessment list is scoped and contains the latest offer status`() {
        DomainStore(Files.createTempFile("portal-issuer-",".sqlite"),"issuer").use { store ->
            val a=p("issuer-a",Role.ISSUER_OPERATOR);val b=p("issuer-b",Role.ISSUER_ADMIN)
            val id=store.assessment(a,"recipient","candidate","CODE","competent",key())["id"] as String
            store.assessment(b,"other-recipient","other-candidate","CODE","competent",key())
            val offer=store.createOffer(a,id,key())
            val rows=store.assessments(a)["items"] as List<*>
            assertEquals(1,rows.size);val row=rows.single() as Map<*,*>
            assertEquals("candidate",row["candidateId"]);assertEquals(offer["id"],row["offerId"])
            assertEquals("PENDING",row["offerState"])
            assertEquals(403,assertFailsWith<ApiFailure> { store.assessments(p("issuer-a",Role.CANDIDATE)) }.status)
        }
    }
    @Test fun `public jobs contain no applicant or result records and private list requires company role`() {
        DomainStore(Files.createTempFile("portal-company-",".sqlite"),"company").use { store ->
            val a=p("company-a",Role.COMPANY_ADMIN);val b=p("company-b",Role.COMPANY_ADMIN)
            store.saveJob(a,"role-a","Electrical technician","CODE","competent",key())
            store.saveJob(b,"role-b","Other role","CODE","competent",key())
            val verification=store.verification(a,"role-a",key())
            val jobs=store.jobs()["items"] as List<*>
            assertEquals(2,jobs.size)
            jobs.forEach { assertEquals(setOf("id","title","competencyCode","assessmentResult","revision"),(it as Map<*,*>).keys) }
            assertEquals(listOf(verification["id"]),(store.results(a)["items"] as List<*>).map { (it as Map<*,*>)["id"] })
            assertTrue((store.results(b)["items"] as List<*>).isEmpty())
            assertEquals(403,assertFailsWith<ApiFailure> { store.results(p("company-a",Role.CANDIDATE)) }.status)
        }
    }
    @Test fun `offer QR decodes to the exact encoded wallet link`() {
        val value=walletLink("nusacred-offer:synthetic+reference/value=",true)
        val rows=qrResponse(value)["modules"] as List<*>
        val size=(rows.size+8)*8
        val pixels=IntArray(size*size) { index ->
            val x=index%size/8-4;val y=index/size/8-4
            if(y in rows.indices && x in rows.indices && (rows[y] as String)[x]=='1') 0xff000000.toInt() else 0xffffffff.toInt()
        }
        val decoded=QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size,size,pixels))))
        assertEquals(value,decoded.text)
        assertTrue(value.startsWith("https://dompet-identitas.demo.inoerawan.id/receive?offer="))
    }
}
