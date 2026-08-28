package fr.kristenjestin.mue.data.remote.sync

import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The SSE frame parser, over a scripted source of lines.
 *
 * What is under test is not "does it read a socket" but the two things that decide whether the
 * live channel is correct: **what counts as a hint**, and **what is discarded**. A parser that
 * turned a heartbeat into a hint would make the phone pull every twenty seconds for as long as the
 * screen is on; one that turned an unknown future event into a hint would do the same against a
 * newer server; and one that ever looked at `data:` would give the client a position, which PRD
 * 12.3 does not allow it to have.
 */
class SyncEventFramesTest {

    /**
     * Splits exactly as a line reader over a socket does: a terminator ends the line it belongs
     * to and does not begin another. `"a\n\n"` is the line `a` and one blank line, and the blank
     * line is the dispatch point — getting this wrong in the helper would hide the very bug the
     * parser's `pending` flag exists to prevent.
     */
    private suspend fun hintsFrom(stream: String): Int {
        val split = stream.split("\n")
        val lines = ArrayDeque(if (split.lastOrNull() == "") split.dropLast(1) else split)
        var hints = 0
        KtorSyncEventStream.read({ lines.removeFirstOrNull() }) { hints++ }
        return hints
    }

    @Test
    fun theGreetingAndEachChangeAreHints() = runTest {
        assertEquals(1, hintsFrom("event: hello\ndata: {}\n\n"))
        assertEquals(
            3,
            hintsFrom(
                "event: hello\ndata: {}\n\n" +
                    "event: change\ndata: {}\n\n" +
                    "event: change\ndata: {}\n\n",
            ),
        )
    }

    @Test
    fun aHeartbeatCommentIsNotAHint() = runTest {
        assertEquals(
            1,
            hintsFrom(
                "event: hello\ndata: {}\n\n" +
                    ": heartbeat\n\n" +
                    ": heartbeat\n\n" +
                    ": heartbeat\n\n",
            ),
            "a comment proves the connection is alive and means nothing changed",
        )
    }

    @Test
    fun anEventThisBuildDoesNotKnowIsIgnoredRatherThanGuessed() = runTest {
        assertEquals(
            1,
            hintsFrom(
                "event: change\ndata: {}\n\n" +
                    "event: something-a-later-server-invented\ndata: {\"x\":1}\n\n",
            ),
            "an unrecognised event that pulled would make an old build loop against a new server",
        )
    }

    /**
     * The invariant the whole design rests on. Whatever a server puts in `data:`, `id:` or
     * `retry:`, the number of pulls is decided by the event names alone — so there is no branch in
     * which the content of a frame could become a cursor or a position.
     */
    @Test
    fun theContentOfAFrameChangesNothingAboutWhatHappens() = runTest {
        val bare = hintsFrom("event: change\ndata: {}\n\n")
        val loaded = hintsFrom(
            "event: change\nid: 41\nretry: 250\ndata: {\"sequence\":\"9999\"}\ndata: {\"more\":1}\n\n",
        )
        assertEquals(bare, loaded, "a hint is one word, and the word is `pull`")
    }

    @Test
    fun anEventIsDispatchedOnTheBlankLineAndNotBefore() = runTest {
        assertEquals(
            0,
            hintsFrom("event: change\ndata: {}\n"),
            "half a frame is not a frame: a connection cut mid-event must not pull",
        )
    }

    @Test
    fun aStreamThatEndsWithoutAFrameDoesNothing() = runTest {
        assertEquals(0, hintsFrom(""))
        assertEquals(0, hintsFrom(": heartbeat\n\n"))
    }

    /**
     * An unnamed event is a `message` in the SSE grammar. Treating it as a hint is the forgiving
     * direction: a server that stops naming its events costs one redundant pull, where ignoring it
     * would cost every change.
     */
    @Test
    fun anUnnamedEventIsTreatedAsAHint() = runTest {
        assertEquals(1, hintsFrom("data: {}\n\n"))
    }
}
