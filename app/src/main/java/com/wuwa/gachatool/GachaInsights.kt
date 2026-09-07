package com.wuwa.gachatool

data class CharacterAcquisitionRecord(
    val record: GachaRecord,
    val pity: Int,
    val acquisitionIndex: Int,
    val isLowerBound: Boolean,
    val isOffRate: Boolean,
)

data class CharacterAcquisitionInsight(
    val pool: String,
    val poolName: String,
    val resourceId: Long,
    val name: String,
    val targetCount: Int,
    val offRateCount: Int,
    val totalFiveStars: Int,
    val totalPulls: Int,
    val isLowerBound: Boolean,
    val hasOffRate: Boolean,
    val records: List<CharacterAcquisitionRecord>,
) {
    val averagePulls: Double? get() = totalPulls.takeIf { targetCount > 0 }?.toDouble()?.div(targetCount)
}

private val standardCharacterIds = setOf(1104L, 1203L, 1301L, 1405L, 1503L)
private val limitedCharacterPools = setOf("1", "8", "10", "12")

fun characterAcquisitionInsights(records: List<GachaRecord>): List<CharacterAcquisitionInsight> {
    val byPool = records.groupBy { it.pool }
    return byPool.flatMap { (pool, source) ->
        val chronological = source.sortedWith(compareBy<GachaRecord> { it.time }.thenBy { it.orderInTimestamp }.thenBy { it.id })
        val limitedCharacter = pool in limitedCharacterPools
        val entries = linkedMapOf<Long, MutableInsight>()
        val pendingOffRates = mutableListOf<CharacterAcquisitionRecord>()
        var pity = 0
        var seenFiveStar = false
        chronological.forEach { record ->
            pity += 1
            if (record.quality != 5) return@forEach
            val lowerBound = !seenFiveStar
            seenFiveStar = true
            val isOffRate = limitedCharacter && record.type == "role" && record.resourceId in standardCharacterIds
            val item = CharacterAcquisitionRecord(record, pity, 0, lowerBound, isOffRate)
            if (isOffRate) {
                pendingOffRates += item
                pity = 0
                return@forEach
            }
            val entry = entries.getOrPut(record.resourceId) { MutableInsight(record, limitedCharacter && record.type == "role") }
            val acquisitionIndex = entry.targetCount + 1
            pendingOffRates.forEach { off -> entry.records += off.copy(acquisitionIndex = acquisitionIndex) }
            entry.offRateCount += pendingOffRates.size
            entry.totalFiveStars += pendingOffRates.size
            entry.totalPulls += pendingOffRates.sumOf { it.pity }
            entry.isLowerBound = entry.isLowerBound || pendingOffRates.any { it.isLowerBound }
            pendingOffRates.clear()
            entry.targetCount += 1
            entry.totalFiveStars += 1
            entry.totalPulls += pity
            entry.isLowerBound = entry.isLowerBound || lowerBound
            entry.records += item.copy(acquisitionIndex = acquisitionIndex)
            pity = 0
        }
        entries.values.map { it.toInsight(pool) }
    }.sortedWith(compareBy<CharacterAcquisitionInsight> { it.pool.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.name })
}

private class MutableInsight(first: GachaRecord, val hasOffRate: Boolean) {
    val resourceId = first.resourceId
    val name = first.name
    val poolName = first.poolName.ifBlank { ImportParser.poolName(first.pool) }
    var targetCount = 0
    var offRateCount = 0
    var totalFiveStars = 0
    var totalPulls = 0
    var isLowerBound = false
    val records = mutableListOf<CharacterAcquisitionRecord>()

    fun toInsight(pool: String) = CharacterAcquisitionInsight(
        pool = pool,
        poolName = poolName,
        resourceId = resourceId,
        name = name,
        targetCount = targetCount,
        offRateCount = offRateCount,
        totalFiveStars = totalFiveStars,
        totalPulls = totalPulls,
        isLowerBound = isLowerBound,
        hasOffRate = hasOffRate,
        records = records.toList(),
    )
}
