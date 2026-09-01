package com.recoverai.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverai.backend.config.LlmProperties;
import com.recoverai.backend.dto.llm.ChatCompletionRequest;
import com.recoverai.backend.dto.llm.ChatCompletionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * LLM integration (section 11 of the plan) via Groq's OpenAI-compatible
 * chat-completions API: natural-language generation and extraction only.
 * The LLM never decides or executes a financial action - message text and
 * extracted intent both flow back through ordinary validated code paths
 * (PolicyEngine / PromiseToPay persistence), the same as every other
 * intervention.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LlmService {

    private final WebClient groqWebClient;
    private final LlmProperties llmProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    public record PromiseToPayExtraction(
            String intent,
            Double amount,
            String promisedDate) {
    }

    /** llmUsed reflects whether the real API call succeeded, not just whether a client bean exists. */
    public record MessageResult(String text, boolean llmUsed) {
    }

    public record ExtractionResult(PromiseToPayExtraction extraction, boolean llmUsed) {
    }

    /** Result after date/amount validation on top of the raw LLM extraction. */
    public record ValidatedPromise(
            String intent,
            Double amount,
            LocalDate promisedDate,
            boolean valid,
            String validationReason) {
    }

    public boolean isAvailable() {
        String apiKey = System.getenv("GROQ_API_KEY");
        return llmProperties.isEnabled() && apiKey != null && !apiKey.isBlank();
    }

    /**
     * USE CASE 1 (section 11): a short, empathetic payment-recovery message
     * in the requested language (default Hinglish). Falls back to a plain
     * templated message if the LLM is unavailable, so callers never see a
     * missing-message failure.
     */
    public MessageResult generateRecoveryMessage(String customerName, double amount, String failureReason, String language) {
        String lang = (language == null || language.isBlank()) ? llmProperties.getDefaultLanguage() : language;
        if (!isAvailable()) {
            return new MessageResult(templatedFallbackMessage(customerName, amount, lang), false);
        }
        try {
            String prompt = "Write a short, warm, non-threatening payment-recovery message to a customer in "
                    + lang + ". "
                    + "Customer name: " + customerName + ". "
                    + "Amount due: Rs " + formatAmount(amount) + ". "
                    + "Failure reason (internal, do not quote verbatim): " + failureReason + ". "
                    + "The message must: mention the amount, invite the customer to retry or update their "
                    + "payment method, stay under 3 sentences, and never threaten or shame the customer. "
                    + "Output only the message text, no preamble, no quotes.";

            String text = chatComplete(prompt, null).trim();
            return text.isBlank()
                    ? new MessageResult(templatedFallbackMessage(customerName, amount, lang), false)
                    : new MessageResult(text, true);
        } catch (Exception e) {
            log.warn("LLM message generation failed, using fallback template: {}", e.getMessage());
            return new MessageResult(templatedFallbackMessage(customerName, amount, lang), false);
        }
    }

    /**
     * USE CASE 2 (section 11): extract a promise-to-pay intent from free
     * customer text. Returns intent=NONE (not a crash) when the LLM is
     * unavailable or the text carries no promise - the caller decides what
     * "no promise found" means for the case, this method never guesses.
     */
    public ExtractionResult extractPromiseToPay(String customerMessage) {
        if (!isAvailable()) {
            return new ExtractionResult(new PromiseToPayExtraction("NONE", null, null), false);
        }
        try {
            String prompt = "Extract a promise-to-pay intent from this customer message. "
                    + "Respond with ONLY a JSON object of the exact shape "
                    + "{\"intent\": string, \"amount\": number|null, \"promisedDate\": string|null}. "
                    + "intent must be exactly \"PROMISE_TO_PAY\" if the customer commits to a payment date, "
                    + "otherwise exactly \"NONE\". "
                    + "amount is the promised payment amount as a plain number (no currency symbol), or null if "
                    + "not stated. promisedDate must be an ISO-8601 date (YYYY-MM-DD) resolved against today's "
                    + "date, or null if intent is NONE or no date was given. "
                    + "Today's date is " + LocalDate.now() + ". "
                    + "Customer message: \"" + customerMessage.replace("\"", "'") + "\"";

            String json = chatComplete(prompt, "json_object");
            PromiseToPayExtraction extraction = objectMapper.readValue(json, PromiseToPayExtraction.class);
            return new ExtractionResult(extraction, true);
        } catch (Exception e) {
            log.warn("LLM promise-to-pay extraction failed, treating as NONE: {}", e.getMessage());
            return new ExtractionResult(new PromiseToPayExtraction("NONE", null, null), false);
        }
    }

    private String chatComplete(String prompt, String responseFormatType) {
        ChatCompletionRequest.ChatCompletionRequestBuilder builder = ChatCompletionRequest.builder()
                .model(llmProperties.getModel())
                .temperature(0.4)
                .maxTokens(400)
                .messages(List.of(ChatCompletionRequest.ChatMessage.builder()
                        .role("user")
                        .content(prompt)
                        .build()));
        if (responseFormatType != null) {
            builder.responseFormat(ChatCompletionRequest.ResponseFormat.builder().type(responseFormatType).build());
        }

        ChatCompletionResponse response = groqWebClient.post()
                .uri("/chat/completions")
                .bodyValue(builder.build())
                .retrieve()
                .bodyToMono(ChatCompletionResponse.class)
                .block(TIMEOUT);

        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            throw new IllegalStateException("Empty response from Groq");
        }
        return response.getChoices().get(0).getMessage().getContent();
    }

    /**
     * Spring Boot validates the extracted intent before it is stored
     * (plan section 11: "Spring Boot validates and stores this") - a promise
     * is only "valid" with a positive amount and a parseable, non-past date.
     */
    public ValidatedPromise validate(PromiseToPayExtraction extraction) {
        boolean isPromise = "PROMISE_TO_PAY".equalsIgnoreCase(extraction.intent());
        if (!isPromise) {
            return new ValidatedPromise("NONE", null, null, false, "No promise-to-pay intent detected.");
        }
        if (extraction.amount() == null || extraction.amount() <= 0) {
            return new ValidatedPromise(extraction.intent(), extraction.amount(), null, false,
                    "Promised amount missing or not positive.");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(extraction.promisedDate());
        } catch (DateTimeParseException | NullPointerException e) {
            return new ValidatedPromise(extraction.intent(), extraction.amount(), null, false,
                    "Promised date missing or not a valid ISO date.");
        }
        if (date.isBefore(LocalDate.now())) {
            return new ValidatedPromise(extraction.intent(), extraction.amount(), date, false,
                    "Promised date is in the past.");
        }
        return new ValidatedPromise(extraction.intent(), extraction.amount(), date, true, "Valid promise-to-pay.");
    }

    private String templatedFallbackMessage(String customerName, double amount, String language) {
        if ("hinglish".equalsIgnoreCase(language)) {
            return "Hi " + customerName + ", aapka Rs " + formatAmount(amount)
                    + " ka payment complete nahi ho paya. Aap chahein toh dobara payment try kar sakte hain "
                    + "ya apna payment method update kar sakte hain.";
        }
        return "Hi " + customerName + ", your payment of Rs " + formatAmount(amount)
                + " could not be completed. Please retry the payment or update your payment method.";
    }

    private String formatAmount(double amount) {
        return amount == Math.floor(amount) ? String.valueOf((long) amount) : String.valueOf(amount);
    }
}
