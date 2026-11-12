package com.s2p.standins;

import com.s2p.contracts.SourceResponse;
import com.s2p.contracts.Status;
import com.s2p.sources.StatusFeedStore;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@Profile("statusfeed")
public class StatusFeedController {
    private final StatusFeedStore store;

    public StatusFeedController(StatusFeedStore store) {
        this.store = store;
    }

    @PostMapping("/statuses")
    public Status.PublishResult publish(@RequestBody Status.CustomerStatus s) {
        return store.publish(s);
    }

    @GetMapping("/statuses/{accountId}")
    public SourceResponse<List<Status.CustomerStatus>> statuses(@PathVariable("accountId") String accountId) {
        return store.statuses(accountId);
    }

    @GetMapping("/tokens/{token}")
    public ResponseEntity<Map<String, String>> token(@PathVariable("token") String token) {
        return store.accountForToken(token).map(a -> ResponseEntity.ok(Map.of("accountId", a)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/sim/reset")
    public void reset() {
        store.reset();
    }
}
