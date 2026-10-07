package com.lesvc.mesh.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TroceadorTest {
    @Test fun corto_va_sin_cabecera() {
        val c = Troceador.cargas("¿Qué tal, señor Ñúñez?")
        assertEquals(1, c.size)
        assertEquals("¿Qué tal, señor Ñúñez?", String(c[0], Charsets.UTF_8))
    }

    @Test fun exactamente_140_bytes_cabe() {
        val t = "ñ".repeat(70) // 140 bytes
        assertEquals(140, Troceador.bytesUtf8(t))
        assertEquals(1, Troceador.cargas(t).size)
        assertEquals(2, Troceador.cargas(t + "a").size)
    }

    @Test fun largo_se_divide_sin_romper_utf8_y_se_reune_en_cualquier_orden() {
        val t = TextosPrueba.largo300
        val c = Troceador.cargas(t, "k7")
        assertTrue(c.all { it.size <= 140 })
        c.forEach { String(it, 5, it.size - 5, Charsets.UTF_8).also { s -> assertTrue(!s.contains('\uFFFD')) } }
        val r = Reensamblador()
        val orden = c.indices.reversed()
        var fin: Reensamblador.Resultado? = null
        for (i in orden) fin = r.recibir(c[i])
        assertEquals(t, (fin as Reensamblador.Resultado.Completo).texto)
    }

    @Test fun demasiado_largo_se_rechaza() {
        val t = "a".repeat(135 * 36 + 1)
        try { Troceador.cargas(t); throw AssertionError("debió fallar") } catch (e: IllegalArgumentException) {}
    }
}
