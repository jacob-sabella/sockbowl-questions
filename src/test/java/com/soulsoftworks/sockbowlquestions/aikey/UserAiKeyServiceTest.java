package com.soulsoftworks.sockbowlquestions.aikey;

import com.soulsoftworks.sockbowlquestions.dto.AiProvider;
import com.soulsoftworks.sockbowlquestions.dto.AiRequestContext;
import com.soulsoftworks.sockbowlquestions.exception.InvalidApiRequestException;
import com.soulsoftworks.sockbowlquestions.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserAiKeyServiceTest {
    private static final String KEY = "sk-ant-api03-abcdefWXYZ";
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");

    private UserAiKeyRepository repository;
    private AnthropicModelsClient modelsClient;
    private AiKeyCipher cipher;
    private UserAiKeyService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserAiKeyRepository.class);
        modelsClient = mock(AnthropicModelsClient.class);
        cipher = new AiKeyCipher(Base64.getEncoder().encodeToString(new byte[32]));
        service = new UserAiKeyService(repository, cipher, modelsClient, Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void saveVerifiesThenStoresOnlyCiphertextAndLast4() {
        when(modelsClient.listModels(KEY)).thenReturn(List.of("claude-sonnet-5", "claude-opus-5-5"));
        when(repository.findById("u1")).thenReturn(Optional.empty());

        UserAiKeyService.Status status = service.save("u1", "  " + KEY + " ", "claude-sonnet-5");

        ArgumentCaptor<UserAiKey> saved = ArgumentCaptor.forClass(UserAiKey.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getCiphertext()).doesNotContain(KEY);
        assertThat(cipher.decrypt(saved.getValue().getCiphertext(), "u1")).isEqualTo(KEY);
        assertThat(status).isEqualTo(new UserAiKeyService.Status(true, "anthropic", "claude-sonnet-5", "WXYZ", NOW));
        assertThat(status.toString()).doesNotContain(KEY);
    }

    @Test
    void aKeyAnthropicRejectsIsNeverStored() {
        when(modelsClient.listModels(KEY)).thenThrow(new InvalidApiRequestException("Anthropic rejected this API key"));

        assertThatThrownBy(() -> service.save("u1", KEY, "claude-sonnet-5"))
                .isInstanceOf(InvalidApiRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsAModelTheKeyCannotUse() {
        when(modelsClient.listModels(KEY)).thenReturn(List.of("claude-haiku-4-5-20251001"));

        assertThatThrownBy(() -> service.save("u1", KEY, "claude-opus-5-5"))
                .isInstanceOf(InvalidApiRequestException.class).hasMessageContaining("claude-opus-5-5");
    }

    @Test
    void rejectsBlankOrMalformedInput() {
        assertThatThrownBy(() -> service.save("u1", " ", "claude-sonnet-5")).isInstanceOf(InvalidApiRequestException.class);
        assertThatThrownBy(() -> service.save("u1", "sk ant", "claude-sonnet-5")).isInstanceOf(InvalidApiRequestException.class);
        assertThatThrownBy(() -> service.save("u1", KEY, "bad model!")).isInstanceOf(InvalidApiRequestException.class);
        verify(modelsClient, never()).listModels(any());
    }

    @Test
    void resolveContextDecryptsAndLetsXModelOverride() {
        UserAiKey k = stored("u1", "claude-sonnet-5");
        when(repository.findById("u1")).thenReturn(Optional.of(k));

        AiRequestContext ctx = service.resolveContext("u1", null).orElseThrow();
        assertThat(ctx.getProvider()).isEqualTo(AiProvider.ANTHROPIC);
        assertThat(ctx.getApiKey()).isEqualTo(KEY);
        assertThat(ctx.getModel()).isEqualTo("claude-sonnet-5");
        assertThat(ctx.isComplete()).isTrue();
        assertThat(ctx.toString()).doesNotContain(KEY);

        assertThat(service.resolveContext("u1", "claude-opus-5-5").orElseThrow().getModel()).isEqualTo("claude-opus-5-5");
    }

    @Test
    void resolveContextIsEmptyForGuestsOrUsersWithoutAKey() {
        when(repository.findById("u2")).thenReturn(Optional.empty());

        assertThat(service.resolveContext(null, null)).isEmpty();
        assertThat(service.resolveContext("u2", null)).isEmpty();
    }

    @Test
    void disabledWithoutAMasterKey() {
        UserAiKeyService off = new UserAiKeyService(repository, new AiKeyCipher(""), modelsClient);

        assertThat(off.resolveContext("u1", null)).isEmpty();
        assertThatThrownBy(() -> off.status("u1")).isInstanceOf(SavedKeysDisabledException.class);
        assertThatThrownBy(() -> off.save("u1", KEY, "claude-sonnet-5")).isInstanceOf(SavedKeysDisabledException.class);
    }

    @Test
    void updateModelChecksAgainstTheSavedKey() {
        when(repository.findById("u1")).thenReturn(Optional.of(stored("u1", "claude-sonnet-5")));
        when(modelsClient.listModels(KEY)).thenReturn(List.of("claude-sonnet-5", "claude-opus-5-5"));

        assertThat(service.updateModel("u1", "claude-opus-5-5").model()).isEqualTo("claude-opus-5-5");
    }

    @Test
    void modelsWithoutASavedKeyIsNotFound() {
        when(repository.findById("u1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listModels("u1")).isInstanceOf(ResourceNotFoundException.class);
    }

    private UserAiKey stored(String user, String model) {
        return UserAiKey.builder().keycloakId(user).provider("anthropic")
                .ciphertext(cipher.encrypt(KEY, user)).model(model).last4("WXYZ")
                .createdAt(NOW).updatedAt(NOW).build();
    }
}
