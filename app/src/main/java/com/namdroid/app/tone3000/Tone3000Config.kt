package com.namdroid.app.tone3000

/**
 * Datos de tu app registrada en TONE3000 (Settings -> API Keys en tone3000.com).
 *
 * PUBLISHABLE_KEY identifica esta integracion y empieza con "t3k_pub_...".
 * Es segura de incluir en el cliente (no es un secreto), a diferencia de la
 * Secret Key ("t3k_cs_...") que NUNCA debe ir en la app.
 *
 * El mismo REDIRECT_URI debe estar registrado en el panel de TONE3000 o el
 * login falla con "redirect_uri mismatch".
 */
object Tone3000Config {
    const val PUBLISHABLE_KEY = "t3k_pub_dQvs7D0v6oTuVTvuM7FFa-SFIa9BSzvc"
    const val REDIRECT_URI = "namdroid://oauth/callback"
    const val BASE_URL = "https://www.tone3000.com/api/v1"
}
