package com.lesvc.mesh.audio

object TextosPrueba {
    const val hola = "HOLA"
    const val pregunta = "¿Qué tal, señor Ñúñez?"
    /** Exactamente 300 caracteres, con tildes, eñes y signos de apertura. */
    val largo300: String = run {
        val base = "¡Atención, Radio Valdeprado! Mañana a las 9:30 revisamos la antena del cerro; " +
            "traed cable coaxial, conectores N y el analizador. ¿Quién se encarga del café? " +
            "Señal de prueba: ÁÉÍÓÚ áéíóú Üü Ññ ¿¡ 0123456789. "
        val sb = StringBuilder()
        while (sb.length < 300) sb.append(base)
        sb.substring(0, 300)
    }
}
