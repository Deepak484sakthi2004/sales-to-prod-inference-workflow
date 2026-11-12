package com.s2p.standins;

import com.s2p.contracts.Command;
import com.s2p.contracts.Mail;
import com.s2p.contracts.SourceResponse;
import com.s2p.sources.MailboxStore;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Profile("mailbox")
public class MailboxController {
    private final MailboxStore store;

    public MailboxController(MailboxStore store) {
        this.store = store;
    }

    @GetMapping("/inbox")
    public SourceResponse<List<Mail.Message>> inbox() {
        return store.inbox();
    }

    @GetMapping("/outbox")
    public SourceResponse<List<Mail.Sent>> outbox() {
        return store.outbox();
    }

    @PostMapping("/outbox")
    public Command<Mail.Sent> send(@RequestBody Mail.Outbound m) {
        return store.send(m);
    }

    @PostMapping("/sim/inject")
    public Mail.Message inject(@RequestBody Mail.Message m) {
        return store.inject(m);
    }

    @PostMapping("/sim/reset")
    public void reset() {
        store.reset();
    }
}
