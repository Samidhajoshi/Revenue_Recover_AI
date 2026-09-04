package com.recoverai.backend.config;

import com.recoverai.backend.repository.CustomerRepository;
import com.recoverai.backend.service.CsvImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Loads the bundled synthetic dataset (data_generator/output, copied into
 * src/main/resources/seed-data/ at build time) on startup if the database is
 * empty - so a fresh deploy (e.g. Render's ephemeral disk wiping the H2 file
 * on every restart) always has the 10k-case demo dataset ready without
 * anyone re-uploading CSVs by hand. A no-op once any customer exists, so it
 * never overwrites real imported data.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements ApplicationRunner {

    private final CustomerRepository customerRepository;
    private final CsvImportService csvImportService;

    @Value("${recoverai.seed.enabled:true}")
    private boolean enabled;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!enabled) {
            return;
        }
        if (customerRepository.count() > 0) {
            log.info("Database already has data - skipping seed.");
            return;
        }
        log.info("Database is empty - seeding bundled synthetic dataset...");
        int customers = csvImportService.importCustomers(new ClassPathResource("seed-data/customers.csv").getInputStream());
        int gateways = csvImportService.importGateways(new ClassPathResource("seed-data/gateways.csv").getInputStream());
        int transactions = csvImportService.importTransactions(new ClassPathResource("seed-data/transactions.csv").getInputStream());
        int subscriptions = csvImportService.importSubscriptions(new ClassPathResource("seed-data/subscriptions.csv").getInputStream());
        log.info("Seeded {} customers, {} gateways, {} transactions, {} subscriptions.",
                customers, gateways, transactions, subscriptions);
    }
}
