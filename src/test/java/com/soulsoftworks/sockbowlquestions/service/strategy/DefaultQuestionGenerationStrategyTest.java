package com.soulsoftworks.sockbowlquestions.service.strategy;

import com.soulsoftworks.sockbowlquestions.config.AiPrompts;
import com.soulsoftworks.sockbowlquestions.dto.AiRequestContext;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.service.ChatClientFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** One model call per question, earlier answers fed back as an avoid list, and only bad JSON retried. */
class DefaultQuestionGenerationStrategyTest {

    private final List<String> prompts = new ArrayList<>();
    private final Deque<Function<Prompt, ChatResponse>> script = new ArrayDeque<>();
    private int tossupCount;
    private DefaultQuestionGenerationStrategy strategy;

    private static ChatResponse text(String s) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(s))));
    }

    @BeforeEach
    void setUp() {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                String user = prompt.getUserMessage().getText();
                prompts.add(user);
                if (!script.isEmpty()) {
                    return script.pop().apply(prompt);
                }
                if (user.contains("bonus #")) {
                    return text("""
                            {"preamble":"For 10 points each:","part_a_question":"qa","part_a_answer":"aa",
                             "part_b_question":"qb","part_b_answer":"ab","part_c_question":"qc","part_c_answer":"ac"}""");
                }
                tossupCount++;
                return text("{\"question\":\"clue %d\",\"answer\":\"answer-%d\"}".formatted(tossupCount, tossupCount));
            }
        };
        ChatClient client = ChatClient.builder(model).build();
        ChatClientFactory factory = mock(ChatClientFactory.class);
        when(factory.getChatClient(any(), nullable(String.class))).thenReturn(client);
        AiPrompts aiPrompts = new AiPrompts();
        aiPrompts.setNaqtWriterPacketGenerationPrompt(new ClassPathResource("prompts/naqt-write-packet-generation.st"));
        aiPrompts.setNaqtWriterBonusGenerationPrompt(new ClassPathResource("prompts/naqt-write-bonus-generation.st"));
        strategy = new DefaultQuestionGenerationStrategy(factory, aiPrompts);
    }

    @Test
    void makesExactlyOneCallPerQuestionAndFeedsBackEarlierAnswers() throws Exception {
        Packet packet = strategy.generatePacket("Zelda", "", 3, true, AiRequestContext.builder().build(), "u1", "U");

        assertThat(prompts).hasSize(6);
        assertThat(packet.getTossups()).hasSize(3);
        assertThat(packet.getBonuses()).hasSize(3);
        assertThat(prompts.get(0)).contains("(none yet)");
        assertThat(prompts.get(2)).contains("- answer-1").contains("- answer-2").doesNotContain("of 20");
        // Bonuses see every tossup answer and the earlier bonuses' answers.
        assertThat(prompts.get(5)).contains("- answer-3").contains("- aa").contains("- ac");
    }

    @Test
    void retriesOnlyUnusableAnswersAndGivesUpAfterThree() {
        script.add(p -> text("Sure! Here's a tossup."));
        script.add(p -> text("{\"question\":\"\",\"answer\":\"x\"}"));
        var tossup = strategy.generateTossup("Zelda", "", List.of(), AiRequestContext.builder().build());
        assertThat(tossup.getAnswer()).isEqualTo("answer-1");
        assertThat(prompts).hasSize(3);

        prompts.clear();
        for (int i = 0; i < 3; i++) {
            script.add(p -> text("not json"));
        }
        assertThatThrownBy(() -> strategy.generateTossup("Zelda", "", List.of(), AiRequestContext.builder().build()))
                .hasMessageContaining("after 3 attempts");
        assertThat(prompts).hasSize(3);
    }

    @Test
    void providerErrorsAreNotRetried() {
        script.add(p -> {
            throw new IllegalStateException("401 invalid x-api-key");
        });
        assertThatThrownBy(() -> strategy.generateTossup("Zelda", "", List.of(), AiRequestContext.builder().build()))
                .hasMessageContaining("401");
        assertThat(prompts).hasSize(1);
    }
}
