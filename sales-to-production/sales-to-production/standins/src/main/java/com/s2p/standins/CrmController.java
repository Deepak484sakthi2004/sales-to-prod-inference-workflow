package com.s2p.standins;

import com.s2p.contracts.Crm;
import com.s2p.contracts.SourceResponse;
import com.s2p.sources.CrmStore;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("crm")
public class CrmController {
    private final CrmStore store;

    public CrmController(CrmStore store) {
        this.store = store;
    }

    @GetMapping("/snapshot")
    public SourceResponse<Crm.Snapshot> snapshot() {
        return store.snapshot();
    }

    @PostMapping("/sim/reset")
    public void reset() {
        store.reset();
    }
}
