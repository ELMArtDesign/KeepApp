/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.core.rhythm

/**
 * Parser JSON leggero in Kotlin puro (zero dipendenze Android o librerie esterne)
 * e motore di gestione dei pattern ritmici (`RhythmPatternEngine` — D41).
 *
 * Regola chiave D41:
 * - I pattern hanno 3–4 livelli di intensità (es. 0=Defaticamento, 1=Base, 2=Spinta, 3=Rinforzo).
 * - Qualsiasi richiesta di cambio livello viene accodata e applicata **sempre sul primo battito
 *   della battuta successiva** (`stepIndex == 0`), evitando transizioni sgraziate a metà misura.
 */
class RhythmPatternEngine(
    initialPattern: RhythmPattern,
    initialLevelIndex: Int = 1
) {
    @Volatile
    var currentPattern: RhythmPattern = initialPattern
        private set

    @Volatile
    var activeLevelIndex: Int = initialPattern.levelOrClosest(initialLevelIndex).levelIndex
        private set

    @Volatile
    var pendingLevelIndex: Int? = null
        private set

    private var pendingPattern: RhythmPattern? = null

    /**
     * Richiede un nuovo livello di intensità. Se siamo sul primo step della battuta (`stepIndex == 0`),
     * viene applicato subito; altrimenti entra in vigore al prossimo downbeat (`stepIndex == 0`).
     */
    fun requestLevel(levelIndex: Int) {
        val clamped = currentPattern.levelOrClosest(levelIndex).levelIndex
        if (clamped != activeLevelIndex) {
            pendingLevelIndex = clamped
        } else {
            pendingLevelIndex = null
        }
    }

    /**
     * Richiede il cambio di pattern, applicato in modo fluido sul prossimo downbeat.
     */
    fun requestPattern(newPattern: RhythmPattern, levelIndex: Int = activeLevelIndex) {
        pendingPattern = newPattern
        pendingLevelIndex = newPattern.levelOrClosest(levelIndex).levelIndex
    }

    /**
     * Restituisce i colpi da eseguire allo `stepIndex` corrente della battuta.
     * Se `stepIndex == 0` e c'è un cambio livello o pattern in sospeso, lo applica ora.
     * Restituisce anche `true` nel secondo elemento della coppia se è avvenuto un cambio livello.
     */
    fun hitsForStep(stepIndex: Int): Pair<List<PatternHit>, Boolean> {
        var levelChanged = false
        if (stepIndex == 0) {
            val nextPat = pendingPattern
            if (nextPat != null) {
                currentPattern = nextPat
                pendingPattern = null
                levelChanged = true
            }
            val nextLvl = pendingLevelIndex
            if (nextLvl != null) {
                val resolved = currentPattern.levelOrClosest(nextLvl).levelIndex
                if (resolved != activeLevelIndex) {
                    activeLevelIndex = resolved
                    levelChanged = true
                }
                pendingLevelIndex = null
            }
        }

        val level = currentPattern.levelOrClosest(activeLevelIndex)
        val hits = level.hits.filter { it.stepIndex == stepIndex }
        return hits to levelChanged
    }

    companion object {
        /**
         * Effettua il parsing di un file JSON di pattern ritmico (`assets/patterns/`).
         */
        fun parseJson(jsonText: String): RhythmPattern {
            val root = SimpleJsonParser(jsonText).parseObject()
            val id = root["id"] as? String ?: "unknown"
            val name = root["name"] as? String ?: id
            val description = root["description"] as? String ?: ""
            val beatsPerBar = (root["beatsPerBar"] as? Number)?.toInt() ?: 4
            val subdivisionsPerBeat = (root["subdivisionsPerBeat"] as? Number)?.toInt() ?: 4

            @Suppress("UNCHECKED_CAST")
            val rawLevels = root["levels"] as? List<Map<String, Any?>> ?: emptyList()
            val levels = rawLevels.mapIndexed { idx, lvlMap ->
                val levelIdx = (lvlMap["level"] as? Number)?.toInt() ?: idx
                val lvlName = lvlMap["name"] as? String ?: "Livello $levelIdx"
                @Suppress("UNCHECKED_CAST")
                val rawHits = lvlMap["hits"] as? List<Map<String, Any?>> ?: emptyList()
                val hits = rawHits.mapNotNull { hitMap ->
                    val step = (hitMap["step"] as? Number)?.toInt() ?: return@mapNotNull null
                    val instKey = hitMap["instrument"] as? String ?: return@mapNotNull null
                    val inst = DrumInstrument.fromJsonKey(instKey) ?: return@mapNotNull null
                    val vel = ((hitMap["velocity"] as? Number)?.toFloat() ?: 0.8f).coerceIn(0.05f, 1.0f)
                    PatternHit(stepIndex = step, instrument = inst, velocity = vel)
                }
                RhythmLevel(levelIndex = levelIdx, name = lvlName, hits = hits)
            }.sortedBy { it.levelIndex }

            return RhythmPattern(
                id = id,
                name = name,
                description = description,
                beatsPerBar = beatsPerBar.coerceIn(1, 12),
                subdivisionsPerBeat = subdivisionsPerBeat.coerceIn(1, 8),
                levels = levels
            )
        }
    }
}

