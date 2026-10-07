# Evaluación «Turbo» (protocolos ggwave personalizados) – 8-oct-2026

Simulación en el PC con ggwave v0.4.3 sin modificar (`tools/turbo/turbo_eval.cpp`): 30 cargas aleatorias de 140 B por celda; éxito = los 140 bytes idénticos. Solo cambian tramas por símbolo (fpt) y bytes por símbolo (más tonos); misma amplitud y marcadores. Ruido blanco a la SNR indicada; eco = respuesta al impulso de sala sintética (camino directo + cola «velvet noise»): sala RT60 0,4 s / DRR +3 dB, medio 0,6 s / 0 dB, fuerte 0,8 s / −3 dB; reloj = el emisor va 100 ppm rápido.

| Protocolo | Duración 140 B | Bytes/s | limpio | 10 dB | 10 dB + eco | 3 dB | 3 dB + eco | 0 dB + eco | 3 dB + eco fuerte | 10 dB + eco + reloj 100 ppm | 10 dB + eco medio | 10 dB + eco fuerte |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Estándar muy rápido (fpt3, 3 B/tx) | 4.97 s | 28.2 | 30/30 | 30/30 | 30/30 | 30/30 | 30/30 | 30/30 | 0/30 | 30/30 | 9/30 | 0/30 |
| Turbo A (fpt2, 3 B/tx) | 3.54 s | 39.5 | 30/30 | 30/30 | 30/30 | 30/30 | 29/30 | 30/30 | 0/30 | 30/30 | 5/30 | 0/30 |
| Turbo B (fpt3, 4 B/tx) | 3.88 s | 36.1 | 30/30 | 30/30 | 30/30 | 30/30 | 30/30 | 30/30 | 0/30 | 30/30 | 8/30 | 0/30 |
| Turbo C (fpt2, 4 B/tx) | 2.82 s | 49.7 | 30/30 | 30/30 | 30/30 | 30/30 | 30/30 | 30/30 | 0/30 | 30/30 | 4/30 | 0/30 |
| Turbo D (fpt1, 3 B/tx) | 2.11 s | 66.3 | 30/30 | 30/30 | 30/30 | 30/30 | 30/30 | 29/30 | 0/30 | 30/30 | 8/30 | 0/30 |

Conclusiones:
- En este canal simulado todos los candidatos aguantan igual que el estándar «muy rápido» hasta 0 dB con eco de sala y con 100 ppm de deriva; todos caen igual con eco medio/fuerte (el límite es la reverberación, no la velocidad).
- **Elegido: Turbo C** (2 tramas, 4 bytes por símbolo, 1,9–7,9 kHz): 49,7 B/s frente a 28,2 B/s (**×1,76**; 140 B en 2,82 s en vez de 4,97 s), 30/30 a 10 dB con eco. Se añade como «Turbo (experimental, corta distancia)».
- Turbo D (1 trama por símbolo, ×2,35) también pasa la simulación, pero con símbolos de 21 ms el eco real de altavoces y salas pequeñas pesa más de lo que modela la simulación; no se ofrece hasta probarlo en móviles reales.
- Aviso: la simulación no modela la respuesta en frecuencia del altavoz/micro, el control automático de ganancia ni el ruido real (voces): hay que probar Turbo en móviles antes de fiarse.
