package com.s2p.standins;

import com.s2p.contracts.Command;
import com.s2p.contracts.Planning;
import com.s2p.contracts.SourceResponse;
import com.s2p.sources.PlanningStore;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@Profile("planning")
public class PlanningController {
    public record Slip(String lineId, int workingDays, LocalDate from, String reason) {
    }

    public record Progress(String workOrderId, int completedQty) {
    }

    private final PlanningStore store;

    public PlanningController(PlanningStore store) {
        this.store = store;
    }

    @GetMapping("/snapshot")
    public SourceResponse<Planning.Snapshot> snapshot() {
        return store.snapshot();
    }

    @PostMapping("/work-orders")
    public Command<Planning.WorkOrder> create(@RequestBody Planning.NewWorkOrder w) {
        return store.createWorkOrder(w);
    }

    @PostMapping("/reschedules")
    public List<Command<Planning.WorkOrder>> reschedule(@RequestBody List<Planning.Reschedule> batch) {
        return store.reschedule(batch);
    }

    @GetMapping("/events")
    public List<Planning.Event> events(@RequestParam(name = "after", defaultValue = "0") long after) {
        return store.events(after);
    }

    @PostMapping("/sim/slip")
    public Planning.Event slip(@RequestBody Slip s) {
        return store.slipLine(s.lineId(), s.workingDays(), s.from(), s.reason());
    }

    @PostMapping("/sim/progress")
    public Planning.Event progress(@RequestBody Progress p) {
        return store.reportProgress(p.workOrderId(), p.completedQty());
    }

    @PostMapping("/sim/feed-lag")
    public void lag(@RequestParam("minutes") int minutes) {
        store.setFeedLag(minutes);
    }

    @PostMapping("/sim/reset")
    public void reset() {
        store.reset();
    }
}
