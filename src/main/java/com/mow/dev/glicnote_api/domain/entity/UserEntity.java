package com.mow.dev.glicnote_api.domain.entity;


import com.mow.dev.glicnote_api.domain.model.PostUserDTO;
import jakarta.persistence.*;
import lombok.*;

@Table(name = "users")
@Entity(name = "User")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(of = "id")
public class UserEntity extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name")
    private String name;

    @Column(name = "email")
    private String email;

    @Column(name = "cell_phone")
    private String cellPhone;


    public UserEntity dtoToEntity(PostUserDTO dto) {
        this.name = dto.name();
        this.email = dto.email();
        this.cellPhone = dto.cellPhone();

        return this;
    }
}
