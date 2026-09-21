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
import com.hmessaging.data.db.entity.BankRuleEntity
import com.hmessaging.data.db.entity.BankTxEntity
import com.hmessaging.data.db.entity.BlockRuleEntity
import com.hmessaging.data.db.entity.BlockedMessageEntity
import com.hmessaging.data.db.entity.DiagEventEntity
import com.hmessaging.data.db.entity.ForwardLogEntity
import com.hmessaging.data.db.entity.ForwardRuleEntity
import com.hmessaging.data.db.entity.MessageEntity
import com.hmessaging.data.db.entity.OtpEntity
import com.hmessaging.data.db.entity.OtpVetoEntity
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
        OtpVetoEntity::class,
        TemplateEntity::class,
        DiagEventEntity::class,
        BankTxEntity::class,
        BankRuleEntity::class,
    ],
    version = 7,
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

        /**
         * Adds the taught bank formats, and throws away everything the guessing parser filed.
         *
         * Those rows were wrong — a phone bill read as a withdrawal, a phone number read as an
         * amount — and there is no sorting the few right ones from the rest, so none of them are
         * kept. The ledger starts empty and fills as formats are taught.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bank_rules` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`senderKey` TEXT NOT NULL, " +
                        "`senderLabel` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`amountAnchor` TEXT NOT NULL, " +
                        "`balanceAnchor` TEXT, " +
                        "`accountAnchor` TEXT, " +
                        "`accountLiteral` TEXT, " +
                        "`currency` TEXT NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`hitCount` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`sampleBody` TEXT NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bank_rules_senderKey` ON `bank_rules` (`senderKey`)")
                db.execSQL("ALTER TABLE `bank_tx` ADD COLUMN `ruleId` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("DELETE FROM `bank_tx`")
            }
        }

        /**
         * Adds the kinds of message the reader has ruled out as verification codes.
         *
         * Purely additive: nothing already stored changes, and a phone with no rules behaves
         * exactly as before until the first one is taught.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `otp_vetoes` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`senderKey` TEXT NOT NULL, " +
                        "`senderLabel` TEXT NOT NULL, " +
                        "`shape` TEXT NOT NULL, " +
                        "`sample` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_otp_vetoes_senderKey_shape` " +
                        "ON `otp_vetoes` (`senderKey`, `shape`)",
                )
            }
        }

        /**
         * Remembers which SIM a conversation's newest message used.
         *
         * Backfilled from the messages already stored rather than left at "unknown", so the list
         * says something useful about the history too and not only about what arrives next.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `threads` ADD COLUMN `lastSubscriptionId` INTEGER NOT NULL DEFAULT -1")
                db.execSQL(
                    "UPDATE threads SET lastSubscriptionId = COALESCE((" +
                        "SELECT subscriptionId FROM messages WHERE messages.threadId = threads.id " +
                        "ORDER BY date DESC, id DESC LIMIT 1), -1)",
                )
            }
        }

        fun build(context: Context): HmDatabase =
            Room.databaseBuilder(context.applicationContext, HmDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .build()
    }
}
