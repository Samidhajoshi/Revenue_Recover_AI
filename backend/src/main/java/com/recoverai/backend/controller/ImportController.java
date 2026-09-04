package com.recoverai.backend.controller;

import com.recoverai.backend.service.CsvImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/import")
@RequiredArgsConstructor
public class ImportController {

    private final CsvImportService csvImportService;

    @PostMapping(value = "/customers", consumes = "multipart/form-data")
    public ResponseEntity<?> importCustomers(@RequestParam("file") MultipartFile file) {
        try {
            int count = csvImportService.importCustomers(file.getInputStream());
            return ResponseEntity.ok(Map.of("imported", count, "entity", "customers"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping(value = "/transactions", consumes = "multipart/form-data")
    public ResponseEntity<?> importTransactions(@RequestParam("file") MultipartFile file) {
        try {
            int count = csvImportService.importTransactions(file.getInputStream());
            return ResponseEntity.ok(Map.of("imported", count, "entity", "transactions"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping(value = "/subscriptions", consumes = "multipart/form-data")
    public ResponseEntity<?> importSubscriptions(@RequestParam("file") MultipartFile file) {
        try {
            int count = csvImportService.importSubscriptions(file.getInputStream());
            return ResponseEntity.ok(Map.of("imported", count, "entity", "subscriptions"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping(value = "/gateways", consumes = "multipart/form-data")
    public ResponseEntity<?> importGateways(@RequestParam("file") MultipartFile file) {
        try {
            int count = csvImportService.importGateways(file.getInputStream());
            return ResponseEntity.ok(Map.of("imported", count, "entity", "gateways"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
