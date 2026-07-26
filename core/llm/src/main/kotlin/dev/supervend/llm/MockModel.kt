package dev.supervend.llm

/**
 * A deterministic, zero-network [ModelPort] (software spec §13). Used everywhere in CI — golden
 * runs, qualification threshold tests, baseline determinism — so the whole suite passes with no
 * OpenRouter access. The responder is a pure function of the request, which keeps runs
 * bit-reproducible.
 *
 * A [MockModel] never reaches the network; it is the substitutability proof (personas Liskov)
 * that a script can occupy any seat a model can.
 */
class MockModel(
    private val id: String = "mock",
    private val responder: (CompletionRequest) -> CompletionResult,
) : ModelPort {

    override suspend fun complete(request: CompletionRequest): CompletionResult = responder(request)

    companion object {
        /** Always returns the same fixed text — the simplest possible stand-in. */
        fun constant(text: String, tokensOut: Int = 0): MockModel =
            MockModel(responder = { CompletionResult.Completed(text, usage(it, tokensOut)) })

        /** Maps a rendered prompt to a canned response; unknown prompts yield [ModelFailure.EMPTY]. */
        fun scripted(table: Map<String, String>): MockModel = MockModel(responder = { req ->
            table[req.prompt]?.let { CompletionResult.Completed(it, usage(req, it.length)) }
                ?: CompletionResult.Unusable(ModelFailure.EMPTY)
        })

        internal fun usage(req: CompletionRequest, tokensOut: Int): Usage =
            Usage(tokensIn = req.prompt.length / 4, tokensOut = tokensOut, latencyMs = 0, httpStatus = 200)
    }
}
