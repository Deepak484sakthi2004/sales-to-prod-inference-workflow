package com.s2p.core.enquiry;

import com.s2p.contracts.Erp;
import com.s2p.core.engine.Money;
import com.s2p.core.engine.WorkCalendar;
import com.s2p.core.policy.Policy;
import com.s2p.core.state.CentralView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Picks the price list that is valid today for this customer and quantity, and says why when it falls back. */
public final class PricingService {
    public record Priced(BigDecimal unitPrice, String priceListId, LocalDate quoteValidTo,
                         boolean negotiated, List<String> notes) {
    }

    public Optional<Priced> price(String accountId, String sku, int qty, CentralView v, Policy p) {
        LocalDate today = v.today();
        List<String> notes = new ArrayList<>();
        List<Erp.PriceList> mine = v.erp().priceLists().stream()
                .filter(pl -> pl.sku().equals(sku) && accountId.equals(pl.accountId())).toList();
        Optional<Erp.PriceList> negotiated = mine.stream().filter(pl -> pl.validOn(today) && pl.minQty() <= qty)
                .min(Comparator.comparing(Erp.PriceList::unitPrice));
        Erp.PriceList chosen = negotiated.orElse(null);
        if (chosen == null) {
            for (Erp.PriceList pl : mine) {
                if (pl.validTo().isBefore(today)) {
                    notes.add("Negotiated price " + pl.id() + " (" + Money.inr(pl.unitPrice()) + ") expired on "
                            + WorkCalendar.fmt(pl.validTo()) + "; standard price applies. Renewing it is a commercial decision.");
                } else if (pl.minQty() > qty) {
                    notes.add("Negotiated price " + pl.id() + " needs at least " + pl.minQty() + " units.");
                }
            }
            chosen = v.erp().priceLists().stream()
                    .filter(pl -> pl.sku().equals(sku) && pl.accountId() == null && pl.validOn(today))
                    .findFirst().orElse(null);
        }
        if (chosen == null) {
            return Optional.empty();
        }
        LocalDate quoteValid = today.plusDays(p.quoteValidityDays());
        if (chosen.validTo().isBefore(quoteValid)) {
            notes.add("Quote validity capped at " + WorkCalendar.fmt(chosen.validTo()) + " because price list "
                    + chosen.id() + " ends then.");
            quoteValid = chosen.validTo();
        }
        return Optional.of(new Priced(chosen.unitPrice(), chosen.id(), quoteValid,
                chosen.accountId() != null, notes));
    }
}
