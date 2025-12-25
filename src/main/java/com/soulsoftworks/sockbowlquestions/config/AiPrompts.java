package com.soulsoftworks.sockbowlquestions.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

@Configuration
@Data
public class AiPrompts {
    @Value("classpath:/prompts/naqt-write-packet-generation.st")
    private Resource naqtWriterPacketGenerationPrompt;

    @Value("classpath:/prompts/naqt-write-bonus-generation.st")
    private Resource naqtWriterBonusGenerationPrompt;
}
