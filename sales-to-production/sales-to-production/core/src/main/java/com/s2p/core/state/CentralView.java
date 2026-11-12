package com.s2p.core.state;

import com.s2p.contracts.Crm;
import com.s2p.contracts.Erp;
import com.s2p.contracts.Mail;
import com.s2p.contracts.Planning;
import com.s2p.core.knowledge.Fact;
import com.s2p.core.knowledge.Freshness;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One consistent read of the business: sales, planning, production and status all answer from the same view, and
 * every view knows the version and age of each source it was built from.
 */
public record CentralView(OffsetDateTime at, LocalDate today, Erp.Snapshot erp, Planning.Snapshot planning,
                          Crm.Snapshot crm, List<Mail.Message> inbox, Map<Source, Freshness> freshness,
                          Map<Source, Long> versions) {

    public Freshness freshness(Source s) {
        return freshness.get(s);
    }

    public long version(Source s) {
        return versions.getOrDefault(s, 0L);
    }

    public Fact fact(Source s, String ref, String statement) {
        Freshness f = freshness.get(s);
        return new Fact(s.name(), ref, statement, version(s), f == null ? -1 : f.ageMinutes());
    }

    public Optional<Erp.Product> product(String sku) {
        return erp.products().stream().filter(p -> p.sku().equals(sku)).findFirst();
    }

    public Optional<Erp.StockItem> stock(String sku) {
        return erp.stock().stream().filter(s -> s.sku().equals(sku)).findFirst();
    }

    public Optional<Crm.Account> account(String id) {
        return crm.accounts().stream().filter(a -> a.id().equals(id)).findFirst();
    }

    public String accountName(String id) {
        return account(id).map(Crm.Account::name).orElse(id);
    }

    public Optional<Crm.Contact> contactByEmail(String email) {
        return crm.contacts().stream().filter(c -> c.email().equalsIgnoreCase(email)).findFirst();
    }

    public Optional<Crm.Contact> primaryContact(String accountId) {
        return crm.contacts().stream().filter(c -> c.accountId().equals(accountId)).findFirst();
    }

    public Optional<Crm.Account> accountByDomain(String domain) {
        return crm.accounts().stream().filter(a -> a.emailDomain().equalsIgnoreCase(domain)).findFirst();
    }

    public Optional<Erp.SalesOrder> order(String id) {
        return erp.orders().stream().filter(o -> o.id().equals(id)).findFirst();
    }

    public List<Erp.SalesOrder> openOrders() {
        return erp.orders().stream().filter(o -> o.status().equals("OPEN")).toList();
    }

    public Optional<Erp.Quotation> quotation(String id) {
        return erp.quotations().stream().filter(q -> q.id().equals(id)).findFirst();
    }

    public List<Planning.WorkOrder> workOrdersFor(String orderId) {
        return planning.workOrders().stream().filter(w -> orderId.equals(w.orderId())).toList();
    }

    public List<Erp.Reservation> reservationsFor(String orderId) {
        return erp.reservations().stream().filter(r -> orderId.equals(r.orderId())).toList();
    }

    public List<Planning.Routing> routings(String sku) {
        return planning.routings().stream().filter(r -> r.sku().equals(sku))
                .sorted((a, b) -> Boolean.compare(b.preferred(), a.preferred())).toList();
    }

    public Optional<Planning.Line> line(String id) {
        return planning.lines().stream().filter(l -> l.id().equals(id)).findFirst();
    }

    public int transitDays(String accountId) {
        return account(accountId).map(Crm.Account::transitDays).orElse(2);
    }

    public boolean canCommit() {
        return !freshness.get(Source.ERP).blocksCommitment() && !freshness.get(Source.PLANNING).blocksCommitment();
    }
}
