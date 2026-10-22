@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
package id.nusacred.portals

import kotlin.js.*
import kotlinx.coroutines.await

private fun text(value:JsAny,key:String):String = js("typeof value[key] === 'string' ? value[key] : ''")
private fun number(value:JsAny,key:String):Double = js("typeof value[key] === 'number' ? value[key] : 0")
private fun bool(value:JsAny,key:String):Boolean = js("value[key] === true")
private fun child(value:JsAny,key:String):JsAny = js("value[key] || {}")
private fun length(value:JsAny,key:String):Int = js("Array.isArray(value[key]) ? value[key].length : 0")
private fun element(value:JsAny,key:String,index:Int):JsAny = js("value[key][index]")
private fun stringValue(value:JsAny):String = js("String(value)")
fun quote(value:String):String = js("JSON.stringify(value)")
fun now():Double = js("Date.now() / 1000")
fun date(seconds:Double):String = js("seconds > 0 ? new Date(seconds * 1000).toLocaleDateString('en-GB', { day:'2-digit',month:'short',year:'numeric' }) : '—'")
fun navigate(value:String):Unit = js("window.location.assign(value)")
fun currentId():String = js("sessionStorage.getItem('nusacred-verification') || ''")
fun saveId(value:String):Unit = js("sessionStorage.setItem('nusacred-verification', value)")
fun newKey():String = js("crypto.randomUUID()")
private fun copyPromise(value:String):Promise<JsAny> = js("(async () => { await navigator.clipboard.writeText(value); return {}; })()")
suspend fun copyText(value:String) { copyPromise(value).await<JsAny>() }
private fun fetchJson(path:String,method:String,body:String?,csrf:String?,key:String?):Promise<JsAny> = js("""
    (async () => {
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), 25000);
        try {
            const headers = { 'Accept':'application/json' };
            if (body !== null) headers['Content-Type'] = 'application/json';
            if (csrf) headers['X-CSRF-Token'] = csrf;
            if (key) headers['Idempotency-Key'] = key;
            const response = await fetch(path, { method, headers, body:body === null ? undefined : body,
                credentials:'same-origin', redirect:'error', cache:'no-store', signal:controller.signal });
            const raw = await response.text();
            if (raw.length > 2097152) throw new Error('Response too large');
            let data = {}; try { data = raw ? JSON.parse(raw) : {}; } catch (_) {}
            return { ok:response.ok, status:response.status, data };
        } finally { clearTimeout(timer); }
    })()
""")
class Data(val raw:JsAny) {
    fun s(key:String)=text(raw,key)
    fun n(key:String)=number(raw,key)
    fun b(key:String)=bool(raw,key)
    fun obj(key:String)=Data(child(raw,key))
    fun list(key:String)=(0 until length(raw,key)).map { Data(element(raw,key,it)) }
    fun strings(key:String)=(0 until length(raw,key)).map { stringValue(element(raw,key,it)) }
}
class PortalFailure(val status:Int,val code:String,val safeMessage:String):RuntimeException()
suspend fun api(path:String,method:String="GET",body:String?=null,csrf:String?=null,key:String?=null):Data {
    require(path.startsWith("/api/v1/") || path=="/auth/logout")
    val result=Data(fetchJson(path,method,body,csrf,key).await())
    if(!result.b("ok")) throw PortalFailure(result.n("status").toInt(),result.obj("data").s("code"),result.obj("data").s("message"))
    return result.obj("data")
}
fun body(vararg pairs:Pair<String,String>)=pairs.joinToString(",","{","}") { quote(it.first)+":"+quote(it.second) }
