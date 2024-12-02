package com.mow.dev.glicnote_api.domain.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;


public record PostUserDTO(
        @NotBlank String name,
        @NotBlank
        @Email
        String email,
        @NotBlank String cellPhone) {
}
