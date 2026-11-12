package com.s2p.standins;

import com.s2p.contracts.Command;
import com.s2p.contracts.Erp;
import com.s2p.contracts.SourceResponse;
import com.s2p.sources.ErpStore;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("erp")
public class ErpController {
    private final ErpStore store;

    public ErpController(ErpStore store) {
        this.store = store;
    }

    @GetMapping("/snapshot")
    public SourceResponse<Erp.Snapshot> snapshot() {
        return store.snapshot();
    }

    @PostMapping("/quotations")
    public Command<Erp.Quotation> quote(@RequestBody Erp.NewQuotation q) {
        return store.createQuotation(q);
    }

    @PutMapping("/quotations/{id}/status")
    public Erp.Quotation status(@PathVariable("id") String id, @RequestParam("status") String status) {
        return store.setQuotationStatus(id, status);
    }

    @PostMapping("/orders")
    public Command<Erp.SalesOrder> order(@RequestBody Erp.NewOrder o) {
        return store.createOrder(o);
    }

    @PostMapping("/reservations")
    public Command<Erp.Reservation> reserve(@RequestBody Erp.NewReservation r) {
        return store.reserve(r);
    }

    @PostMapping("/sim/reset")
    public void reset() {
        store.reset();
    }
}
