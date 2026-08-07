package com.sibirskyspeak

/**
 * Stable identifiers for UI automation (uiautomator/Espresso/Compose UI tests). Text
 * labels change with copy edits and can be ambiguous (e.g. two nodes reading "Study");
 * these tags don't. Add one here whenever a control is worth driving from a test or a
 * QA script rather than only tapping it by hand.
 */
internal object TestTags {
    const val ONBOARDING_BEGINNER = "onboarding_beginner"
    const val ONBOARDING_PLACEMENT = "onboarding_placement"
    // Bottom navigation
    const val NAV_PRACTICE = "nav_practice"
    const val NAV_READER = "nav_reader"
    const val NAV_PROGRESS = "nav_progress"
    const val NAV_LAB = "nav_lab"
    const val NAV_SETTINGS = "nav_settings"

    // Primary communicative tutor
    const val TUTOR_ONBOARDING_START = "tutor_onboarding_start"
    const val TUTOR_CONTINUE = "tutor_continue"
    const val TUTOR_OPEN_TOOLS = "tutor_open_tools"
    const val TUTOR_LISTEN = "tutor_listen"
    const val TUTOR_CHOICE_PREFIX = "tutor_choice"
    const val TUTOR_CHECK = "tutor_check"
    const val TUTOR_NEXT = "tutor_next"
    const val TUTOR_SPEECH_FALLBACK = "tutor_speech_fallback"
    const val ANSWER_TILE_PREFIX = "answer_tile"
    const val ANSWER_TILE_ASSEMBLED = "answer_tile_assembled"
    const val ANSWER_TILE_CLEAR = "answer_tile_clear"
    const val TOOLS_RETURN_TUTOR = "tools_return_tutor"
    const val TOOLS_CONTINUE_EPISODES = "tools_continue_episodes"

    // Dashboard / Practice landing
    const val DASHBOARD_STUDY_BUTTON = "dashboard_study_button"
    const val DASHBOARD_NEXT_ACTION_BUTTON = "dashboard_next_action_button"
    const val DASHBOARD_ADJUST_TODAY = "dashboard_adjust_today"
    const val SETTINGS_AUTOMATIC_PUBLIC_BACKUP = "settings_automatic_public_backup"

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

    // Study route: assigned end-of-unit capstone
    const val EXIT_TICKET_DISMISS = "exit_ticket_dismiss"
    const val EXIT_TICKET_CHOICE_PREFIX = "exit_ticket_choice"
    const val EXIT_TICKET_LISTEN = "exit_ticket_listen"
    const val EXIT_TICKET_CONTINUE = "exit_ticket_continue"
    const val EXIT_TICKET_CLOSE = "exit_ticket_close"
    const val DISMISS_MIGRATION_REPORT = "dismiss_migration_report"
}
