package com.soulsoftworks.sockbowlquestions.aikey;

import com.soulsoftworks.sockbowlquestions.exception.ResourceNotFoundException;
import com.soulsoftworks.sockbowlquestions.security.AuthenticatedUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The caller's saved Claude API key. Responses carry only {@code last4}, the
 * model and timestamps, never the key.
 *
 * <ul>
 *   <li>{@code GET /api/me/ai-key}: status</li>
 *   <li>{@code PUT /api/me/ai-key} {@code {apiKey, model}}: verify with Anthropic, then save</li>
 *   <li>{@code PATCH /api/me/ai-key} {@code {model}}: change the model</li>
 *   <li>{@code DELETE /api/me/ai-key}: remove (204)</li>
 *   <li>{@code GET /api/me/ai-key/models}: models the saved key can use (404 without one)</li>
 * </ul>
 * 503 when {@code SOCKBOWL_AI_KEY_ENCRYPTION_KEY} isn't set; 502 when Anthropic fails.
 */
@Slf4j
@RestController
@RequestMapping("/api/me/ai-key")
@PreAuthorize("hasAuthority('question:generate')")
public class UserAiKeyController {
    private final UserAiKeyService service;

    public UserAiKeyController(UserAiKeyService service) {
        this.service = service;
    }

    public record SaveRequest(String apiKey, String model) {
        @Override
        public String toString() {
            return "SaveRequest[model=" + model + "]";
        }
    }

    public record ModelRequest(String model) {
    }

    @GetMapping
    public UserAiKeyService.Status get(@AuthenticationPrincipal Jwt jwt) {
        return service.status(userId(jwt));
    }

    @PutMapping
    public UserAiKeyService.Status save(@RequestBody SaveRequest body, @AuthenticationPrincipal Jwt jwt) {
        return service.save(userId(jwt), body == null ? null : body.apiKey(), body == null ? null : body.model());
    }

    @PatchMapping
    public UserAiKeyService.Status updateModel(@RequestBody ModelRequest body, @AuthenticationPrincipal Jwt jwt) {
        return service.updateModel(userId(jwt), body == null ? null : body.model());
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt) {
        service.delete(userId(jwt));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/models")
    public List<String> models(@AuthenticationPrincipal Jwt jwt) {
        return service.listModels(userId(jwt));
    }

    @ExceptionHandler(SavedKeysDisabledException.class)
    ResponseEntity<String> disabled(SavedKeysDisabledException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(e.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<String> notFound(ResourceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }

    @ExceptionHandler(AnthropicModelsClient.AnthropicUnavailableException.class)
    ResponseEntity<String> upstream(AnthropicModelsClient.AnthropicUnavailableException e) {
        log.warn("Anthropic request failed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body("Couldn't reach Anthropic to check the key. Try again in a moment.");
    }

    private static String userId(Jwt jwt) {
        AuthenticatedUser user = AuthenticatedUser.fromJwt(jwt);
        if (!user.isAuthenticated()) {
            // Only reachable with sockbowl.auth.enabled=false: there's no one to save a key for.
            throw new SavedKeysDisabledException();
        }
        return user.keycloakId();
    }
}
