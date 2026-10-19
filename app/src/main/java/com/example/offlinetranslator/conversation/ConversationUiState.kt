package com.example.offlinetranslator.conversation

/** Preparation state shared by the pure screen and its runtime owner. */
enum class ConversationPreparation { CHECKING, IDLE, INSTALLING, TRANSLATION, ERROR }

enum class ConversationLanguageChangeError { SAVE, OPEN, TEARDOWN }
