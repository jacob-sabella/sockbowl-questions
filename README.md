# Sockbowl Questions

Sockbowl Questions is a backend service for managing, generating, and serving quizbowl question packets and tossups for the Sockbowl platform. It leverages Neo4j for graph-based storage and integrates AI-powered question generation for dynamic packet creation.

## Features

- **Packet Repository:** Store and search question packets in Neo4j.
- **GraphQL API:** Fetch packets and search by name via GraphQL endpoints.
- **AI Question Generation:** Uses AI to generate NAQT-style tossup questions with pyramidality and factual accuracy.
- **Difficulty & Topic Modeling:** Supports custom difficulty levels and topics for packets.

## Technologies

- **Java** (Spring Boot)
- **Neo4j** (graph database)
- **Spring GraphQL**
- **AI Integration** (Spring AI, custom prompt templates)

## Getting Started

See `HELP.md` for setup instructions. Reference guides:
- [Spring Boot Gradle Plugin Reference Guide](https://docs.spring.io/spring-boot/docs/3.0.1/gradle-plugin/reference/html/)
- [GraphQL with Spring Boot](https://spring.io/projects/spring-graphql)

## License

MIT License. See `LICENSE` for details.

---

*Created by Jacob Sabella*
