package com.soulsoftworks.sockbowlquestions.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.soulsoftworks.sockbowlquestions.client.dto.QbPacketResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/**
 * Thin HTTP client for the public qbreader.org question API.
 *
 * <p>qbreader is a free community resource, so this client is deliberately
 * conservative: a descriptive User-Agent with a contact URL, and on-demand
 * fetches only (no bulk scraping). Callers should import packets when a user
 * asks for them, not proactively mirror the database.
 */
@Component
public class QbreaderClient {

    private final RestClient http;

    public QbreaderClient(
            @Value("${sockbowl.qbreader.base-url:https://www.qbreader.org/api}") String baseUrl,
            @Value("${sockbowl.qbreader.user-agent:sockbowl/1.0 (+https://sockbowl.com; quizbowl app)}") String userAgent) {
        // Use the JDK HttpClient rather than the classpath-default reactor-netty
        // factory: reactor-netty allocates responses into direct (off-heap)
        // buffers, and this container caps direct memory very low (~10MB), so
        // reading qbreader payloads there triggers OutOfMemoryError. The JDK
        // client reads into heap and is well within limits for these payloads.
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(30));

        this.http = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.USER_AGENT, userAgent)
                .build();
    }

    /** All set names qbreader knows about (e.g. "2021 SMH", "2026 ACF Nationals"). */
    public List<String> setList() {
        SetListResponse resp = http.get().uri("/set-list").retrieve().body(SetListResponse.class);
        return resp == null || resp.setList() == null ? List.of() : resp.setList();
    }

    /** Number of packets in a set. */
    public int numPackets(String setName) {
        NumPacketsResponse resp = http.get()
                .uri(b -> b.path("/num-packets").queryParam("setName", setName).build())
                .retrieve().body(NumPacketsResponse.class);
        return resp == null || resp.numPackets() == null ? 0 : resp.numPackets();
    }

    /** All tossups and bonuses in one packet of a set. */
    public QbPacketResponse packet(String setName, int packetNumber) {
        return http.get()
                .uri(b -> b.path("/packet")
                        .queryParam("setName", setName)
                        .queryParam("packetNumber", packetNumber)
                        .build())
                .retrieve().body(QbPacketResponse.class);
    }

    /** Random tossups filtered by category and qbreader difficulty (1-10). */
    public QbPacketResponse randomTossups(List<String> categories, List<Integer> difficulties, int number) {
        return http.get()
                .uri(b -> {
                    b.path("/random-tossup").queryParam("number", number);
                    if (categories != null && !categories.isEmpty()) {
                        b.queryParam("categories", String.join(",", categories));
                    }
                    if (difficulties != null && !difficulties.isEmpty()) {
                        b.queryParam("difficulties", joinInts(difficulties));
                    }
                    return b.build();
                })
                .retrieve().body(QbPacketResponse.class);
    }

    /** Random bonuses filtered by category and qbreader difficulty (1-10). */
    public QbPacketResponse randomBonuses(List<String> categories, List<Integer> difficulties, int number) {
        return http.get()
                .uri(b -> {
                    b.path("/random-bonus").queryParam("number", number);
                    if (categories != null && !categories.isEmpty()) {
                        b.queryParam("categories", String.join(",", categories));
                    }
                    if (difficulties != null && !difficulties.isEmpty()) {
                        b.queryParam("difficulties", joinInts(difficulties));
                    }
                    return b.build();
                })
                .retrieve().body(QbPacketResponse.class);
    }

    private static String joinInts(List<Integer> ints) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ints.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ints.get(i));
        }
        return sb.toString();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SetListResponse(List<String> setList) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NumPacketsResponse(Integer numPackets) {}
}
