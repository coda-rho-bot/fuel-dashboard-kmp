package com.angussoftware.fueldashboard.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Parsing a stored credential setting.
 *
 * The governing rule is that a pasted key must keep working. Misreading a real
 * credential as a malformed reference takes a working provider offline, so
 * anything that is not an unambiguous scheme stays a literal.
 */
class SecretRefTest {

    @Test
    fun aPastedKeyIsALiteralAndIsUnchanged() {
        val key = "sk-ant-api03-abc123"
        assertIs<SecretRef.Literal>(SecretRef.parse(key)).let {
            assertEquals(key, it.value)
        }
        assertFalse(SecretRef.isReference(key))
    }

    @Test
    fun anEmptySettingIsALiteralNotAReference() {
        assertIs<SecretRef.Literal>(SecretRef.parse(""))
        assertFalse(SecretRef.isReference(""))
    }

    @Test
    fun recognisedSchemesParse() {
        assertEquals("ZAI_KEY", assertIs<SecretRef.Environment>(SecretRef.parse("env:ZAI_KEY")).name)
        assertEquals("/run/secrets/k", assertIs<SecretRef.FileContents>(SecretRef.parse("file:/run/secrets/k")).path)
        assertEquals("my-key-helper", assertIs<SecretRef.Command>(SecretRef.parse("cmd:my-key-helper")).command)
    }

    @Test
    fun schemesTolerateSurroundingWhitespace() {
        assertEquals("ZAI_KEY", assertIs<SecretRef.Environment>(SecretRef.parse("env:  ZAI_KEY  ")).name)
    }

    @Test
    fun commandArgumentsAreKept() {
        val ref = assertIs<SecretRef.Command>(SecretRef.parse("cmd:vault read -field=key secret/zai"))
        assertEquals("vault read -field=key secret/zai", ref.command)
    }

    @Test
    fun anEmptySchemeBodyStaysLiteral() {
        // "env:" alone is not a usable reference. Treating it as one would
        // resolve to null and take the provider down; as a literal it is at
        // worst a bad key, which is the behaviour before this existed.
        assertIs<SecretRef.Literal>(SecretRef.parse("env:"))
        assertIs<SecretRef.Literal>(SecretRef.parse("cmd:   "))
    }

    @Test
    fun aKeyThatMerelyContainsAColonStaysLiteral() {
        // Real credentials contain colons. Only a leading, recognised scheme
        // indirects — never a colon anywhere in the string.
        for (key in listOf("abc:def", "Bearer:xyz", "https://example.com/key", "ENV:SHOUTING")) {
            assertIs<SecretRef.Literal>(SecretRef.parse(key), "'$key' must stay literal")
            assertFalse(SecretRef.isReference(key))
        }
    }

    @Test
    fun describeMasksLiteralsAndShowsReferences() {
        // A reference names a location, not a secret, so showing it is the
        // point. A literal must never be echoed.
        assertEquals("••••••••", SecretRef.describe("sk-ant-api03-abc123"))
        assertEquals("", SecretRef.describe(""))
        assertEquals("env:ZAI_KEY", SecretRef.describe("env:ZAI_KEY"))
        assertEquals("cmd:my-key-helper", SecretRef.describe("cmd:my-key-helper"))
        assertEquals("file:/run/secrets/k", SecretRef.describe("file:/run/secrets/k"))
    }

    @Test
    fun describeNeverLeaksAnyPartOfALiteral() {
        val key = "sk-ant-super-secret-value"
        val shown = SecretRef.describe(key)
        assertFalse(shown.contains("sk-ant"))
        assertFalse(shown.contains("secret"))
        assertTrue(shown.all { it == '•' })
    }

    @Test
    fun isReferenceAgreesWithParse() {
        for (s in listOf("env:A", "file:/x", "cmd:y")) assertTrue(SecretRef.isReference(s), s)
        for (s in listOf("", "plain", "env:", "a:b")) assertFalse(SecretRef.isReference(s), s)
    }
}
