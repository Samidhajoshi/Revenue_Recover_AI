package com.recoverai.backend.service;

import com.recoverai.backend.entity.Customer;
import com.recoverai.backend.entity.Gateway;
import com.recoverai.backend.entity.Subscription;
import com.recoverai.backend.entity.Transaction;
import com.recoverai.backend.entity.enums.GatewayStatus;
import com.recoverai.backend.entity.enums.SubscriptionStatus;
import com.recoverai.backend.entity.enums.TransactionStatus;
import com.recoverai.backend.repository.CustomerRepository;
import com.recoverai.backend.repository.GatewayRepository;
import com.recoverai.backend.repository.SubscriptionRepository;
import com.recoverai.backend.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * CSV ingestion for transactions / subscriptions / gateways (Phase 1,
 * section 15). Rows are upserted (JPA save() on an existing id updates the
 * row). Customers referenced by transactions/subscriptions that don't yet
 * exist are created with sensible defaults.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CsvImportService {

    private final CustomerRepository customerRepository;
    private final TransactionRepository transactionRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final GatewayRepository gatewayRepository;

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreSurroundingSpaces(true)
            .setTrim(true)
            .build();

    public int importCustomers(MultipartFile file) throws Exception {
        int count = 0;
        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = FORMAT.parse(reader)) {
            for (CSVRecord rec : parser) {
                String id = get(rec, "id");
                if (id == null) continue;
                Customer c = customerRepository.findById(id).orElseGet(Customer::new);
                c.setId(id);
                c.setName(getOrDefault(rec, "name", id));
                c.setEmail(getOrDefault(rec, "email", id.toLowerCase() + "@example.com"));
                c.setPhone(get(rec, "phone"));
                c.setLtv(getOrDefaultDouble(rec, "ltv", 0.0));
                c.setTotalPayments(getInt(rec, "total_payments", 0));
                c.setSuccessfulPayments(getInt(rec, "successful_payments", 0));
                c.setFailedPayments(getInt(rec, "failed_payments", 0));
                c.setSegment(getOrDefault(rec, "segment", "STANDARD"));
                c.setOptedOut(Boolean.parseBoolean(getOrDefault(rec, "opted_out", "false")));
                customerRepository.save(c);
                count++;
            }
        }
        log.info("Imported {} customers", count);
        return count;
    }

    public int importTransactions(MultipartFile file) throws Exception {
        int count = 0;
        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = FORMAT.parse(reader)) {
            for (CSVRecord rec : parser) {
                String customerId = get(rec, "customer_id");
                ensureCustomer(customerId, get(rec, "customer_name"));

                Transaction tx = transactionRepository.findById(get(rec, "id"))
                        .orElseGet(Transaction::new);
                tx.setId(get(rec, "id"));
                tx.setCustomerId(customerId);
                tx.setAmount(getDouble(rec, "amount"));
                tx.setCurrency(getOrDefault(rec, "currency", "INR"));
                tx.setPaymentMethod(get(rec, "payment_method"));
                tx.setGateway(get(rec, "gateway"));
                tx.setBank(get(rec, "bank"));
                tx.setRegion(get(rec, "region"));
                tx.setStatus(parseEnum(get(rec, "status"), TransactionStatus.class, TransactionStatus.FAILED));
                tx.setFailureReason(get(rec, "failure_reason"));
                tx.setAttemptNumber(getInt(rec, "attempt_number", 1));
                LocalDateTime createdAt = getDateTime(rec, "created_at");
                if (createdAt != null) tx.setCreatedAt(createdAt);
                transactionRepository.save(tx);
                count++;
            }
        }
        log.info("Imported {} transactions", count);
        return count;
    }

    public int importSubscriptions(MultipartFile file) throws Exception {
        int count = 0;
        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = FORMAT.parse(reader)) {
            for (CSVRecord rec : parser) {
                String customerId = get(rec, "customer_id");
                ensureCustomer(customerId, get(rec, "customer_name"));

                Subscription sub = subscriptionRepository.findById(get(rec, "id"))
                        .orElseGet(Subscription::new);
                sub.setId(get(rec, "id"));
                sub.setCustomerId(customerId);
                sub.setAmount(getDouble(rec, "amount"));
                sub.setBillingCycle(get(rec, "billing_cycle"));
                sub.setNextPaymentDate(getDate(rec, "next_payment_date"));
                sub.setStatus(parseEnum(get(rec, "status"), SubscriptionStatus.class, SubscriptionStatus.FAILED));
                sub.setPaymentMethod(get(rec, "payment_method"));
                sub.setFailureReason(get(rec, "failure_reason"));
                sub.setRetryCount(getInt(rec, "retry_count", 0));
                subscriptionRepository.save(sub);
                count++;
            }
        }
        log.info("Imported {} subscriptions", count);
        return count;
    }

    public int importGateways(MultipartFile file) throws Exception {
        int count = 0;
        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = FORMAT.parse(reader)) {
            for (CSVRecord rec : parser) {
                Gateway gw = gatewayRepository.findById(get(rec, "id")).orElseGet(Gateway::new);
                gw.setId(get(rec, "id"));
                gw.setName(getOrDefault(rec, "name", get(rec, "id")));
                gw.setSuccessRate(asFraction(getDouble(rec, "success_rate")));
                gw.setFailureRate(asFraction(getDouble(rec, "failure_rate")));
                gw.setBaselineFailureRate(asFraction(getDouble(rec, "baseline_failure_rate")));
                gw.setStatus(parseEnum(get(rec, "status"), GatewayStatus.class, GatewayStatus.HEALTHY));
                gw.setCostPerTransaction(getDouble(rec, "cost_per_transaction"));
                gatewayRepository.save(gw);
                count++;
            }
        }
        log.info("Imported {} gateways", count);
        return count;
    }

    private void ensureCustomer(String customerId, String name) {
        if (customerId == null || customerId.isBlank()) return;
        if (!customerRepository.existsById(customerId)) {
            Customer c = Customer.builder()
                    .id(customerId)
                    .name(name == null || name.isBlank() ? customerId : name)
                    .email(customerId.toLowerCase() + "@example.com")
                    .ltv(0.0)
                    .totalPayments(0)
                    .successfulPayments(0)
                    .failedPayments(0)
                    .segment("STANDARD")
                    .optedOut(false)
                    .build();
            customerRepository.save(c);
        }
    }

    // ---- parsing helpers ----

    private String get(CSVRecord rec, String header) {
        if (!rec.isMapped(header)) return null;
        String v = rec.get(header);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private String getOrDefault(CSVRecord rec, String header, String def) {
        String v = get(rec, header);
        return v == null ? def : v;
    }

    /** Gateway rate CSVs may express rates as 0-1 fractions or 0-100 percentages; normalize to a 0-1 fraction. */
    private Double asFraction(Double value) {
        if (value == null) return null;
        return value > 1.0 ? value / 100.0 : value;
    }

    private Double getOrDefaultDouble(CSVRecord rec, String header, double def) {
        Double v = getDouble(rec, header);
        return v == null ? def : v;
    }

    private Double getDouble(CSVRecord rec, String header) {
        String v = get(rec, header);
        if (v == null) return null;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer getInt(CSVRecord rec, String header, int def) {
        String v = get(rec, header);
        if (v == null) return def;
        try {
            return (int) Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private LocalDateTime getDateTime(CSVRecord rec, String header) {
        String v = get(rec, header);
        if (v == null) return null;
        try {
            return LocalDateTime.parse(v);
        } catch (Exception e) {
            try {
                return LocalDate.parse(v).atStartOfDay();
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private LocalDate getDate(CSVRecord rec, String header) {
        String v = get(rec, header);
        if (v == null) return null;
        try {
            return LocalDate.parse(v, DateTimeFormatter.ISO_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    private <T extends Enum<T>> T parseEnum(String value, Class<T> type, T def) {
        if (value == null) return def;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (Exception e) {
            return def;
        }
    }
}
