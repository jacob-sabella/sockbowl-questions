package com.soulsoftworks.sockbowlquestions.aikey;

import com.soulsoftworks.sockbowlquestions.dto.AiProvider;
import com.soulsoftworks.sockbowlquestions.dto.AiRequestContext;
import com.soulsoftworks.sockbowlquestions.exception.InvalidApiRequestException;
import com.soulsoftworks.sockbowlquestions.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Saved per-user Claude API keys: save (verified against Anthropic first),
 * change model, remove, and resolve into an {@link AiRequestContext} for
 * generation. The plaintext key never leaves this service except inside that
 * context, and is never logged.
 */
@Slf4j
@Service
public class UserAiKeyService {
    public static final String PROVIDER_ANTHROPIC = "anthropic";
    static final int MAX_KEY_LENGTH = 512;
    static final int MAX_MODEL_LENGTH = 100;

    private final UserAiKeyRepository repository;
    private final AiKeyCipher cipher;
    private final AnthropicModelsClient modelsClient;
    private final Clock clock;

    @Autowired
    public UserAiKeyService(UserAiKeyRepository repository, AiKeyCipher cipher,
                            AnthropicModelsClient modelsClient) {
        this(repository, cipher, modelsClient, Clock.systemUTC());
    }

    UserAiKeyService(UserAiKeyRepository repository, AiKeyCipher cipher,
                     AnthropicModelsClient modelsClient, Clock clock) {
        this.repository = repository;
        this.cipher = cipher;
        this.modelsClient = modelsClient;
        this.clock = clock;
    }

    /** What the profile page shows; never includes the key. */
    public record Status(boolean configured, String provider, String model, String last4, Instant updatedAt) {
        static Status none() {
            return new Status(false, PROVIDER_ANTHROPIC, null, null, null);
        }

        static Status of(UserAiKey k) {
            return new Status(true, k.getProvider(), k.getModel(), k.getLast4(), k.getUpdatedAt());
        }
    }

    public Status status(String userId) {
        requireEnabled();
        return repository.findById(userId).map(Status::of).orElseGet(Status::none);
    }

    public Status save(String userId, String apiKey, String model) {
        requireEnabled();
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.isEmpty() || key.length() > MAX_KEY_LENGTH || key.chars().anyMatch(Character::isWhitespace)) {
            throw new InvalidApiRequestException("Paste a valid Claude API key");
        }
        String chosenModel = requireModelName(model);
        List<String> available = modelsClient.listModels(key);
        requireAvailable(chosenModel, available);

        Instant now = clock.instant();
        UserAiKey existing = repository.findById(userId).orElse(null);
        UserAiKey saved = repository.save(UserAiKey.builder()
                .keycloakId(userId)
                .provider(PROVIDER_ANTHROPIC)
                .ciphertext(cipher.encrypt(key, userId))
                .model(chosenModel)
                .last4(key.substring(Math.max(0, key.length() - 4)))
                .createdAt(existing != null && existing.getCreatedAt() != null ? existing.getCreatedAt() : now)
                .updatedAt(now)
                .build());
        log.info("Saved a Claude API key for user {} (model {})", userId, chosenModel);
        return Status.of(saved);
    }

    public Status updateModel(String userId, String model) {
        UserAiKey k = require(userId);
        String chosenModel = requireModelName(model);
        requireAvailable(chosenModel, modelsClient.listModels(cipher.decrypt(k.getCiphertext(), userId)));
        k.setModel(chosenModel);
        k.setUpdatedAt(clock.instant());
        return Status.of(repository.save(k));
    }

    public void delete(String userId) {
        requireEnabled();
        repository.deleteById(userId);
        log.info("Removed the saved Claude API key for user {}", userId);
    }

    public List<String> listModels(String userId) {
        UserAiKey k = require(userId);
        return modelsClient.listModels(cipher.decrypt(k.getCiphertext(), userId));
    }

    /**
     * The saved key as a generation context, if the user has one and saved keys
     * are enabled. {@code modelOverride} (an {@code X-Model} header) wins over the
     * saved model when given.
     */
    public Optional<AiRequestContext> resolveContext(String userId, String modelOverride) {
        if (userId == null || !cipher.isEnabled()) {
            return Optional.empty();
        }
        return repository.findById(userId).map(k -> AiRequestContext.builder()
                .provider(AiProvider.ANTHROPIC)
                .apiKey(cipher.decrypt(k.getCiphertext(), userId))
                .model(modelOverride != null && !modelOverride.isBlank() ? modelOverride : k.getModel())
                .build());
    }

    private UserAiKey require(String userId) {
        requireEnabled();
        return repository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("No saved Claude API key"));
    }

    private void requireEnabled() {
        if (!cipher.isEnabled()) {
            throw new SavedKeysDisabledException();
        }
    }

    private static String requireModelName(String model) {
        String m = model == null ? "" : model.trim();
        if (m.isEmpty() || m.length() > MAX_MODEL_LENGTH || !m.matches("[A-Za-z0-9._:-]+")) {
            throw new InvalidApiRequestException("Choose a Claude model");
        }
        return m;
    }

    private static void requireAvailable(String model, List<String> available) {
        if (!available.isEmpty() && !available.contains(model)) {
            throw new InvalidApiRequestException("This key can't use the model " + model);
        }
    }
}
