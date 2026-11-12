package com.s2p.core.web;

import com.s2p.core.CoreSystem;
import com.s2p.core.status.StatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The only endpoint a customer can reach. It returns the customer answer and nothing else: no confidence factors,
 * no internal basis, no other accounts. The token decides the account; a guessed order reference reveals nothing.
 */
@RestController
@RequestMapping("/customer/api")
public class CustomerController {
    private final CoreSystem core;

    public CustomerController(CoreSystem core) {
        this.core = core;
    }

    @GetMapping("/orders")
    public StatusService.CustomerAnswer orders(@RequestHeader("X-Customer-Token") String token,
                                               @RequestParam(name = "orderRef", required = false) String orderRef) {
        return core.customerView(token, orderRef).answer();
    }
}
