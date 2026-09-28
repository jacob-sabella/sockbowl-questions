package com.soulsoftworks.sockbowlquestions.ai;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link ChatModel} test double that never leaves the JVM: it answers every
 * prompt with a fixed, parseable tossup, and can be scripted to fail or to block
 * its first call until released (for the concurrency-lock test). No OpenAI or
 * Ollama client is ever built or called.
 */
public class ScriptedChatModel implements ChatModel {

    static final String TOSSUP_JSON = """
            {"question": "This element with atomic number 1 is the lightest. For 10 points, name this gas.",
             "answer": "hydrogen"}""";

    private enum Mode { OK, FAIL, BLOCK_FIRST }

    private volatile Mode mode = Mode.OK;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile CountDownLatch entered = new CountDownLatch(1);
    private volatile CountDownLatch release = new CountDownLatch(0);
    private final AtomicInteger blocked = new AtomicInteger();

    /** Back to answering every call normally. */
    public void reset() {
        mode = Mode.OK;
        calls.set(0);
        blocked.set(0);
        entered = new CountDownLatch(1);
        release.countDown();
        release = new CountDownLatch(0);
    }

    /** Every call throws (a generation that fails after it was admitted). */
    public void failAlways() {
        mode = Mode.FAIL;
    }

    /** The next call blocks until {@link #releaseBlocked()}; later calls answer normally. */
    public void blockFirstCall() {
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        blocked.set(0);
        mode = Mode.BLOCK_FIRST;
    }

    public boolean awaitBlockedCall(long timeout, TimeUnit unit) throws InterruptedException {
        return entered.await(timeout, unit);
    }

    public void releaseBlocked() {
        release.countDown();
    }

    public int calls() {
        return calls.get();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        calls.incrementAndGet();
        Mode current = mode;
        if (current == Mode.FAIL) {
            throw new IllegalStateException("scripted chat model failure");
        }
        if (current == Mode.BLOCK_FIRST && blocked.getAndIncrement() == 0) {
            entered.countDown();
            try {
                if (!release.await(60, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("blocked call was never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage(TOSSUP_JSON))));
    }
}
