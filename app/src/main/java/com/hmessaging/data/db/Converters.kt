package com.hmessaging.data.db

import androidx.room.TypeConverter
import com.hmessaging.data.model.DeliveryStatus
import com.hmessaging.data.model.MatchTarget
import com.hmessaging.data.model.MatchType
import com.hmessaging.data.model.MessageType
import com.hmessaging.data.model.RepeatMode
import com.hmessaging.data.model.ScheduleStatus
import com.hmessaging.data.model.SourceMatch

/**
 * Enums are persisted by name rather than ordinal so that reordering a constant can never
 * silently reinterpret existing rows.
 */
class Converters {

    @TypeConverter
    fun messageTypeToString(value: MessageType): String = value.name

    @TypeConverter
    fun stringToMessageType(value: String): MessageType =
        runCatching { MessageType.valueOf(value) }.getOrDefault(MessageType.INBOX)

    @TypeConverter
    fun deliveryStatusToString(value: DeliveryStatus): String = value.name

    @TypeConverter
    fun stringToDeliveryStatus(value: String): DeliveryStatus =
        runCatching { DeliveryStatus.valueOf(value) }.getOrDefault(DeliveryStatus.NONE)

    @TypeConverter
    fun matchTypeToString(value: MatchType): String = value.name

    @TypeConverter
    fun stringToMatchType(value: String): MatchType =
        runCatching { MatchType.valueOf(value) }.getOrDefault(MatchType.EXACT)

    @TypeConverter
    fun matchTargetToString(value: MatchTarget): String = value.name

    @TypeConverter
    fun stringToMatchTarget(value: String): MatchTarget =
        runCatching { MatchTarget.valueOf(value) }.getOrDefault(MatchTarget.SENDER)

    @TypeConverter
    fun sourceMatchToString(value: SourceMatch): String = value.name

    @TypeConverter
    fun stringToSourceMatch(value: String): SourceMatch =
        runCatching { SourceMatch.valueOf(value) }.getOrDefault(SourceMatch.ALL)

    @TypeConverter
    fun repeatModeToString(value: RepeatMode): String = value.name

    @TypeConverter
    fun stringToRepeatMode(value: String): RepeatMode =
        runCatching { RepeatMode.valueOf(value) }.getOrDefault(RepeatMode.NONE)

    @TypeConverter
    fun scheduleStatusToString(value: ScheduleStatus): String = value.name

    @TypeConverter
    fun stringToScheduleStatus(value: String): ScheduleStatus =
        runCatching { ScheduleStatus.valueOf(value) }.getOrDefault(ScheduleStatus.PENDING)
}
