package com.soulsoftworks.sockbowlquestions.repository;

import com.soulsoftworks.sockbowlquestions.SockbowlQuestionsApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.graphql.data.GraphQlRepository;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard for the D2 read path (M2 judge note, latent M3 risk). A {@code @GraphQlRepository}
 * is auto-registered by Spring for GraphQL as the data fetcher of every schema query that
 * returns its entity type and has no explicit {@code @QueryMapping}. For {@code Packet}
 * that would skip {@code PacketReadPolicy} (drafts, EPHEMERAL packets) and the answer-free
 * projection, so a new Packet query added to the schema without a controller method would
 * silently leak answers. No repository in this service may carry the annotation; caller-facing
 * reads must go through a controller.
 */
class NoAutoRegisteredGraphQlRepositoryTest {

    @Test
    void noRepositoryIsAutoRegisteredAsAGraphQlDataFetcher() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                // Repositories are interfaces, which the default filter skips.
                return true;
            }
        };
        scanner.addIncludeFilter(new AnnotationTypeFilter(GraphQlRepository.class, true, true));

        Set<String> annotated = scanner.findCandidateComponents(SockbowlQuestionsApplication.class.getPackageName())
                .stream().map(BeanDefinition::getBeanClassName)
                .filter(name -> !name.equals(AnnotatedProbe.class.getName()))
                .collect(Collectors.toSet());

        assertThat(annotated).as("types annotated with @GraphQlRepository").isEmpty();
    }

    @Test
    void theScannerWouldFindAnAnnotatedRepository() {
        // Sanity check that the scan above can see an annotated interface at all.
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return true;
            }
        };
        scanner.addIncludeFilter(new AnnotationTypeFilter(GraphQlRepository.class, true, true));

        Set<String> annotated = scanner.findCandidateComponents(NoAutoRegisteredGraphQlRepositoryTest.class.getPackageName())
                .stream().map(BeanDefinition::getBeanClassName).collect(Collectors.toSet());

        assertThat(annotated).containsExactly(AnnotatedProbe.class.getName());
    }

    /** Test-only fixture for {@link #theScannerWouldFindAnAnnotatedRepository()}; never a bean. */
    @GraphQlRepository
    interface AnnotatedProbe {
    }
}
