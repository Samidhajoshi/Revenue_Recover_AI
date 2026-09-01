package com.recoverai.backend.dto;

import lombok.Data;

@Data
public class RecoveryMessageRequest {
    /** e.g. "hinglish" or "english"; defaults to recoverai.llm.default-language when blank. */
    private String language;
}
