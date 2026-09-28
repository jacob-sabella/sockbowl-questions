package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.exception.InvalidApiRequestException;
import com.soulsoftworks.sockbowlquestions.repository.ContentCountsRepository;
import com.soulsoftworks.sockbowlquestions.repository.ContentCountsRepository.ContentCounts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Content counts for game's admin usage API (M4-AD-01; plan m4-limits section 2.8).
 *
 * <p><b>Contract</b> (called by game's {@code QuestionsUsageClient}, which relays
 * the admin's own bearer token):
 * <pre>
 * GET /api/admin/usage/content-counts?subs=a,b,...
 *   200 {"a": {"packetsOwned": 3, "questionsCreated": 41}, "b": {...}}
 *   400 more than sockbowl.admin.usage.max-subs (100) distinct subjects
 *   401 no or invalid bearer; 403 without admin:access
 * </pre>
 * Every requested subject is present in the answer, with zeros when it has no
 * content. Blank and duplicate entries are ignored; no {@code subs} (or only
 * blanks) answers {@code {}}. {@code questionsCreated} counts {@code Tossup} and
 * {@code Bonus} nodes by {@code createdBy}.
 *
 * <p>Only exists with auth on: with auth off there is no admin to authorize, so
 * the endpoint is simply absent.
 */
@RestController
@RequestMapping("/api/admin/usage")
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
public class AdminUsageController {

    public static final String ADMIN_ACCESS = "admin:access";

    private final ContentCountsRepository contentCountsRepository;
    private final int maxSubs;

    public AdminUsageController(ContentCountsRepository contentCountsRepository,
                                @Value("${sockbowl.admin.usage.max-subs:100}") int maxSubs) {
        this.contentCountsRepository = contentCountsRepository;
        this.maxSubs = maxSubs;
    }

    @GetMapping("/content-counts")
    @PreAuthorize("hasAuthority('" + ADMIN_ACCESS + "')")
    public Map<String, ContentCounts> contentCounts(@RequestParam(name = "subs", required = false) String subs) {
        Set<String> distinct = new LinkedHashSet<>();
        if (subs != null) {
            for (String part : subs.split(",")) {
                String sub = part.trim();
                if (!sub.isEmpty()) {
                    distinct.add(sub);
                }
            }
        }
        if (distinct.size() > maxSubs) {
            throw new InvalidApiRequestException("At most " + maxSubs + " subjects per request (got "
                    + distinct.size() + ")");
        }
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return contentCountsRepository.countsFor(distinct);
    }
}
