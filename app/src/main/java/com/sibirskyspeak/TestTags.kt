package com.sibirskyspeak

/**
 * Stable identifiers for UI automation (uiautomator/Espresso/Compose UI tests). Text
 * labels change with copy edits and can be ambiguous (e.g. two nodes reading "Study");
 * these tags don't. Add one here whenever a control is worth driving from a test or a
 * QA script rather than only tapping it by hand.
 */
internal object TestTags {
    // Bottom navigation
    const val NAV_PRACTICE = "nav_practice"
    const val NAV_PROGRESS = "nav_progress"
    const val NAV_LAB = "nav_lab"
    const val NAV_SETTINGS = "nav_settings"

    // Dashboard / Practice landing
    const val DASHBOARD_STUDY_BUTTON = "dashboard_study_button"
    const val DOCTRINE_NUDGE_APPLY = "doctrine_nudge_apply"
    const val DOCTRINE_NUDGE_DISMISS = "doctrine_nudge_dismiss"

    // Study session header
    const val SESSION_MORE_MENU = "session_more_menu"
    const val SESSION_UNDO = "session_undo"
    const val SESSION_EXIT = "session_exit"

    // Study session controls
    const val LESSON_GOT_IT = "lesson_got_it"
    const val ANSWER_INPUT_FIELD = "answer_input_field"
    const val ANSWER_SHOW = "answer_show"
    const val RATE_AGAIN = "rate_again"
    const val RATE_HARD = "rate_hard"
    const val RATE_GOOD = "rate_good"
    const val RATE_EASY = "rate_easy"
    const val CORRECTION_CHECK = "correction_check"
    const val CORRECTION_NEXT_CARD = "correction_next_card"

    // Lab: monthly checkpoint (P6.4)
    const val CHECKPOINT_START = "checkpoint_start"
    const val CHECKPOINT_INPUT = "checkpoint_input"
    const val CHECKPOINT_SUBMIT = "checkpoint_submit"
    const val CHECKPOINT_DISMISS = "checkpoint_dismiss"
}
