package io.github.lemcoder.koinference.litertlm

import io.github.lemcoder.koinference.runtime.ModelRuntime
import io.github.lemcoder.koinference.runtime.text.TextModelRuntime

/**
 * What a loaded LiteRT-LM model can do.
 *
 * One member of its own. The parameter and settings members that used to be declared here are on
 * [ModelRuntime], where they were identical to llama.cpp's.
 *
 * Text only, and no sealed parent to hold it apart from anything else: LiteRT-LM's C API exposes
 * tokenize, detokenize and scoring, but nothing that returns an embedding vector, so there is only
 * ever one kind of runtime here. An embedding backend would go through LiteRT core instead.
 */
interface LiteRtLmTextRuntime : TextModelRuntime {

    /**
     * Forget the conversation so far and start the next turn from the system prompt.
     *
     * Here rather than on [ModelRuntime] because it has no llama.cpp counterpart: that engine
     * carries no conversation to forget, and its session rebuild is a side effect of changing a
     * parameter rather than something a caller can ask for.
     *
     * The weights stay loaded, so this is the cheap way to begin a new chat — and the only way,
     * short of changing a parameter for the side effect of dropping the history.
     */
    suspend fun resetConversation()
}
