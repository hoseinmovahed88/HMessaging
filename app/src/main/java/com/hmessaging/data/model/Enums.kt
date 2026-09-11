package com.hmessaging.data.model

/** Where a message sits in its life cycle. Mirrors Telephony.Sms message boxes. */
enum class MessageType {
    INBOX,
    SENT,
    OUTBOX,
    FAILED,
    DRAFT,
    ;

    val isIncoming: Boolean get() = this == INBOX
}

enum class DeliveryStatus {
    NONE,
    PENDING,
    SENT,
    DELIVERED,
    FAILED,
}

/** How a rule's [pattern] is compared against its target. */
enum class MatchType {
    EXACT,
    STARTS_WITH,
    ENDS_WITH,
    CONTAINS,
    REGEX,
}

/** Which part of an incoming message a block rule inspects. */
enum class MatchTarget {
    SENDER,
    BODY,
}

/** Selects which incoming messages an auto-reply or forwarding rule applies to. */
enum class SourceMatch {
    ALL,
    CONTACTS_ONLY,
    NON_CONTACTS_ONLY,
    SENDER_PATTERN,
    BODY_KEYWORD,
}

enum class RepeatMode {
    NONE,
    HOURLY,
    DAILY,
    WEEKLY,
    MONTHLY,
}

enum class ScheduleStatus {
    PENDING,
    SENT,
    FAILED,
    CANCELLED,
}

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}
