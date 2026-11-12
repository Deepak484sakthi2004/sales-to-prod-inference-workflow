package com.s2p.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/** Everything configurable lives under s2p.* in application.yml; see PolicyFactory for the policy part. */
@ConfigurationProperties(prefix = "s2p")
public record CoreProperties(Sources sources, Sim sim, Poll poll, String decisionLog, PolicyProps policy, Store store,
                             Ai ai) {

    public record Sources(String mailbox, String crm, String erp, String planning, String statusFeed) {
    }

    public record Sim(String start, Long realAnchorEpoch) {
    }

    public record Poll(boolean enabled, long intervalMs) {
    }

    public record PolicyProps(Integer minSlackWorkingDays, Boolean allowAlternateLines, Integer quoteValidityDays,
                              Integer planningSlaMinutes, Integer erpSlaMinutes, Integer commitLimitMinutes,
                              Map<String, Double> goodwillPctPerLateDay, Double proactiveNoticeGoodwillReduction,
                              List<String> enabledRules) {
    }

    /** s2p.store.type: memory (default) or mongo. s2p.store.mongo.uri: the Atlas connection string. */
    public record Store(String type, Mongo mongo) {
        public record Mongo(String uri, String database) {
        }
    }

    /** s2p.ai: the Python AI service (Gemini behind it). Off, or unreachable: mail waits unread, questions unread. */
    public record Ai(boolean enabled, String url, long timeoutMs) {
    }
}
