package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.exception.InvalidApiRequestException;
import com.soulsoftworks.sockbowlquestions.exception.PacketVersionConflictException;
import com.soulsoftworks.sockbowlquestions.exception.PayloadTooLargeException;
import com.soulsoftworks.sockbowlquestions.exception.ResourceNotFoundException;
import com.soulsoftworks.sockbowlquestions.exception.ValidationFailedException;
import graphql.GraphQLError;
import graphql.Scalars;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.ResultPath;
import graphql.language.Field;
import graphql.schema.DataFetchingEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Each authoring exception maps to its GraphQL classification and extensions
 * (plans/m3-packets.md 3.1.2), and security exceptions are left for the security
 * resolver (so they stay FORBIDDEN / UNAUTHORIZED).
 */
class AuthoringExceptionResolverTest {

    private final AuthoringExceptionResolver resolver = new AuthoringExceptionResolver();
    private DataFetchingEnvironment env;

    @BeforeEach
    void setUp() {
        env = mock(DataFetchingEnvironment.class);
        ExecutionStepInfo stepInfo = ExecutionStepInfo.newExecutionStepInfo()
                .type(Scalars.GraphQLString)
                .path(ResultPath.rootPath().segment("renamePacket"))
                .build();
        lenient().when(env.getExecutionStepInfo()).thenReturn(stepInfo);
        lenient().when(env.getField()).thenReturn(Field.newField("renamePacket").build());
    }

    @Test
    void versionConflict_isConflictWithPacketIdAndCurrentVersion() {
        GraphQLError error = resolver.resolveToSingleError(
                new PacketVersionConflictException("p1", 3L, 5L), env);

        assertThat(error).isNotNull();
        assertThat(error.getErrorType()).isEqualTo(SockbowlErrorType.CONFLICT);
        assertThat(error.getExtensions())
                .containsEntry("packetId", "p1")
                .containsEntry("currentVersion", 5L);
        assertThat(error.getMessage()).contains("p1").contains("Reload");
        assertThat(error.getPath()).containsExactly("renamePacket");
        assertThat(classification(error)).isEqualTo("CONFLICT");
    }

    @Test
    void validationFailed_isValidationFailedWithField() {
        GraphQLError error = resolver.resolveToSingleError(
                new ValidationFailedException("name", "Packet name exceeds 200 characters"), env);

        assertThat(error.getErrorType()).isEqualTo(SockbowlErrorType.VALIDATION_FAILED);
        assertThat(error.getExtensions()).containsEntry("field", "name");
        assertThat(error.getMessage()).isEqualTo("Packet name exceeds 200 characters");
        assertThat(classification(error)).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void validationFailed_withoutField_keepsNullFieldExtension() {
        GraphQLError error = resolver.resolveToSingleError(
                new ValidationFailedException(null, "A bonus needs at least one part"), env);

        assertThat(error.getErrorType()).isEqualTo(SockbowlErrorType.VALIDATION_FAILED);
        assertThat(error.getExtensions()).containsKey("field");
        assertThat(error.getExtensions().get("field")).isNull();
    }

    @Test
    void payloadTooLarge_isPayloadTooLargeWithLimitBytes() {
        GraphQLError error = resolver.resolveToSingleError(new PayloadTooLargeException(524_288L), env);

        assertThat(error.getErrorType()).isEqualTo(SockbowlErrorType.PAYLOAD_TOO_LARGE);
        assertThat(error.getExtensions()).containsEntry("limitBytes", 524_288L);
        assertThat(error.getMessage()).contains("524288");
        assertThat(classification(error)).isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    void notFound_staysNotFound() {
        GraphQLError error = resolver.resolveToSingleError(ResourceNotFoundException.of("Packet", "x"), env);
        assertThat(error.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        assertThat(error.getMessage()).isEqualTo("Packet not found: x");
    }

    @Test
    void invalidRequestAndIllegalArgument_stayBadRequest() {
        assertThat(resolver.resolveToSingleError(new InvalidApiRequestException("bad"), env).getErrorType())
                .isEqualTo(ErrorType.BAD_REQUEST);
        assertThat(resolver.resolveToSingleError(new IllegalArgumentException("bad"), env).getErrorType())
                .isEqualTo(ErrorType.BAD_REQUEST);
    }

    @Test
    void securityExceptions_areLeftToTheSecurityResolver() {
        assertThat(resolver.resolveToSingleError(new AccessDeniedException("no"), env)).isNull();
        assertThat(resolver.resolveToSingleError(new BadCredentialsException("no"), env)).isNull();
    }

    @Test
    void unknownExceptions_areNotMapped() {
        assertThat(resolver.resolveToSingleError(new IllegalStateException("boom"), env)).isNull();
    }

    /** The classification as serialized to the client in {@code extensions.classification}. */
    @SuppressWarnings("unchecked")
    private static Object classification(GraphQLError error) {
        Map<String, Object> spec = error.toSpecification();
        return ((Map<String, Object>) spec.get("extensions")).get("classification");
    }
}
