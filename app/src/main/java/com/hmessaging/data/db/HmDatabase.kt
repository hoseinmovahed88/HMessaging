package com.hmessaging.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hmessaging.data.db.dao.AutoReplyDao
import com.hmessaging.data.db.dao.BankDao
import com.hmessaging.data.db.dao.BlockDao
import com.hmessaging.data.db.dao.DiagDao
import com.hmessaging.data.db.dao.ForwardDao
import com.hmessaging.data.db.dao.MessageDao
import com.hmessaging.data.db.dao.OtpDao
import com.hmessaging.data.db.dao.ScheduleDao
import com.hmessaging.data.db.dao.TemplateDao
import com.hmessaging.data.db.dao.ThreadDao
import com.hmessaging.data.db.entity.AutoReplyLogEntity
import com.hmessaging.data.db.entity.AutoReplyRuleEntity
import com.hmessaging.data.db.entity.BankTxEntity
import com.hmessaging.data.db.entity.BlockRuleEntity
import com.hmessaging.data.db.entity.BlockedMessageEntity
import com.hmessaging.data.db.entity.DiagEventEntity
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
        DiagEventEntity::class,
        BankTxEntity::class,
    ],
    version = 4,
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
    abstract fun diagDao(): DiagDao
    abstract fun bankDao(): BankDao

    companion object {
        private const val NAME = "hmessaging.db"

        /** Adds the diagnostics log. Nothing else changes, so existing messages are preserved. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `diag_events` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`at` INTEGER NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`detail` TEXT NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_diag_events_at` ON `diag_events` (`at`)")
            }
        }

        /**
         * Adds the conversation match key. Left empty here and backfilled in Kotlin, because
         * deriving it means stripping non-digits and taking a significant tail, which SQLite
         * cannot express without a pile of nested replaces.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `threads` ADD COLUMN `matchKey` TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_threads_matchKey` ON `threads` (`matchKey`)")
            }
        }

        /** Adds the bank ledger. Filled by parsing messages already stored, so nothing is lost. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bank_tx` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`messageId` INTEGER NOT NULL, " +
                        "`threadId` INTEGER NOT NULL, " +
                        "`address` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`amount` INTEGER NOT NULL, " +
                        "`currency` TEXT NOT NULL, " +
                        "`accountKey` TEXT NOT NULL, " +
                        "`accountLabel` TEXT, " +
                        "`balance` INTEGER, " +
                        "`at` INTEGER NOT NULL, " +
                        "`body` TEXT NOT NULL)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bank_tx_messageId` ON `bank_tx` (`messageId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bank_tx_at` ON `bank_tx` (`at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bank_tx_accountKey` ON `bank_tx` (`accountKey`)")
            }
        }

        fun build(context: Context): HmDatabase =
            Room.databaseBuilder(context.applicationContext, HmDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
