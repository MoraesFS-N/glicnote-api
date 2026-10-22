package com.glicnote.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import static com.glicnote.api.Models.*;

@RestController
@Validated
public class ApiController {
    final PeopleService people; final ReadingService readings; final SocialService social; final FeedService feed; final BadgeService badges;
    ApiController(PeopleService people,ReadingService readings,SocialService social,FeedService feed,BadgeService badges) {
        this.people=people; this.readings=readings; this.social=social; this.feed=feed; this.badges=badges;
    }
    @GetMapping("/health") Map<String,String> health() { return Map.of("status","ok"); }
    @GetMapping("/v1/me") Profile me(@AuthenticationPrincipal Jwt jwt) { return people.me(people.actor(jwt)); }
    @PutMapping("/v1/me") Profile updateMe(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody ProfileInput input) { return people.update(people.actor(jwt),input); }
    @GetMapping("/v1/me/emergency-contact") EmergencyContact contact(@AuthenticationPrincipal Jwt jwt) { return people.contact(people.actor(jwt)); }
    @PutMapping("/v1/me/emergency-contact") EmergencyContact saveContact(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody ContactInput input) { return people.saveContact(people.actor(jwt),input); }
    @DeleteMapping("/v1/me/emergency-contact") @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteContact(@AuthenticationPrincipal Jwt jwt) { people.deleteContact(people.actor(jwt)); }
    @GetMapping("/v1/users") List<PublicProfile> users(@AuthenticationPrincipal Jwt jwt,@RequestParam String query,@RequestParam(defaultValue="20") int limit) { return people.search(people.actor(jwt),query,limit); }
    @GetMapping("/v1/users/{id}") PublicProfile user(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) { return people.publicProfile(people.actor(jwt),id); }
    @PostMapping("/v1/readings") @ResponseStatus(HttpStatus.CREATED)
    Reading create(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody ReadingInput input) { return readings.create(people.actor(jwt),input); }
    @GetMapping("/v1/readings") Page<Reading> readings(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) LocalDate from,
        @RequestParam(required=false) LocalDate to,@RequestParam(required=false) String moment,@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor) {
        return readings.list(people.actor(jwt),from,to,moment,limit,cursor);
    }
    @GetMapping("/v1/readings/summary") ReadingSummary summary(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) LocalDate from,
        @RequestParam(required=false) LocalDate to,@RequestParam(required=false) String moment) { return readings.summary(people.actor(jwt),from,to,moment); }
    @GetMapping("/v1/readings/{id}") Reading reading(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) { return readings.get(people.actor(jwt),id); }
    @PutMapping("/v1/readings/{id}") Reading updateReading(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody ReadingUpdate input) { return readings.update(people.actor(jwt),id,input); }
    @DeleteMapping("/v1/readings/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteReading(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestParam @Min(0) int version) { readings.delete(people.actor(jwt),id,version); }
    @PostMapping("/v1/follow-requests/{targetId}") @ResponseStatus(HttpStatus.CREATED)
    Follow request(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID targetId) { return social.request(people.actor(jwt),targetId); }
    @PutMapping("/v1/follow-requests/{id}/decision")
    Follow decide(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody Decision input) { return social.decide(people.actor(jwt),id,input); }
    @PutMapping("/v1/follow-requests/{id}/cancel")
    Follow cancel(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody VersionInput input) { return social.cancel(people.actor(jwt),id,input.version()); }
    @GetMapping("/v1/follows") Page<FollowView> follows(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="outgoing") String direction,
        @RequestParam(defaultValue="accepted") String status,@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor) {
        return social.list(people.actor(jwt),direction,status,limit,cursor);
    }
    @DeleteMapping("/v1/follows/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void end(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestParam @Min(0) int version) { social.end(people.actor(jwt),id,version); }
    @PutMapping("/v1/blocks/{targetId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void block(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID targetId) { social.block(people.actor(jwt),targetId); }
    @DeleteMapping("/v1/blocks/{targetId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void unblock(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID targetId) { social.unblock(people.actor(jwt),targetId); }
    @GetMapping("/v1/blocks") List<Block> blocks(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="50") int limit) { return social.blocks(people.actor(jwt),limit); }
    @PostMapping("/v1/posts") @ResponseStatus(HttpStatus.CREATED)
    FeedItem post(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody PostInput input) { return feed.create(people.actor(jwt),input); }
    @GetMapping("/v1/feed") Page<FeedItem> feed(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor) { return feed.list(people.actor(jwt),limit,cursor); }
    @GetMapping("/v1/posts/{id}") FeedItem post(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) { return feed.get(people.actor(jwt),id); }
    @PutMapping("/v1/posts/{id}") FeedItem updatePost(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody CaptionInput input) { return feed.update(people.actor(jwt),id,input); }
    @DeleteMapping("/v1/posts/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void deletePost(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) { feed.delete(people.actor(jwt),id); }
    @GetMapping("/v1/me/badges") BadgeOverview badges(@AuthenticationPrincipal Jwt jwt) { return badges.overview(people.actor(jwt)); }
    @GetMapping("/v1/me/badges/{id}") EarnedBadgeView badge(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) { return badges.earned(people.actor(jwt),id); }
    @GetMapping("/v1/notifications") Page<Notification> notifications(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor) { return feed.notifications(people.actor(jwt),limit,cursor); }
    @PutMapping("/v1/notifications/{id}/read") @ResponseStatus(HttpStatus.NO_CONTENT)
    void readNotification(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) { feed.readNotification(people.actor(jwt),id); }
}
