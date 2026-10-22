package com.glicnote.api;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class Models {
    private Models() {}
    public static final String MOMENTS = "fasting|after_breakfast|after_lunch|after_snack|after_dinner|other";
    public record Profile(UUID id, String name, String username, String email, String photoUrl,
                          String timeZone, Integer birthdayMonth, Integer birthdayDay, LocalDate birthDate,
                          OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    public record PublicProfile(UUID id, String name, String username, String photoUrl) {}
    public record ProfileInput(@Size(max=120) String name,
        @NotBlank @Pattern(regexp="[a-z0-9_]{3,30}") String username,
        @NotBlank @Size(max=80) String timeZone,
        @Min(1) @Max(12) Integer birthdayMonth, @Min(1) @Max(31) Integer birthdayDay, LocalDate birthDate) {}
    public record EmergencyContact(String name, String phone, String relationship, OffsetDateTime updatedAt) {}
    public record ContactInput(@NotBlank @Size(max=120) String name,
        @NotBlank @Pattern(regexp="\\+[1-9][0-9]{6,14}") String phone, @Size(max=80) String relationship) {}
    public record Reading(UUID id, UUID userId, int glucoseMgDl, BigDecimal insulinUnits, String moment,
        OffsetDateTime measuredAt, String timeZone, LocalDate localDate, int version,
        OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    public record ReadingInput(UUID id, @NotNull @Positive Integer glucoseMgDl,
        @DecimalMin("0") @Digits(integer=5, fraction=3) BigDecimal insulinUnits,
        @NotNull @Pattern(regexp=MOMENTS) String moment,
        @NotNull OffsetDateTime measuredAt, @NotBlank @Size(max=80) String timeZone) {}
    public record ReadingUpdate(@NotNull @Positive Integer glucoseMgDl,
        @DecimalMin("0") @Digits(integer=5, fraction=3) BigDecimal insulinUnits,
        @NotNull @Pattern(regexp=MOMENTS) String moment,
        @NotNull OffsetDateTime measuredAt, @NotBlank @Size(max=80) String timeZone,
        @NotNull @Min(0) Integer version) {}
    public record ReadingSummary(long count, BigDecimal averageGlucoseMgDl, Reading latest) {}
    public record Follow(UUID id, UUID requesterId, UUID targetId, String status, int version,
        OffsetDateTime requestedAt, OffsetDateTime respondedAt, OffsetDateTime endedAt) {}
    public record FollowView(Follow relationship, PublicProfile requester, PublicProfile target) {}
    public record Decision(@NotNull Boolean accept, @NotNull @Min(0) Integer version) {}
    public record VersionInput(@NotNull @Min(0) Integer version) {}
    public record Block(UUID blockedId, OffsetDateTime createdAt) {}
    public record Badge(UUID id, String code, String iconKey, String ruleType, Integer target,
        UUID specialDateId, String recurrence, int ruleVersion, int sortOrder, boolean active) {}
    public record SpecialDate(UUID id, String code, String kind, Integer month, Integer day, LocalDate specificDate, boolean active) {}
    public record EarnedBadge(UUID id, UUID userId, UUID badgeId, String edition, int ruleVersion,
        UUID sourceReadingId, OffsetDateTime achievedAt, OffsetDateTime earnedAt) {}
    public record EarnedBadgeView(EarnedBadge award, Badge definition) {}
    public record BadgeProgress(Badge definition, long current, long best, boolean achieved, List<EarnedBadge> awards) {}
    public record BadgeOverview(long readingCount, int currentStreak, int longestStreak, List<BadgeProgress> badges) {}
    public record Post(UUID id, UUID authorId, String type, UUID readingId, UUID earnedBadgeId,
        String caption, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    public record PostInput(@NotNull @Pattern(regexp="reading|badge") String type,
        @NotNull UUID referenceId, @Size(max=1000) String caption) {}
    public record CaptionInput(@Size(max=1000) String caption) {}
    public record FeedItem(UUID id, PublicProfile author, String type, String caption,
        OffsetDateTime createdAt, OffsetDateTime updatedAt, Reading reading, EarnedBadgeView badge) {}
    public record Notification(UUID id, UUID recipientId, UUID actorId, String type, UUID followId,
        Integer requestVersion, UUID earnedBadgeId, String eventKey, OffsetDateTime createdAt, OffsetDateTime readAt) {}
    public record Page<T>(List<T> items, String nextCursor) {}
}
