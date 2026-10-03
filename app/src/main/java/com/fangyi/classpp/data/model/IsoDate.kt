package com.fangyi.classpp.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * 无时区日期：内部为 epochDay（1970-01-01 = 0 的纯整数天数），JSON 中序列化为 `yyyy-MM-dd`。
 *
 * minSdk 24 无 java.time，用整数运算替代：周计算只是加减法，周几用公式求得。
 * 语义固定为“日历日期”本身，与设备时区/夏令时无关。
 */
@Serializable(with = IsoDateSerializer::class)
data class IsoDate(val epochDay: Long) {

    operator fun plus(days: Int): IsoDate = IsoDate(epochDay + days)

    operator fun minus(days: Int): IsoDate = IsoDate(epochDay - days)

    /** 与 [other] 的天数差（可为负） */
    operator fun minus(other: IsoDate): Long = epochDay - other.epochDay

    /** ISO 星期：1 = 周一 … 7 = 周日。1970-01-01 是周四（=4） */
    fun isoDayOfWeek(): Int = Math.floorMod(epochDay + 3, 7L).toInt() + 1

    /** 所在 ISO 周的周一（周日回退到本周一）——周数/日期带一律按日历周对齐 */
    fun mondayOfWeek(): IsoDate = this + (1 - isoDayOfWeek())

    override fun toString(): String {
        val cal = GregorianCalendar(UTC).apply { timeInMillis = epochDay * MILLIS_PER_DAY }
        return String.format(
            Locale.ROOT,
            "%04d-%02d-%02d",
            cal.get(GregorianCalendar.YEAR),
            cal.get(GregorianCalendar.MONTH) + 1,
            cal.get(GregorianCalendar.DAY_OF_MONTH),
        )
    }

    companion object {
        private const val MILLIS_PER_DAY = 86_400_000L
        private val ISO_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")
        private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

        /** 构造指定年月日的日历日期；非法日期（如 2026-02-30）抛 [IllegalArgumentException] */
        fun of(year: Int, month: Int, dayOfMonth: Int): IsoDate {
            val cal = GregorianCalendar(UTC)
            cal.isLenient = false
            cal.clear()
            cal.set(year, month - 1, dayOfMonth)
            return IsoDate(Math.floorDiv(cal.timeInMillis, MILLIS_PER_DAY))
        }

        /** 严格解析 `yyyy-MM-dd`；格式或日期非法抛 [IllegalArgumentException] */
        fun parse(text: String): IsoDate =
            parseOrNull(text) ?: throw IllegalArgumentException("Invalid ISO date: '$text'")

        /** 严格解析 `yyyy-MM-dd`；非法返回 null */
        fun parseOrNull(text: String): IsoDate? {
            if (!ISO_PATTERN.matches(text)) return null
            val parts = text.split('-')
            val year = parts[0].toIntOrNull() ?: return null
            val month = parts[1].toIntOrNull() ?: return null
            val day = parts[2].toIntOrNull() ?: return null
            return try {
                of(year, month, day)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        /** [millis] 所在的**本地时区**日历日期（“今天”语义） */
        fun today(millis: Long = System.currentTimeMillis()): IsoDate {
            val local = GregorianCalendar().apply { timeInMillis = millis }
            return of(
                local.get(GregorianCalendar.YEAR),
                local.get(GregorianCalendar.MONTH) + 1,
                local.get(GregorianCalendar.DAY_OF_MONTH),
            )
        }
    }
}

object IsoDateSerializer : KSerializer<IsoDate> {
    override val descriptor = PrimitiveSerialDescriptor("IsoDate", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: IsoDate) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): IsoDate {
        val text = decoder.decodeString()
        return IsoDate.parseOrNull(text)
            ?: throw SerializationException("Invalid ISO date: '$text'")
    }
}
