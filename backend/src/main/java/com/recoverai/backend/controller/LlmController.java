package com.recoverai.backend.controller;

import com.recoverai.backend.dto.PromiseToPayRequest;
import com.recoverai.backend.dto.RecoveryMessageRequest;
import com.recoverai.backend.entity.Customer;
import com.recoverai.backend.entity.PromiseToPay;
import com.recoverai.backend.entity.RecoveryCase;
import com.recoverai.backend.repository.CustomerRepository;
import com.recoverai.backend.repository.PromiseToPayRepository;
import com.recoverai.backend.repository.RecoveryCaseRepository;
import com.recoverai.backend.service.LlmService;
import com.recoverai.backend.service.StateMachineService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * LLM-backed natural-language endpoints (plan section 11): message
 * generation and promise-to-pay extraction. Neither endpoint executes a
 * recovery action - message text is returned for the caller to send via the
 * normal SEND_MESSAGE/PAYMENT_LINK intervention path, and an extracted
 * promise is only ever recorded, never acted on automatically.
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class LlmController {

    private final LlmService llmService;
    private final RecoveryCaseRepository recoveryCaseRepository;
    private final CustomerRepository customerRepository;
    private final PromiseToPayRepository promiseToPayRepository;
    private final StateMachineService stateMachineService;

    @PostMapping("/{id}/message")
    public ResponseEntity<?> generateMessage(@PathVariable Long id, @RequestBody(required = false) RecoveryMessageRequest body) {
        RecoveryCase rc = getCase(id);
        Customer customer = rc.getCustomerId() == null ? null : customerRepository.findById(rc.getCustomerId()).orElse(null);
        String customerName = customer == null ? (rc.getCustomerId() == null ? "there" : rc.getCustomerId()) : customer.getName();
        String language = body == null ? null : body.getLanguage();

        LlmService.MessageResult result = llmService.generateRecoveryMessage(
                customerName, rc.getAmountAtRisk() == null ? 0 : rc.getAmountAtRisk(),
                rc.getDiagnosis(), language);

        stateMachineService.logEvent(rc, "MESSAGE_GENERATED", "LLM",
                "Generated recovery message" + (result.llmUsed() ? "" : " (fallback template; LLM unavailable)"),
                Map.of("language", language == null ? "default" : language));

        return ResponseEntity.ok(Map.of("caseId", id, "message", result.text(), "llmUsed", result.llmUsed()));
    }

    @PostMapping("/{id}/promise-to-pay")
    public ResponseEntity<?> extractPromiseToPay(@PathVariable Long id, @RequestBody PromiseToPayRequest body) {
        RecoveryCase rc = getCase(id);
        if (body == null || body.getMessage() == null || body.getMessage().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message is required"));
        }

        LlmService.ExtractionResult extractionResult = llmService.extractPromiseToPay(body.getMessage());
        LlmService.ValidatedPromise validated = llmService.validate(extractionResult.extraction());

        PromiseToPay saved = promiseToPayRepository.save(PromiseToPay.builder()
                .recoveryCaseId(id)
                .rawMessage(body.getMessage())
                .intent(validated.intent())
                .amount(validated.amount())
                .promisedDate(validated.promisedDate())
                .valid(validated.valid())
                .validationReason(validated.validationReason())
                .build());

        stateMachineService.logEvent(rc, "PROMISE_TO_PAY_EXTRACTED", "LLM",
                validated.validationReason() + (extractionResult.llmUsed() ? "" : " (LLM unavailable; treated as no promise)"),
                Map.of("intent", validated.intent(), "amount", String.valueOf(validated.amount()),
                        "promisedDate", String.valueOf(validated.promisedDate()), "valid", validated.valid(),
                        "llmUsed", extractionResult.llmUsed()));

        return ResponseEntity.ok(saved);
    }

    @GetMapping("/{id}/promise-to-pay")
    public List<PromiseToPay> listPromisesToPay(@PathVariable Long id) {
        return promiseToPayRepository.findByRecoveryCaseId(id);
    }

    private RecoveryCase getCase(Long id) {
        return recoveryCaseRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("RecoveryCase not found: " + id));
    }
}
