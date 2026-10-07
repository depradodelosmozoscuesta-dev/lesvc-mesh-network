# Código antiguo (fuera de la compilación)

Primer intento de codificador/decodificador "Sonic Stereo" (12 notas, FFT de 256
puntos). Se sustituyó por ggwave en la versión 0.2 porque no decodificaba ni
siquiera en bucle digital perfecto (detección de nota siempre en la nota 0,
desalineación de tramas, campo `repeats` sin codificar, canal R decodificado con
parámetros del L, silencio convertido en caracteres).

Se conserva solo como referencia; Gradle no compila esta carpeta.
