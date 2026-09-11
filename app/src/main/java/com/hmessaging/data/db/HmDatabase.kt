package com.hmessaging.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.hmessaging.data.db.dao.AutoReplyDao
import com.hmessaging.data.db.dao.BlockDao
import com.hmessaging.data.db.dao.ForwardDao
import com.hmessaging.data.db.dao.MessageDao
import com.hmessaging.data.db.dao.OtpDao
import com.hmessaging.data.db.dao.ScheduleDao
import com.hmessaging.data.db.dao.TemplateDao
import com.hmessaging.data.db.dao.ThreadDao
import com.hmessaging.data.db.entity.AutoReplyLogEntity
import com.hmessaging.data.db.entity.AutoReplyRuleEntity
import com.hmessaging.data.db.entity.BlockRuleEntity
import com.hmessaging.data.db.entity.BlockedMessageEntity
import com.hmessaging.data.db.entity.ForwardLogEntity
import com.hmessaging.data.db.entity.ForwardRuleEntity
import com.hmessaging.data.db.entity.MessageEntity
import com.hmessaging.data.db.entity.OtpEntity
import com.hmessaging.data.db.entity.ScheduledMessageEntity
import com.hmessaging.data.db.entity.TemplateEntity
import com.hmessaging.data.db.entity.ThreadEntity

@Database(
    entities = [
        ThreadEntity::class,
        MessageEntity::class,
        BlockRuleEntity::class,
        BlockedMessageEntity::class,
        ScheduledMessageEntity::class,
        AutoReplyRuleEntity::class,
        AutoReplyLogEntity::class,
        ForwardRuleEntity::class,
        ForwardLogEntity::class,
        OtpEntity::class,
        TemplateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class HmDatabase : RoomDatabase() {

    abstract fun threadDao(): ThreadDao
    abstract fun messageDao(): MessageDao
    abstract fun blockDao(): BlockDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun autoReplyDao(): AutoReplyDao
    abstract fun forwardDao(): ForwardDao
    abstract fun otpDao(): OtpDao
    abstract fun templateDao(): TemplateDao

    companion object {
        private const val NAME = "hmessaging.db"

        fun build(context: Context): HmDatabase =
            Room.databaseBuilder(context.applicationContext, HmDatabase::class.java, NAME)
                .build()
    }
}
