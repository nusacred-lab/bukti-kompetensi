package id.nusacred.integration

import com.nimbusds.jose.util.JSONObjectUtils
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Validates the JSON Schema keywords used by the exercised response contracts. */
internal object ResponseSchema {
    private val spec = JSONObjectUtils.parse(Files.readString(Path.of("../docs/openapi.json")))
    fun response(path: String, method: String, status: Int, value: Any?) {
        val paths = spec["paths"] as Map<*, *>
        val operation = (paths[path] as Map<*, *>)[method] as Map<*, *>
        val responses = operation["responses"] as Map<*, *>
        val content = (responses[status.toString()] as Map<*, *>)["content"] as Map<*, *>
        validate((content["application/json"] as Map<*, *>)["schema"] as Map<*, *>, value)
    }
    private fun validate(schema: Map<*, *>, value: Any?) {
        val ref = schema["\$ref"] as? String
        if(ref != null) {
            var resolved: Any? = spec
            for(segment in ref.removePrefix("#/").split('/')) resolved = (resolved as Map<*, *>)[segment]
            validate(resolved as Map<*, *>, value)
            return
        }
        schema["type"]?.let { type ->
            val types = if(type is List<*>) type else listOf(type)
            assertTrue(types.any { when(it) {
                "object" -> value is Map<*, *>
                "array" -> value is List<*>
                "string" -> value is String
                "integer" -> value is Number && value.toDouble() % 1.0 == 0.0
                "boolean" -> value is Boolean
                "null" -> value == null
                else -> error("Unsupported contract type: $it")
            } }, "Expected $type, received $value")
        }
        if(schema.containsKey("const")) assertEquals(schema["const"], value)
        (schema["enum"] as? List<*>)?.let { assertTrue(value in it, "Value outside enum: $value") }
        if(value is Map<*, *>) {
            val properties = schema["properties"] as? Map<*, *> ?: emptyMap<Any, Any>()
            (schema["required"] as? List<*>)?.forEach { assertTrue(value.containsKey(it), "Missing required property: $it") }
            if(schema["additionalProperties"] == false) assertTrue(properties.keys.containsAll(value.keys), "Unexpected properties: ${value.keys - properties.keys}")
            value.forEach { (key, child) -> (properties[key] as? Map<*, *>)?.let { validate(it, child) } }
        }
        if(value is String) {
            (schema["minLength"] as? Number)?.let { assertTrue(value.length >= it.toInt()) }
            (schema["maxLength"] as? Number)?.let { assertTrue(value.length <= it.toInt()) }
        }
        if(value is List<*>) {
            (schema["items"] as? Map<*, *>)?.let { item -> value.forEach { validate(item, it) } }
        }
    }
}
