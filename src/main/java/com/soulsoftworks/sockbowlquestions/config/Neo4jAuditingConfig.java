package com.soulsoftworks.sockbowlquestions.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.neo4j.config.EnableNeo4jAuditing;

/**
 * Turns on SDN auditing (D13, M4-PV-01): {@code @CreatedBy}/{@code @CreatedDate}/
 * {@code @LastModifiedBy}/{@code @LastModifiedDate} on {@code Packet}, {@code Tossup},
 * {@code Bonus} and {@code BonusPart} are populated automatically on every SDN-managed
 * save, using {@link com.soulsoftworks.sockbowlquestions.security.SecurityAuditorAware}
 * (bean name {@code securityAuditorAware}) to resolve who's asking.
 *
 * <p>Raw-Cypher write paths bypass SDN's save pipeline entirely and so bypass this;
 * the only one in this codebase, {@code PacketRepository.batchCreatePacket}, stamps the
 * same fields explicitly instead (see its Javadoc).
 */
@Configuration
@EnableNeo4jAuditing(auditorAwareRef = "securityAuditorAware")
public class Neo4jAuditingConfig {
}
