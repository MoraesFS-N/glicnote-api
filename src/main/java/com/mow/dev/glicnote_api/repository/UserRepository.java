package com.mow.dev.glicnote_api.repository;

import com.mow.dev.glicnote_api.domain.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
}
