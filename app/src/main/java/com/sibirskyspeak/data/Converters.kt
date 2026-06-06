package com.sibirskyspeak.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun cardTypeToString(value: CardType): String = value.name

    @TypeConverter
    fun stringToCardType(value: String): CardType = CardType.valueOf(value)

    @TypeConverter
    fun queueToString(value: Queue): String = value.name

    @TypeConverter
    fun stringToQueue(value: String): Queue = Queue.valueOf(value)

    @TypeConverter
    fun cardStateToString(value: CardState): String = value.name

    @TypeConverter
    fun stringToCardState(value: String): CardState = CardState.valueOf(value)

    @TypeConverter
    fun ratingToString(value: Rating): String = value.name

    @TypeConverter
    fun stringToRating(value: String): Rating = Rating.valueOf(value)

    @TypeConverter
    fun reviewSourceToString(value: ReviewSource): String = value.name

    @TypeConverter
    fun stringToReviewSource(value: String): ReviewSource = ReviewSource.valueOf(value)

    @TypeConverter
    fun wordStatusToString(value: WordStatus): String = value.name

    @TypeConverter
    fun stringToWordStatus(value: String): WordStatus = WordStatus.valueOf(value)
}
