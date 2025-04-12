package com.soulsoftworks.sockbowlquestions.api;

import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.VectorStore;

public class QbQaAdvisor extends QuestionAnswerAdvisor {

    public QbQaAdvisor(VectorStore vectorStore) {
        super(vectorStore);
    }
}
