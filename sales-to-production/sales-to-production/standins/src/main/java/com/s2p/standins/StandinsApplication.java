package com.s2p.standins;

import com.s2p.contracts.SimClock;
import com.s2p.sources.CrmStore;
import com.s2p.sources.ErpStore;
import com.s2p.sources.MailboxStore;
import com.s2p.sources.PlanningStore;
import com.s2p.sources.StatusFeedStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * One codebase, five processes: start it with --spring.profiles.active=mailbox|crm|erp|planning|statusfeed and it
 * becomes that system of record, reachable only through its own HTTP interface.
 */
@SpringBootApplication
public class StandinsApplication {

    public static void main(String[] args) {
        SpringApplication.run(StandinsApplication.class, args);
    }

    @Bean
    SimClock simClock(@Value("${s2p.sim.start}") String start,
                      @Value("${s2p.sim.real-anchor-epoch:0}") long anchor) {
        return SimClock.live(OffsetDateTime.parse(start), anchor > 0 ? Instant.ofEpochSecond(anchor) : Instant.now());
    }

    @Bean
    @Profile("mailbox")
    MailboxStore mailboxStore(SimClock c) {
        return new MailboxStore(c, c.today());
    }

    @Bean
    @Profile("crm")
    CrmStore crmStore(SimClock c) {
        return new CrmStore(c, c.today());
    }

    @Bean
    @Profile("erp")
    ErpStore erpStore(SimClock c) {
        return new ErpStore(c, c.today());
    }

    @Bean
    @Profile("planning")
    PlanningStore planningStore(SimClock c) {
        return new PlanningStore(c, c.today());
    }

    @Bean
    @Profile("statusfeed")
    StatusFeedStore statusFeedStore(SimClock c) {
        return new StatusFeedStore(c, c.today());
    }
}
