package com.mow.dev.glicnote_api.controller;


import com.mow.dev.glicnote_api.domain.entity.UserEntity;
import com.mow.dev.glicnote_api.domain.model.GetUserDTO;
import com.mow.dev.glicnote_api.domain.model.PostUserDTO;
import com.mow.dev.glicnote_api.repository.UserRepository;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("users")
public class UserController {


    @Autowired
    private UserRepository repository;


    @GetMapping("/{id}")
    public GetUserDTO getUser(@PathVariable Long id) {
        UserEntity user = repository.getReferenceById(id);

        return new GetUserDTO(user);
    }

    @GetMapping()
    public List<GetUserDTO> getUsers() {
        List<UserEntity> user = repository.findAll();

        return user.stream().map(GetUserDTO::new).collect(Collectors.toList());
    }

    @PostMapping
    public GetUserDTO createUser(@RequestBody @Valid PostUserDTO dto) {
        UserEntity user = new UserEntity();

        UserEntity userSaved = repository.save(user.dtoToEntity(dto));

        return new GetUserDTO(userSaved);

    }

}

