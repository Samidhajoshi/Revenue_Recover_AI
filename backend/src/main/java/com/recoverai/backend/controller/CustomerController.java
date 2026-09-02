package com.recoverai.backend.controller;

import com.recoverai.backend.entity.Customer;
import com.recoverai.backend.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Read access to the customer records CSV-imported (or auto-created by
 * transaction/subscription import) - there was previously no way to see the
 * actual uploaded customer data (name, LTV, segment, history) in one place.
 */
@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerRepository customerRepository;

    @GetMapping
    public List<Customer> list(@RequestParam(required = false) String q) {
        List<Customer> all = customerRepository.findAll();
        if (q == null || q.isBlank()) {
            return all;
        }
        String needle = q.trim().toLowerCase();
        return all.stream()
                .filter(c -> (c.getId() != null && c.getId().toLowerCase().contains(needle))
                        || (c.getName() != null && c.getName().toLowerCase().contains(needle))
                        || (c.getEmail() != null && c.getEmail().toLowerCase().contains(needle)))
                .collect(Collectors.toList());
    }
}
