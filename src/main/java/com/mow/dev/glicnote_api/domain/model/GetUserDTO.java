package com.mow.dev.glicnote_api.domain.model;

import com.mow.dev.glicnote_api.domain.entity.UserEntity;


public record GetUserDTO(Long id, String name, String email, String cellPhone) {
    public GetUserDTO(UserEntity userEntity) {
        this(
                userEntity.getId(),
                userEntity.getEmail(),
                userEntity.getName(),
                userEntity.getCellPhone());
    }
}
