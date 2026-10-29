package io.translatetube.local.translate

/**
 * Prompt rules shared by the LLM translation engines.
 *
 * The translated lines are also read aloud by the dubbing engine. A sentence that the subtitle
 * split over several cues used to be translated cue by cue, each ending like a finished sentence,
 * so the dubbed result sounded like several short, unrelated statements.
 */
internal object TranslationPromptPolicy {
    /** Appended to the system prompt. [target] is the display name of the target language. */
    fun spokenSubtitleRules(target: String): String =
        "A sentence may be split across several consecutive lines. Translate each line as its own part of " +
            "that sentence so the translated lines read one after another form one fluent $target sentence: " +
            "do not repeat content, do not move content between lines, do not add words absent from the source, " +
            "and do not end a line with a full stop when its sentence continues on the next line. " +
            "The text will be read aloud: no brackets, stage directions, emoji or notes. "
}