/**
 * Mini parser JSON ricorsivo a discesa in Kotlin puro (Apache 2.0, zero dipendenze).
 */
private class SimpleJsonParser(private val src: String) {
    private var pos = 0

    fun parseObject(): Map<String, Any?> {
        skipWs()
        expect('{')
        val map = LinkedHashMap<String, Any?>()
        skipWs()
        if (peek() == '}') {
            pos++
            return map
        }
        while (pos < src.length) {
            skipWs()
            val key = parseString()
            skipWs()
            expect(':')
            skipWs()
            val value = parseValue()
            map[key] = value
            skipWs()
            when (peek()) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return map
                }
                else -> error("Carattere inatteso nel JSON alla posizione $pos: '${peek()}'")
            }
        }
        error("Oggetto JSON non chiuso")
    }

    private fun parseArray(): List<Any?> {
        expect('[')
        val list = ArrayList<Any?>()
        skipWs()
        if (peek() == ']') {
            pos++
            return list
        }
        while (pos < src.length) {
            skipWs()
            list.add(parseValue())
            skipWs()
            when (peek()) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return list
                }
                else -> error("Carattere inatteso nell'array JSON alla posizione $pos: '${peek()}'")
            }
        }
        error("Array JSON non chiuso")
    }

    private fun parseValue(): Any? {
        skipWs()
        return when (val ch = peek()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> {
                expectLiteral("true")
                true
            }
            'f' -> {
                expectLiteral("false")
                false
            }
            'n' -> {
                expectLiteral("null")
                null
            }
            '-', in '0'..'9' -> parseNumber()
            else -> error("Valore JSON non valido alla posizione $pos: '$ch'")
        }
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (pos < src.length) {
            val c = src[pos++]
            if (c == '"') return sb.toString()
            if (c == '\\' && pos < src.length) {
                when (val esc = src[pos++]) {
                    '"', '\\', '/' -> sb.append(esc)
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        val hex = src.substring(pos, pos + 4)
                        sb.append(hex.toInt(16).toChar())
                        pos += 4
                    }
                    else -> sb.append(esc)
                }
            } else {
                sb.append(c)
            }
        }
        error("Stringa JSON non chiusa")
    }

    private fun parseNumber(): Number {
        val start = pos
        if (peek() == '-') pos++
        while (pos < src.length && src[pos] in '0'..'9') pos++
        var isFloat = false
        if (pos < src.length && src[pos] == '.') {
            isFloat = true
            pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
        }
        if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
            isFloat = true
            pos++
            if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
        }
        val numStr = src.substring(start, pos)
        return if (isFloat) numStr.toDouble() else numStr.toLong()
    }

    private fun expect(ch: Char) {
        if (pos >= src.length || src[pos] != ch) {
            error("Atteso '$ch' alla posizione $pos")
        }
        pos++
    }

    private fun expectLiteral(lit: String) {
        if (!src.startsWith(lit, pos)) error("Atteso '$lit' alla posizione $pos")
        pos += lit.length
    }

    private fun peek(): Char = if (pos < src.length) src[pos] else '\u0000'

    private fun skipWs() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }
}
