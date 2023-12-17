package dev.sosohappy.model.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

@Data
public class CodeChallengeDto {

    @NotEmpty
    private String codeChallenge;

}
