package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.exception.InvalidApiRequestException;
import com.soulsoftworks.sockbowlquestions.exception.PacketVersionConflictException;
import com.soulsoftworks.sockbowlquestions.exception.PayloadTooLargeException;
import com.soulsoftworks.sockbowlquestions.exception.ResourceNotFoundException;
import com.soulsoftworks.sockbowlquestions.exception.ValidationFailedException;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps authoring-layer exceptions onto meaningful GraphQL errors so clients
 * receive typed classifications instead of generic INTERNAL_ERROR responses.
 *
 * <ul>
 *   <li>{@link ResourceNotFoundException} → {@code NOT_FOUND}</li>
 *   <li>{@link PacketVersionConflictException} → {@link SockbowlErrorType#CONFLICT},
 *       extensions {@code packetId}, {@code currentVersion}</li>
 *   <li>{@link ValidationFailedException} → {@link SockbowlErrorType#VALIDATION_FAILED},
 *       extension {@code field}</li>
 *   <li>{@link PayloadTooLargeException} → {@link SockbowlErrorType#PAYLOAD_TOO_LARGE},
 *       extension {@code limitBytes}</li>
 *   <li>{@link InvalidApiRequestException}, {@link IllegalArgumentException} → {@code BAD_REQUEST}</li>
 * </ul>
 * Anything else (notably Spring Security's {@code AccessDeniedException} /
 * {@code AuthenticationException}) is left to the other resolvers, so it still comes out
 * as {@code FORBIDDEN} / {@code UNAUTHORIZED}.
 */
@Component
public class AuthoringExceptionResolver extends DataFetcherExceptionResolverAdapter {

    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        if (ex instanceof ResourceNotFoundException) {
            return GraphqlErrorBuilder.newError(env)
                    .errorType(ErrorType.NOT_FOUND)
                    .message(ex.getMessage())
                    .build();
        }
        if (ex instanceof PacketVersionConflictException conflict) {
            Map<String, Object> extensions = new LinkedHashMap<>();
            extensions.put("packetId", conflict.getPacketId());
            extensions.put("currentVersion", conflict.getCurrentVersion());
            return GraphqlErrorBuilder.newError(env)
                    .errorType(SockbowlErrorType.CONFLICT)
                    .message(ex.getMessage())
                    .extensions(extensions)
                    .build();
        }
        if (ex instanceof ValidationFailedException validation) {
            Map<String, Object> extensions = new LinkedHashMap<>();
            extensions.put("field", validation.getField());
            return GraphqlErrorBuilder.newError(env)
                    .errorType(SockbowlErrorType.VALIDATION_FAILED)
                    .message(ex.getMessage())
                    .extensions(extensions)
                    .build();
        }
        if (ex instanceof PayloadTooLargeException tooLarge) {
            return GraphqlErrorBuilder.newError(env)
                    .errorType(SockbowlErrorType.PAYLOAD_TOO_LARGE)
                    .message(ex.getMessage())
                    .extensions(Map.of("limitBytes", tooLarge.getLimitBytes()))
                    .build();
        }
        if (ex instanceof InvalidApiRequestException || ex instanceof IllegalArgumentException) {
            return GraphqlErrorBuilder.newError(env)
                    .errorType(ErrorType.BAD_REQUEST)
                    .message(ex.getMessage())
                    .build();
        }
        return null;
    }
}
