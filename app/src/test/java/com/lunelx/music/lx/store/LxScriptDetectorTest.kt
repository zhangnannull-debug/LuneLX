package com.lunelx.music.lx.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LxScriptDetector] 的回归测试。
 *
 * 背景：0.1.0 版本用 `content.contains("lx_setup") && content.contains("lx.on")`
 * 做校验，导致混淆过的旧版脚本（如「裤佬音源」）导入失败，提示「脚本保存失败」。
 * 这组测试锁定修复后的行为。
 */
class LxScriptDetectorTest {

    @Test
    fun acceptsNewStyleScript() {
        val newStyle = """
            globalThis.lx_setup = (key, id, name, description, version, author, homepage, rawScript) => {
              const { EVENT_NAMES, request, on, send } = globalThis.lx
              on(EVENT_NAMES.request, ({ source, action, info }) => {})
            }
        """.trimIndent()
        assertTrue(LxScriptDetector.looksLikeLxScript(newStyle))
    }

    @Test
    fun acceptsLegacyStyleScript() {
        // 旧版脚本：没有 lx_setup，用 globalThis['lx'] 解构
        val legacy = """
            const {EVENT_NAMES,request,on,send}=globalThis['lx']
            on(EVENT_NAMES.request,({source,action,info})=>{})
        """.trimIndent()
        assertTrue(LxScriptDetector.looksLikeLxScript(legacy))
    }

    @Test
    fun acceptsObfuscatedScript() {
        // 真实「裤佬音源」的关键特征：混淆后仍保留 EVENT_NAMES 与 globalThis['lx']
        val obfuscated = """
            const _0x1cb06b=_0x4a54,_0x1df937=_0x33fc;(function(){})();
            const {EVENT_NAMES,request,on,send}=globalThis['lx'];
            on(EVENT_NAMES[_0x1df937(0x1a7,'TPG7')],({source,action,info})=>{});
        """.trimIndent()
        assertTrue(LxScriptDetector.looksLikeLxScript(obfuscated))
    }

    @Test
    fun acceptsDotNotationLx() {
        assertTrue(LxScriptDetector.looksLikeLxScript("const lx = globalThis.lx; lx.on('request', f)"))
    }

    @Test
    fun acceptsDirectDestructure() {
        assertTrue(LxScriptDetector.looksLikeLxScript("const {EVENT_NAMES,on} = globalThis['lx']"))
    }

    @Test
    fun rejectsBlankContent() {
        assertFalse(LxScriptDetector.looksLikeLxScript(""))
        assertFalse(LxScriptDetector.looksLikeLxScript("   \n\t "))
    }

    @Test
    fun rejectsUnrelatedJavaScript() {
        assertFalse(
            LxScriptDetector.looksLikeLxScript(
                "function hello(){ console.log('not a music source') }"
            )
        )
    }

    @Test
    fun rejectsHtmlErrorPage() {
        // 从 URL 导入时，服务器可能返回登录页/错误页而非脚本
        assertFalse(
            LxScriptDetector.looksLikeLxScript(
                "<!DOCTYPE html><html><body>404 Not Found</body></html>"
            )
        )
    }
}
