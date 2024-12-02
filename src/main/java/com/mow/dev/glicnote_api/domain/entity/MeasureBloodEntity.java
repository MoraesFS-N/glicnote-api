package com.mow.dev.glicnote_api.domain.entity;


import jakarta.persistence.*;
import lombok.*;

@Table(name = "measure_blood")
@Entity(name = "MeasureBlood")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(of = "id")
public class MeasureBloodEntity extends AuditableEntity{

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
}
