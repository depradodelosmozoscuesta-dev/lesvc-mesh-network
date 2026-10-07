package com.lesvc.audiocode

data class SonicUnit(
    val note: Int,
    val durationMsL: Int,
    val durationMsR: Int,
    val amplitudeL: Float,
    val amplitudeR: Float,
    val repeatsL: Int,
    val repeatsR: Int
)

object SonicDictionaryStereo {
    val symbols = mapOf(
        'A' to SonicUnit(0, 50, 55, 0.8f, 0.7f, 1, 2),
        'B' to SonicUnit(1, 50, 55, 0.8f, 0.7f, 2, 1),
        'C' to SonicUnit(2, 50, 55, 0.8f, 0.7f, 1, 1),
        'D' to SonicUnit(3, 55, 50, 0.7f, 0.8f, 1, 2),
        'E' to SonicUnit(4, 55, 50, 0.7f, 0.8f, 2, 1),
        'F' to SonicUnit(5, 55, 50, 0.7f, 0.8f, 1, 1),
        'G' to SonicUnit(6, 60, 50, 0.9f, 0.6f, 2, 2),
        'H' to SonicUnit(7, 60, 50, 0.9f, 0.6f, 1, 2),
        'I' to SonicUnit(8, 60, 50, 0.9f, 0.6f, 2, 1),
        'J' to SonicUnit(9, 50, 60, 0.7f, 0.9f, 1, 1),
        'K' to SonicUnit(10, 50, 60, 0.7f, 0.9f, 2, 2),
        'L' to SonicUnit(11, 50, 60, 0.7f, 0.9f, 1, 2),
        'M' to SonicUnit(0, 55, 55, 0.8f, 0.8f, 2, 1),
        'N' to SonicUnit(1, 55, 55, 0.8f, 0.8f, 1, 2),
        'O' to SonicUnit(2, 60, 55, 0.9f, 0.8f, 1, 1),
        'P' to SonicUnit(3, 60, 55, 0.9f, 0.8f, 2, 2),
        'Q' to SonicUnit(4, 50, 60, 0.7f, 0.9f, 1, 1),
        'R' to SonicUnit(5, 50, 60, 0.7f, 0.9f, 2, 1),
        'S' to SonicUnit(6, 55, 50, 0.8f, 0.7f, 1, 2),
        'T' to SonicUnit(7, 55, 50, 0.8f, 0.7f, 2, 2),
        'U' to SonicUnit(8, 60, 55, 0.9f, 0.8f, 1, 1),
        'V' to SonicUnit(9, 60, 55, 0.9f, 0.8f, 2, 1),
        'W' to SonicUnit(10, 50, 55, 0.7f, 0.8f, 1, 2),
        'X' to SonicUnit(11, 50, 55, 0.7f, 0.8f, 2, 2),
        'Y' to SonicUnit(0, 55, 60, 0.8f, 0.9f, 1, 1),
        'Z' to SonicUnit(1, 55, 60, 0.8f, 0.9f, 2, 1),
        ' ' to SonicUnit(2, 45, 45, 0.5f, 0.5f, 1, 1),
        '0' to SonicUnit(3, 50, 50, 0.8f, 0.8f, 1, 1),
        '1' to SonicUnit(4, 50, 50, 0.8f, 0.8f, 2, 2),
        '2' to SonicUnit(5, 50, 50, 0.8f, 0.8f, 1, 2),
        '3' to SonicUnit(6, 50, 50, 0.8f, 0.8f, 2, 1),
        '4' to SonicUnit(7, 55, 55, 0.8f, 0.8f, 1, 1),
        '5' to SonicUnit(8, 55, 55, 0.8f, 0.8f, 2, 2),
        '6' to SonicUnit(9, 55, 55, 0.8f, 0.8f, 1, 2),
        '7' to SonicUnit(10, 55, 55, 0.8f, 0.8f, 2, 1),
        '8' to SonicUnit(11, 60, 60, 0.8f, 0.8f, 1, 1),
        '9' to SonicUnit(0, 60, 60, 0.8f, 0.8f, 2, 2),
        '.' to SonicUnit(1, 40, 40, 0.6f, 0.6f, 1, 1),
        ',' to SonicUnit(2, 40, 40, 0.6f, 0.6f, 2, 1),
        '!' to SonicUnit(3, 45, 45, 0.7f, 0.7f, 1, 2),
        '?' to SonicUnit(4, 45, 45, 0.7f, 0.7f, 2, 2)
    )
}
